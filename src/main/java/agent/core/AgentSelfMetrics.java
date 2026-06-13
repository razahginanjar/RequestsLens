package agent.core;

import agent.model.AgentStatus;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.concurrent.atomic.LongAdder;

public final class AgentSelfMetrics {

    private final LongAdder droppedSamples    = new LongAdder();
    private final LongAdder samplingDelays     = new LongAdder();
    private volatile long   lastSampleTs      = 0L;
    private final long      startedAtMs       = System.currentTimeMillis();
    private final MemoryMXBean memBean        =
        ManagementFactory.getMemoryMXBean();

    // ── Phase 3 — persistence health ──────────────────────────────────────

    /**
     * Counts heap/GC samples dropped by the PersistenceWriter because its
     * bounded queue was full. Incremented on a Tier-1-ish path (the enqueue
     * call), so it must stay allocation-free — LongAdder satisfies that.
     */
    private final LongAdder droppedPersistenceSamples = new LongAdder();

    /**
     * Last observed depth of the PersistenceWriter queue. Written by the
     * persistence daemon (one writer thread), read by the HTTP thread — a
     * plain volatile int is the correct, cheapest tool for a gauge.
     */
    private volatile int    persistenceQueueDepth = 0;

    // ── Increment (Tier 1 safe) ───────────────────────────────────────────
    public void incrementDroppedSamples()  { droppedSamples.increment(); }
    public void incrementSamplingDelays()  { samplingDelays.increment(); }
    public void incrementDroppedPersistence() { droppedPersistenceSamples.increment(); }

    // ── Setters (Tier 2 safe) ─────────────────────────────────────────────
    public void setLastSampleTs(long ts)   { lastSampleTs = ts; }
    public void setPersistenceQueueDepth(int depth) { persistenceQueueDepth = depth; }

    // ── Snapshot (Tier 3 — HTTP thread only) ──────────────────────────────
    public AgentStatus snapshot(String instanceId, long baseIntervalMs) {
        return new AgentStatus(
            instanceId,
            System.currentTimeMillis() - startedAtMs,
            memBean.getHeapMemoryUsage().getUsed(),
            droppedSamples.sum(),
            samplingDelays.sum(),
            lastSampleTs,
            baseIntervalMs,
            droppedPersistenceSamples.sum(),
            persistenceQueueDepth
        );
    }
}