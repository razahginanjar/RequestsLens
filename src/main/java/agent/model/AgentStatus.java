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
    long   samplingDelays,
    long   lastSampleTimestampMs,
    long   baseIntervalMs,

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
    int    persistenceQueueDepth
) {}