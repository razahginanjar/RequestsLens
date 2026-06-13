package agent.core;

import agent.collector.gc.GcListener;
import agent.collector.heap.HeapSampler;
import agent.collector.spring.BeanMemoryMapper;
import agent.collector.spring.EndpointAggregator;
import agent.collector.spring.SpringInstrumentation;
import agent.alert.AlertEvaluator;
import agent.alert.WebhookDispatcher;
import agent.analysis.LeakDetector;
import agent.http.ProfilerHttpServer;
import agent.persistence.DatabaseConnectionPool;
import agent.persistence.PersistenceDaemon;
import agent.persistence.PersistenceWriter;
import agent.persistence.SchemaInitializer;
import agent.persistence.SqliteRepository;
import agent.sampling.AdaptiveSamplingController;
import agent.profiling.AllocationRecorder;
import agent.profiling.StackSampler;
import agent.profiling.ThreadMetrics;
import agent.profiling.TraceSupport;

import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * Java agent entry point.
 *
 * The JVM calls premain() before the target application's main() method.
 * This method must:
 *   1. Complete quickly (under 500ms)
 *   2. Start all background daemon threads
 *   3. Return — the application then starts normally
 *
 * Think of premain() like a constructor: set things up, then get out.
 */
public final class AgentMain {

    private static final Logger log = Logger.getLogger(AgentMain.class.getName());

    /**
     * Called by the JVM when -javaagent: is used at startup.
     *
     * @param agentArgs      the string after -javaagent:agent.jar=<agentArgs>
     *                       null if no arguments were provided
     * @param instrumentation the JVM Instrumentation API. Phase 2 uses it for
     *                        Byte Buddy class transformation (SpringInstrumentation)
     *                        and for Instrumentation.getObjectSize() in BeanMemoryMapper.
     */
    public static void premain(String agentArgs, Instrumentation instrumentation) {
        log.info("JVM Profiler Agent starting...");

        try {
            // 1. Load configuration from all sources
            AgentConfig config = AgentConfig.load(agentArgs);

            // 2. Create the registry that all components share. The base
            //    sampling interval seeds the adaptive SamplingStateHolder.
            CollectorRegistry registry = new CollectorRegistry(config.getBaseIntervalMs());

            // 2a. Store the JVM Instrumentation handle on the registry.
            //     BeanMemoryMapper needs it for Instrumentation.getObjectSize(),
            //     and Phase 4 components read it from here too. (Spec Step 10.)
            registry.setInstrumentation(instrumentation);

            // 2b. Phase 6: enable per-thread CPU/allocation counters and wire the
            //     request-trace support (sampling rate, caps, output buffer). Must
            //     be set before any request is handled.
            ThreadMetrics.init();
            AllocationRecorder.setInstrumentation(instrumentation);
            TraceSupport.enabled     = config.isTraceEnabled()
                                       && !config.getTracePackages().isBlank();
            TraceSupport.sampleRate  = config.getTraceSampleRate();
            TraceSupport.maxDepth    = config.getTraceMaxDepth();
            TraceSupport.maxSpans    = config.getTraceMaxSpans();
            TraceSupport.traceBuffer = registry.traceBuffer();

            // 3. Start the heap sampler daemon
            new HeapSampler(registry, config).start();

            // 4. Start the GC event listener
            new GcListener(registry.gcBuffer()).attach();

            // 5. Create the SINGLE shared collaborators used by both the
            //    bytecode advice and the aggregation daemon.
            //
            //    Why share? The Byte Buddy advice on ApplicationContext.refresh()
            //    hands the live Spring context to whichever BeanMemoryMapper is
            //    wired into SpringInstrumentation. The AggregationDaemon then
            //    scans beans through a BeanMemoryMapper too. If these were two
            //    different mapper instances, the daemon's mapper would never
            //    receive the context and /profiler/beans would stay empty.
            //    One instance, shared, keeps the context and the scanner aligned.
            //
            //    Likewise the EndpointAggregator owns a stateful rolling latency
            //    window and is the SINGLE consumer of the (destructive-drain)
            //    endpoint ring buffer. Exactly one instance must exist.
            BeanMemoryMapper   beanMapper         = new BeanMemoryMapper(instrumentation);
            EndpointAggregator endpointAggregator = new EndpointAggregator(registry.endpointBuffer());

            // 6. Install Spring bytecode instrumentation.
            //    Must run before Spring loads DispatcherServlet / refreshes its
            //    context, so the advice is in place when those classes load.
            //    We pass in the shared beanMapper so context discovery and bean
            //    scanning use the same instance.
            new SpringInstrumentation(instrumentation, registry, config, beanMapper).install();

            // 7. Start the Phase 3 persistence subsystem (if enabled). Done
            //    before the aggregation daemon so the PersistenceWriter is on
            //    the registry by the time the first aggregation cycle drains the
            //    heap/GC buffers. A persistence failure must never stop the agent.
            startPersistence(registry, config);

            // 8. Create the Phase 4 adaptive-sampling + alerting components.
            //    - AdaptiveSamplingController drives the shared SamplingStateHolder.
            //    - WebhookDispatcher posts alerts to the configured URL (or logs
            //      locally when none is set).
            //    - LeakDetector + AlertEvaluator turn metrics into state-change alerts.
            AdaptiveSamplingController adaptiveController =
                new AdaptiveSamplingController(config, registry.getSamplingStateHolder());
            WebhookDispatcher webhookDispatcher =
                new WebhookDispatcher(config.getWebhookUrl());
            LeakDetector leakDetector =
                new LeakDetector(config.getLeakDetectionWindowMs());
            AlertEvaluator alertEvaluator =
                new AlertEvaluator(config, webhookDispatcher, leakDetector);

            // 9. Start the aggregation daemon — the single owner that drains the
            //    endpoint/heap/GC buffers, publishes stats + RPS, refreshes the
            //    bean ranking, evaluates adaptive sampling + alerts, and enqueues
            //    samples for persistence, every 5 seconds.
            new AggregationDaemon(registry, endpointAggregator, beanMapper,
                adaptiveController, alertEvaluator).start();

            // 9b. Phase 6: start the always-on sampling profiler (flame graph) and,
            //     when method tracing + allocation detail are on, the JFR allocation
            //     profiler. Both are best-effort daemons that never block the app.
            if (config.isSamplingProfilerEnabled()) {
                StackSampler sampler = new StackSampler(config.getSamplingProfilerIntervalMs());
                registry.setStackSampler(sampler);
                sampler.start();
            }
            // (Phase 6 Amendment A) Per-object allocation detail is captured by
            // allocation-site bytecode instrumentation (see SpringInstrumentation),
            // not by a separate JFR daemon.

            // 10. Start the HTTP server
            new ProfilerHttpServer(registry, config).start();

            log.info("JVM Profiler Agent started successfully — "
                + "port=" + config.getHttpPort()
                + " instanceId=" + config.getInstanceId());

        } catch (Exception e) {
            // If agent setup fails, log and continue — never crash the target app
            log.severe("JVM Profiler Agent failed to start: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Called when attaching to an already-running JVM dynamically.
     * Delegates to premain() — same setup logic.
     */
    public static void agentmain(String agentArgs, Instrumentation instrumentation) {
        premain(agentArgs, instrumentation);
    }

    /**
     * Sets up the Phase 3 persistence subsystem: opens/creates the SQLite
     * database, initializes the schema, wires the {@link PersistenceWriter} and
     * {@link SqliteRepository} onto the registry, starts the
     * {@link PersistenceDaemon}, and registers a shutdown hook that flushes any
     * queued samples so data buffered just before exit is not lost.
     *
     * <p>This method is best-effort: any failure is logged and swallowed so the
     * agent (and the target application) keep running without persistence.
     */
    private static void startPersistence(CollectorRegistry registry, AgentConfig config) {
        if (!config.isPersistenceEnabled()) {
            log.info("Persistence disabled (profiler.persistence.enabled=false)");
            return;
        }

        try {
            // Ensure the parent directory of the db file exists.
            Path dbDir = Path.of(config.getPersistencePath()).getParent();
            if (dbDir != null) {
                Files.createDirectories(dbDir);
            }

            // Open the connection and create the schema (idempotent).
            DatabaseConnectionPool pool =
                new DatabaseConnectionPool(config.getPersistencePath());
            new SchemaInitializer().initialize(pool.get());

            // Build the repository + writer and publish them on the registry.
            SqliteRepository repository =
                new SqliteRepository(pool.get(), config.getInstanceId());
            PersistenceWriter writer =
                new PersistenceWriter(repository, registry.selfMetrics());

            registry.setSqliteRepository(repository);   // read path (history routes)
            registry.setPersistenceWriter(writer);       // write path (aggregation daemon)

            // Start the flush + purge daemon.
            new PersistenceDaemon(writer, repository,
                config.getPersistenceRetentionDays()).start();

            // Flush-on-shutdown: drain whatever is still queued and close the db.
            // Without this, samples buffered in the last <5s before exit are lost.
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("Agent shutdown — flushing persistence...");
                try {
                    writer.flush();
                } catch (Exception e) {
                    log.warning("Shutdown flush failed: " + e.getMessage());
                }
                pool.close();
            }, "profiler-shutdown"));

            log.info("Persistence started — db=" + config.getPersistencePath());

        } catch (Exception e) {
            // Persistence failure must not crash the agent.
            log.warning("Persistence setup failed — running without persistence: "
                + e.getMessage());
        }
    }
}