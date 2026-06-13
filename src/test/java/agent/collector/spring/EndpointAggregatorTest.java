package agent.collector.spring;

import agent.buffer.RingBuffer;
import agent.model.EndpointSample;
import agent.model.EndpointStats;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link EndpointAggregator} (Phase 2 spec, Step 14).
 *
 * <p>These tests exercise the aggregator in isolation: we write raw
 * {@link EndpointSample}s into a ring buffer, then assert on the
 * {@link EndpointStats} the aggregator computes after draining it.
 *
 * <p>The aggregator is the single consumer of the buffer in production, so
 * each test uses its own buffer + aggregator pair — no shared state leaks
 * between tests.
 */
class EndpointAggregatorTest {

    @Test
    void returnsEmptyWhenNoSamples() {
        // With nothing written, the aggregator has no windows and no new
        // samples, so it must return an empty list (never null).
        RingBuffer<EndpointSample> buffer = new RingBuffer<>(100);
        EndpointAggregator agg = new EndpointAggregator(buffer);
        assertTrue(agg.aggregate().isEmpty());
    }

    @Test
    void computesAverageLatency() {
        // Three GET /hello samples at 10/20/30ms -> mean of 20ms.
        RingBuffer<EndpointSample> buffer = new RingBuffer<>(100);
        buffer.write(sample("GET", "/hello", 10));
        buffer.write(sample("GET", "/hello", 20));
        buffer.write(sample("GET", "/hello", 30));

        EndpointAggregator agg = new EndpointAggregator(buffer);
        List<EndpointStats> stats = agg.aggregate();

        assertEquals(1, stats.size());
        assertEquals(20.0, stats.get(0).avgLatencyMs(), 0.1);
    }

    @Test
    void separatesEndpointsByPathAndMethod() {
        // GET /hello, POST /hello and GET /slow are three distinct endpoints —
        // the key is "METHOD path", so method and path both matter.
        RingBuffer<EndpointSample> buffer = new RingBuffer<>(100);
        buffer.write(sample("GET",  "/hello", 10));
        buffer.write(sample("POST", "/hello", 50));
        buffer.write(sample("GET",  "/slow",  200));

        EndpointAggregator agg = new EndpointAggregator(buffer);
        List<EndpointStats> stats = agg.aggregate();

        assertEquals(3, stats.size());
    }

    @Test
    void sortsByAverageLatencyDescending() {
        // The aggregator returns slowest-first so the worst offenders surface
        // at the top of /profiler/endpoints.
        RingBuffer<EndpointSample> buffer = new RingBuffer<>(100);
        buffer.write(sample("GET", "/fast", 5));
        buffer.write(sample("GET", "/slow", 300));

        EndpointAggregator agg = new EndpointAggregator(buffer);
        List<EndpointStats> stats = agg.aggregate();

        // Slowest endpoint should be first
        assertEquals("/slow", stats.get(0).path());
        assertEquals("/fast", stats.get(1).path());
    }

    @Test
    void computesMaxLatency() {
        // Max must be the single largest latency in the window, regardless of
        // the order samples arrived in.
        RingBuffer<EndpointSample> buffer = new RingBuffer<>(100);
        buffer.write(sample("GET", "/api", 10));
        buffer.write(sample("GET", "/api", 500));
        buffer.write(sample("GET", "/api", 50));

        EndpointAggregator agg = new EndpointAggregator(buffer);
        List<EndpointStats> stats = agg.aggregate();

        assertEquals(500L, stats.get(0).maxLatencyMs());
    }

    // Helper method — builds a minimal EndpointSample for testing.
    // Heap-before/after are zeroed (irrelevant to latency assertions) and the
    // timestamp is "now" so samples look freshly captured.
    private EndpointSample sample(String method, String path, long latencyMs) {
        return new EndpointSample(method, path, latencyMs, 0L, 0L,
            System.currentTimeMillis());
    }
}
