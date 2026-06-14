package agent.core;

import org.junit.jupiter.api.Test;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class AgentSelfMetricsTest {

    @Test
    void allCountersStartAtZero() {
        AgentSelfMetrics m = new AgentSelfMetrics();
        var snap = m.snapshot("test:7070", 10L);

        assertEquals(0, snap.droppedSamples());
        assertEquals(0, snap.droppedGcEvents());
        assertEquals(0, snap.droppedEndpointSamples());
        assertEquals(0, snap.droppedTraces());
        assertEquals(0, snap.samplingDelays());
        assertEquals(0, snap.lastSampleTimestampMs());
        assertEquals(0, snap.aggregationCycles());
        assertEquals(0, snap.aggregationErrors());
        assertEquals(0, snap.profilerHttpRequests());
        assertEquals(0, snap.profilerHttpAuthFailures());
        assertEquals(0, snap.droppedPersistenceSamples());
        assertEquals(0, snap.persistenceQueueDepth());
        assertEquals(0, snap.persistenceFlushes());
        assertEquals(0, snap.persistenceFlushFailures());
        assertEquals(0, snap.persistedHeapSamples());
        assertEquals(0, snap.persistedGcEvents());
        assertEquals(0, snap.persistencePurgeRuns());
        assertEquals(0, snap.persistencePurgeFailures());
    }

    @Test
    void incrementDroppedSamplesAccurately() {
        AgentSelfMetrics m = new AgentSelfMetrics();
        m.incrementDroppedSamples();
        m.incrementDroppedSamples();
        assertEquals(2, m.snapshot("x", 10).droppedSamples());
    }

    @Test
    void setLastSampleTsIsVisible() {
        AgentSelfMetrics m = new AgentSelfMetrics();
        m.setLastSampleTs(12345L);
        assertEquals(12345L, m.snapshot("x", 10).lastSampleTimestampMs());
    }

    @Test
    void recordsDroppedBuffersIndependently() {
        AgentSelfMetrics m = new AgentSelfMetrics();
        m.incrementDroppedGcEvents();
        m.incrementDroppedEndpointSamples();
        m.incrementDroppedEndpointSamples();
        m.incrementDroppedTraces();

        var snap = m.snapshot("x", 10);
        assertEquals(1, snap.droppedGcEvents());
        assertEquals(2, snap.droppedEndpointSamples());
        assertEquals(1, snap.droppedTraces());
    }

    @Test
    void recordsAggregationHealth() {
        AgentSelfMetrics m = new AgentSelfMetrics();
        m.recordAggregationCycle(111L, 7L);
        m.incrementAggregationErrors();
        m.recordAggregationCycle(222L, -1L);

        var snap = m.snapshot("x", 10);
        assertEquals(2, snap.aggregationCycles());
        assertEquals(1, snap.aggregationErrors());
        assertEquals(222L, snap.lastAggregationTimestampMs());
        assertEquals(0L, snap.lastAggregationDurationMs());
    }

    @Test
    void recordsProfilerHttpHealth() {
        AgentSelfMetrics m = new AgentSelfMetrics();
        m.recordProfilerHttpRequest();
        m.recordProfilerHttpRequest();
        m.incrementProfilerHttpAuthFailures();

        var snap = m.snapshot("x", 10);
        assertEquals(2, snap.profilerHttpRequests());
        assertEquals(1, snap.profilerHttpAuthFailures());
        assertTrue(snap.lastProfilerHttpRequestTimestampMs() > 0);
    }

    @Test
    void recordsPersistenceHealth() {
        AgentSelfMetrics m = new AgentSelfMetrics();
        m.incrementDroppedPersistence();
        m.setPersistenceQueueDepth(42);
        m.recordPersistenceFlush(111L, -1L, 3L, 2L);
        m.incrementPersistenceFlushFailures();
        m.recordPersistencePurge(222L, 7L);
        m.incrementPersistencePurgeFailures();

        var snap = m.snapshot("x", 10);
        assertEquals(1, snap.droppedPersistenceSamples());
        assertEquals(42, snap.persistenceQueueDepth());
        assertEquals(1, snap.persistenceFlushes());
        assertEquals(1, snap.persistenceFlushFailures());
        assertEquals(111L, snap.lastPersistenceFlushTimestampMs());
        assertEquals(0L, snap.lastPersistenceFlushDurationMs());
        assertEquals(3L, snap.persistedHeapSamples());
        assertEquals(2L, snap.persistedGcEvents());
        assertEquals(1, snap.persistencePurgeRuns());
        assertEquals(1, snap.persistencePurgeFailures());
        assertEquals(222L, snap.lastPersistencePurgeTimestampMs());
        assertEquals(7L, snap.lastPersistencePurgeDeletedRows());
    }

    @Test
    void countersAreConcurrentlySafe() throws InterruptedException {
        AgentSelfMetrics m = new AgentSelfMetrics();
        int threads    = 8;
        int perThread  = 1000;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                for (int j = 0; j < perThread; j++) {
                    m.incrementDroppedSamples();
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        assertEquals((long) threads * perThread,
            m.snapshot("x", 10).droppedSamples());
    }

    @Test
    void agentHeapUsedBytesIsPositive() {
        AgentSelfMetrics m  = new AgentSelfMetrics();
        var snap = m.snapshot("x", 10);
        assertTrue(snap.agentHeapUsedBytes() > 0,
            "Agent heap usage should be measurable");
    }
}
