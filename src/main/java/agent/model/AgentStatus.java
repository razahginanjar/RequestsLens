package agent.model;

/**
 * A snapshot of the agent's own health — returned by GET /profiler/status.
 *
 * This grows over time as new subsystems are added. Fields added in
 * later phases (sampling state, webhook failures, etc.) will be added here.
 */
public record AgentStatus(
    String instanceId,
    long   uptimeMs,
    long   agentHeapUsedBytes,
    long   droppedSamples,
    long   droppedGcEvents,
    long   droppedEndpointSamples,
    long   droppedCpuSamples,
    long   droppedTraces,
    long   samplingDelays,
    long   lastSampleTimestampMs,
    long   lastCpuSampleTimestampMs,
    long   baseIntervalMs,
    long   aggregationCycles,
    long   aggregationErrors,
    long   lastAggregationTimestampMs,
    long   lastAggregationDurationMs,
    long   profilerHttpRequests,
    long   profilerHttpAuthFailures,
    long   lastProfilerHttpRequestTimestampMs,

    /**
     * Phase 3 — number of heap/GC samples the PersistenceWriter dropped
     * because its bounded queue was full (SQLite not keeping up). Non-zero
     * means persisted history has gaps; investigate disk I/O.
     */
    long   droppedPersistenceSamples,

    /**
     * Phase 3 — current depth of the PersistenceWriter queue at the last
     * flush. A value trending toward the queue capacity is an early warning
     * that writes are falling behind.
     */
    int    persistenceQueueDepth,

    long   persistenceFlushes,
    long   persistenceFlushFailures,
    long   lastPersistenceFlushTimestampMs,
    long   lastPersistenceFlushDurationMs,
    long   persistedHeapSamples,
    long   persistedGcEvents,
    long   persistedCpuSamples,
    long   persistencePurgeRuns,
    long   persistencePurgeFailures,
    long   lastPersistencePurgeTimestampMs,
    long   lastPersistencePurgeDeletedRows
) {
    public long totalDroppedSamples() {
        return droppedSamples
            + droppedGcEvents
            + droppedEndpointSamples
            + droppedCpuSamples
            + droppedTraces
            + droppedPersistenceSamples;
    }

    public long totalInternalErrors() {
        return aggregationErrors
            + persistenceFlushFailures
            + persistencePurgeFailures;
    }
}
