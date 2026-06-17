package agent.core;

import agent.buffer.RingBuffer;
import agent.collector.jfr.JfrEventRecorder;
import agent.model.BeanMemoryInfo;
import agent.model.CpuSnapshot;
import agent.model.EndpointSample;
import agent.model.EndpointStats;
import agent.model.GcEvent;
import agent.model.HeapSnapshot;
import agent.model.JfrEvent;
import agent.model.LeakWarning;
import agent.model.LiveLogEvent;
import agent.model.RequestTrace;
import agent.persistence.PersistenceWriter;
import agent.persistence.SqliteRepository;
import agent.profiling.asyncprofiler.AsyncProfilerController;
import agent.sampling.SamplingStateHolder;
import agent.profiling.StackSampler;

import java.lang.instrument.Instrumentation;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class CollectorRegistry {

    // ── Phase 1 buffers ───────────────────────────────────────────────────
    private final RingBuffer<HeapSnapshot> heapBuffer;
    private final RingBuffer<GcEvent>      gcBuffer;
    private final RingBuffer<CpuSnapshot>  cpuBuffer;
    private final RingBuffer<LiveLogEvent> logBuffer;
    private final RingBuffer<JfrEvent>     jfrBuffer;
    private final AgentSelfMetrics         selfMetrics;
    private final InstrumentationDiagnostics instrumentationDiagnostics;

    // ── Phase 2 additions ─────────────────────────────────────────────────

    /**
     * Ring buffer for raw endpoint measurements.
     * Written by DispatcherServlet advice (Tier 1), drained by EndpointAggregator.
     */
    private final RingBuffer<EndpointSample> endpointBuffer;

    /**
     * Latest bean memory rankings — updated every 30s by BeanMemoryMapper.
     * CopyOnWriteArrayList is used because reads (HTTP thread) and writes
     * (aggregation thread) happen concurrently. Writes are rare (every 30s),
     * reads are frequent.
     */
    private final CopyOnWriteArrayList<BeanMemoryInfo> beanMemoryRanking;

    /**
     * Latest per-endpoint statistics — recomputed every 5s by the
     * AggregationDaemon, which is the SINGLE consumer of {@link #endpointBuffer}.
     *
     * <p>Why store the result here instead of recomputing in the HTTP route?
     * {@link RingBuffer#drainTo} is destructive: it clears each slot as it
     * reads so that the next write knows the slot is free. The buffer is
     * therefore a single-consumer structure. If both the daemon and the HTTP
     * route drained it, they would steal samples from each other and neither
     * would see a complete picture. We resolve this by letting only the daemon
     * drain the buffer and publishing its computed snapshot here for the HTTP
     * thread to read.
     *
     * <p>CopyOnWriteArrayList is used for the same reason as
     * {@link #beanMemoryRanking}: rare writes (every 5s on the daemon thread),
     * frequent lock-free reads (on the HTTP thread).
     */
    private final CopyOnWriteArrayList<EndpointStats> endpointStats;

    /**
     * The JVM Instrumentation object — passed from premain().
     * Needed by BeanMemoryMapper to call Instrumentation.getObjectSize().
     */
    private volatile Instrumentation instrumentation;

    /**
     * Current requests per second across all endpoints.
     * Written by EndpointAggregator, read by AdaptiveSamplingController (Phase 4).
     */
    private volatile double currentRps = 0.0;

    // ── Phase 3 additions (persistence) ───────────────────────────────────

    /**
     * The async writer that buffers heap/GC samples and batches them to SQLite.
     * Set by AgentMain only when persistence is enabled; remains null otherwise.
     * The AggregationDaemon checks for null before enqueuing.
     */
    private volatile PersistenceWriter persistenceWriter;

    /**
     * The repository used by the history HTTP routes to read persisted data.
     * Set by AgentMain when persistence is enabled; null when disabled (the
     * history routes return 503 in that case).
     */
    private volatile SqliteRepository sqliteRepository;

    /**
     * The most recent heap snapshot, updated by the HeapSampler on every tick.
     *
     * <p>Why this exists: in Phase 3 the AggregationDaemon DRAINS the heap ring
     * buffer to feed persistence, which empties it. The live /profiler/heap
     * "current" value must therefore not depend on the buffer's contents. This
     * volatile cache always holds the latest sample regardless of draining.
     */
    private volatile HeapSnapshot latestHeapSnapshot;
    private volatile CpuSnapshot latestCpuSnapshot;

    // ── Phase 4 additions (adaptive sampling & alerting) ──────────────────

    /**
     * Shared sampling state (NORMAL/THROTTLED + effective interval). Written by
     * the AdaptiveSamplingController (aggregation daemon), read by the
     * HeapSampler (sampler daemon) and the /profiler/status route.
     */
    private final SamplingStateHolder samplingStateHolder;

    /**
     * Leak warnings active as of the most recent aggregation cycle — exposed by
     * GET /profiler/leaks. CopyOnWriteArrayList for lock-free concurrent reads
     * (HTTP thread) against rare writes (aggregation daemon).
     */
    private final CopyOnWriteArrayList<LeakWarning> activeLeakWarnings;

    // ── Phase 6 additions (deep request profiling) ────────────────────────

    /**
     * Ring buffer of finished request traces, written by TraceSupport (request
     * thread) and drained by the AggregationDaemon, which publishes the most
     * recent N into {@link #recentTraces}.
     */
    private final RingBuffer<RequestTrace> traceBuffer;

    /** Most recent request traces, for GET /profiler/traces and /trace/{id}. */
    private final CopyOnWriteArrayList<RequestTrace> recentTraces;

    /** The always-on sampling profiler; null if disabled. Set by AgentMain. */
    private volatile StackSampler stackSampler;

    /** The optional in-process JFR event recorder; null when disabled. */
    private volatile JfrEventRecorder jfrEventRecorder;

    /** Optional embedded async-profiler controller; set by AgentMain. */
    private volatile AsyncProfilerController asyncProfilerController;

    public CollectorRegistry(long baseIntervalMs) {
        this(baseIntervalMs, 1000, 1000);
    }

    public CollectorRegistry(long baseIntervalMs, int logBufferCapacity) {
        this(baseIntervalMs, logBufferCapacity, 1000);
    }

    public CollectorRegistry(long baseIntervalMs, int logBufferCapacity,
                             int jfrBufferCapacity) {
        this.heapBuffer          = new RingBuffer<>(1000);
        this.gcBuffer            = new RingBuffer<>(500);
        this.cpuBuffer           = new RingBuffer<>(1000);
        this.logBuffer           = new RingBuffer<>(Math.max(10, logBufferCapacity));
        this.jfrBuffer           = new RingBuffer<>(Math.max(10, jfrBufferCapacity));
        this.endpointBuffer      = new RingBuffer<>(2000);
        this.selfMetrics         = new AgentSelfMetrics();
        this.instrumentationDiagnostics = new InstrumentationDiagnostics();
        this.beanMemoryRanking   = new CopyOnWriteArrayList<>();
        this.endpointStats       = new CopyOnWriteArrayList<>();
        this.samplingStateHolder = new SamplingStateHolder(baseIntervalMs);
        this.activeLeakWarnings  = new CopyOnWriteArrayList<>();
        this.traceBuffer         = new RingBuffer<>(256);
        this.recentTraces        = new CopyOnWriteArrayList<>();
    }

    // ── Getters ───────────────────────────────────────────────────────────
    public RingBuffer<HeapSnapshot>   heapBuffer()          { return heapBuffer; }
    public RingBuffer<GcEvent>        gcBuffer()            { return gcBuffer; }
    public RingBuffer<CpuSnapshot>    cpuBuffer()           { return cpuBuffer; }
    public RingBuffer<LiveLogEvent>   logBuffer()           { return logBuffer; }
    public RingBuffer<JfrEvent>       jfrBuffer()           { return jfrBuffer; }
    public RingBuffer<EndpointSample> endpointBuffer()      { return endpointBuffer; }
    public AgentSelfMetrics           selfMetrics()         { return selfMetrics; }
    public InstrumentationDiagnostics instrumentationDiagnostics() { return instrumentationDiagnostics; }
    public List<BeanMemoryInfo>       beanMemoryRanking()   { return Collections.unmodifiableList(beanMemoryRanking); }
    public List<EndpointStats>        endpointStats()       { return Collections.unmodifiableList(endpointStats); }
    public double                     getCurrentRps()       { return currentRps; }
    public Instrumentation            getInstrumentation()  { return instrumentation; }
    public SamplingStateHolder        getSamplingStateHolder() { return samplingStateHolder; }

    // ── Setters ───────────────────────────────────────────────────────────
    public void setInstrumentation(Instrumentation inst)    { this.instrumentation = inst; }
    public void setCurrentRps(double rps)                   { this.currentRps = rps; }
    public void updateBeanRanking(List<BeanMemoryInfo> ranking) {
        beanMemoryRanking.clear();
        beanMemoryRanking.addAll(ranking);
    }

    /**
     * Publishes a freshly computed per-endpoint statistics snapshot.
     *
     * <p>Called only by the AggregationDaemon (the single buffer consumer).
     * The HTTP thread reads the published snapshot via {@link #endpointStats()}.
     * We clear-then-addAll so the published view always reflects exactly the
     * most recent aggregation cycle, never a mix of old and new entries.
     */
    public void updateEndpointStats(List<EndpointStats> stats) {
        endpointStats.clear();
        endpointStats.addAll(stats);
    }

    // ── Phase 3 accessors (persistence) ───────────────────────────────────
    public PersistenceWriter getPersistenceWriter()          { return persistenceWriter; }
    public void setPersistenceWriter(PersistenceWriter w)    { this.persistenceWriter = w; }

    public SqliteRepository getSqliteRepository()            { return sqliteRepository; }
    public void setSqliteRepository(SqliteRepository r)      { this.sqliteRepository = r; }

    public HeapSnapshot getLatestHeapSnapshot()              { return latestHeapSnapshot; }
    public void setLatestHeapSnapshot(HeapSnapshot s)        { this.latestHeapSnapshot = s; }

    public CpuSnapshot getLatestCpuSnapshot()                { return latestCpuSnapshot; }
    public void setLatestCpuSnapshot(CpuSnapshot s)          { this.latestCpuSnapshot = s; }

    // ── Phase 4 accessors (alerting) ──────────────────────────────────────
    /** Publishes the leak warnings active as of the latest aggregation cycle. */
    public void setActiveLeakWarnings(List<LeakWarning> warnings) {
        activeLeakWarnings.clear();
        activeLeakWarnings.addAll(warnings);
    }

    /** The currently active leak warnings (for GET /profiler/leaks). */
    public List<LeakWarning> getActiveLeakWarnings() {
        return Collections.unmodifiableList(activeLeakWarnings);
    }

    // ── Phase 6 accessors (deep request profiling) ────────────────────────
    public RingBuffer<RequestTrace> traceBuffer()          { return traceBuffer; }
    public List<RequestTrace>       recentTraces()         { return Collections.unmodifiableList(recentTraces); }
    public void updateRecentTraces(List<RequestTrace> traces) {
        recentTraces.clear();
        recentTraces.addAll(traces);
    }
    public StackSampler getStackSampler()                  { return stackSampler; }
    public void setStackSampler(StackSampler s)            { this.stackSampler = s; }
    public JfrEventRecorder getJfrEventRecorder()          { return jfrEventRecorder; }
    public void setJfrEventRecorder(JfrEventRecorder r)    { this.jfrEventRecorder = r; }
    public AsyncProfilerController getAsyncProfilerController() { return asyncProfilerController; }
    public void setAsyncProfilerController(AsyncProfilerController c) { this.asyncProfilerController = c; }
}
