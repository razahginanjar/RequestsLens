package agent.core;

import agent.model.AgentStatus;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.concurrent.atomic.LongAdder;

public final class AgentSelfMetrics {

    private final LongAdder droppedSamples = new LongAdder();
    private final LongAdder droppedGcEvents = new LongAdder();
    private final LongAdder droppedEndpointSamples = new LongAdder();
    private final LongAdder droppedTraces = new LongAdder();
    private final LongAdder samplingDelays = new LongAdder();

    private volatile long lastSampleTs = 0L;
    private final long startedAtMs = System.currentTimeMillis();
    private final MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();

    private final LongAdder droppedPersistenceSamples = new LongAdder();
    private volatile int persistenceQueueDepth = 0;

    private final LongAdder aggregationCycles = new LongAdder();
    private final LongAdder aggregationErrors = new LongAdder();
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

    public void setPersistenceQueueDepth(int depth) {
        persistenceQueueDepth = depth;
    }

    public AgentStatus snapshot(String instanceId, long baseIntervalMs) {
        return new AgentStatus(
            instanceId,
            System.currentTimeMillis() - startedAtMs,
            memBean.getHeapMemoryUsage().getUsed(),
            droppedSamples.sum(),
            droppedGcEvents.sum(),
            droppedEndpointSamples.sum(),
            droppedTraces.sum(),
            samplingDelays.sum(),
            lastSampleTs,
            baseIntervalMs,
            aggregationCycles.sum(),
            aggregationErrors.sum(),
            lastAggregationTimestampMs,
            lastAggregationDurationMs,
            profilerHttpRequests.sum(),
            profilerHttpAuthFailures.sum(),
            lastProfilerHttpRequestTimestampMs,
            droppedPersistenceSamples.sum(),
            persistenceQueueDepth
        );
    }
}
