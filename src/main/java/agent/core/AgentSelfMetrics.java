package agent.core;

import agent.model.AgentStatus;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.concurrent.atomic.LongAdder;

public final class AgentSelfMetrics {

    private final LongAdder droppedSamples = new LongAdder();
    private final LongAdder droppedGcEvents = new LongAdder();
    private final LongAdder droppedEndpointSamples = new LongAdder();
    private final LongAdder droppedCpuSamples = new LongAdder();
    private final LongAdder droppedTraces = new LongAdder();
    private final LongAdder samplingDelays = new LongAdder();

    private volatile long lastSampleTs = 0L;
    private volatile long lastCpuSampleTs = 0L;
    private final long startedAtMs = System.currentTimeMillis();
    private final MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();

    private final LongAdder droppedPersistenceSamples = new LongAdder();
    private volatile int persistenceQueueDepth = 0;
    private final LongAdder persistenceFlushes = new LongAdder();
    private final LongAdder persistenceFlushFailures = new LongAdder();
    private final LongAdder persistedHeapSamples = new LongAdder();
    private final LongAdder persistedGcEvents = new LongAdder();
    private final LongAdder persistedCpuSamples = new LongAdder();
    private volatile long lastPersistenceFlushTimestampMs = 0L;
    private volatile long lastPersistenceFlushDurationMs = 0L;
    private final LongAdder persistencePurgeRuns = new LongAdder();
    private final LongAdder persistencePurgeFailures = new LongAdder();
    private volatile long lastPersistencePurgeTimestampMs = 0L;
    private volatile long lastPersistencePurgeDeletedRows = 0L;

    private final LongAdder aggregationCycles = new LongAdder();
    private final LongAdder aggregationErrors = new LongAdder();
    private final LongAdder internalErrors = new LongAdder();
    private volatile long lastAggregationTimestampMs = 0L;
    private volatile long lastAggregationDurationMs = 0L;

    private final LongAdder profilerHttpRequests = new LongAdder();
    private final LongAdder profilerHttpAuthFailures = new LongAdder();
    private volatile long lastProfilerHttpRequestTimestampMs = 0L;

    public void incrementDroppedSamples() {
        droppedSamples.increment();
    }

    public void incrementDroppedGcEvents() {
        droppedGcEvents.increment();
    }

    public void incrementDroppedEndpointSamples() {
        droppedEndpointSamples.increment();
    }

    public void incrementDroppedCpuSamples() {
        droppedCpuSamples.increment();
    }

    public void incrementDroppedTraces() {
        droppedTraces.increment();
    }

    public void incrementSamplingDelays() {
        samplingDelays.increment();
    }

    public void incrementDroppedPersistence() {
        droppedPersistenceSamples.increment();
    }

    public void incrementAggregationErrors() {
        aggregationErrors.increment();
    }

    public void incrementInternalErrors() {
        internalErrors.increment();
    }

    public void recordAggregationCycle(long timestampMs, long durationMs) {
        aggregationCycles.increment();
        lastAggregationTimestampMs = timestampMs;
        lastAggregationDurationMs = Math.max(0L, durationMs);
    }

    public void recordProfilerHttpRequest() {
        profilerHttpRequests.increment();
        lastProfilerHttpRequestTimestampMs = System.currentTimeMillis();
    }

    public void incrementProfilerHttpAuthFailures() {
        profilerHttpAuthFailures.increment();
    }

    public void setLastSampleTs(long ts) {
        lastSampleTs = ts;
    }

    public void setLastCpuSampleTs(long ts) {
        lastCpuSampleTs = ts;
    }

    public void setPersistenceQueueDepth(int depth) {
        persistenceQueueDepth = depth;
    }

    public void recordPersistenceFlush(long timestampMs, long durationMs,
                                       long heapRows, long gcRows, long cpuRows) {
        persistenceFlushes.increment();
        persistedHeapSamples.add(Math.max(0L, heapRows));
        persistedGcEvents.add(Math.max(0L, gcRows));
        persistedCpuSamples.add(Math.max(0L, cpuRows));
        lastPersistenceFlushTimestampMs = timestampMs;
        lastPersistenceFlushDurationMs = Math.max(0L, durationMs);
    }

    public void incrementPersistenceFlushFailures() {
        persistenceFlushFailures.increment();
    }

    public void recordPersistencePurge(long timestampMs, long deletedRows) {
        persistencePurgeRuns.increment();
        lastPersistencePurgeTimestampMs = timestampMs;
        lastPersistencePurgeDeletedRows = Math.max(0L, deletedRows);
    }

    public void incrementPersistencePurgeFailures() {
        persistencePurgeFailures.increment();
    }

    public AgentStatus snapshot(String instanceId, long baseIntervalMs) {
        return new AgentStatus(
            instanceId,
            System.currentTimeMillis() - startedAtMs,
            memBean.getHeapMemoryUsage().getUsed(),
            droppedSamples.sum(),
            droppedGcEvents.sum(),
            droppedEndpointSamples.sum(),
            droppedCpuSamples.sum(),
            droppedTraces.sum(),
            samplingDelays.sum(),
            lastSampleTs,
            lastCpuSampleTs,
            baseIntervalMs,
            aggregationCycles.sum(),
            aggregationErrors.sum(),
            internalErrors.sum(),
            lastAggregationTimestampMs,
            lastAggregationDurationMs,
            profilerHttpRequests.sum(),
            profilerHttpAuthFailures.sum(),
            lastProfilerHttpRequestTimestampMs,
            droppedPersistenceSamples.sum(),
            persistenceQueueDepth,
            persistenceFlushes.sum(),
            persistenceFlushFailures.sum(),
            lastPersistenceFlushTimestampMs,
            lastPersistenceFlushDurationMs,
            persistedHeapSamples.sum(),
            persistedGcEvents.sum(),
            persistedCpuSamples.sum(),
            persistencePurgeRuns.sum(),
            persistencePurgeFailures.sum(),
            lastPersistencePurgeTimestampMs,
            lastPersistencePurgeDeletedRows
        );
    }
}
