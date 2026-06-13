package agent.collector.spring;

import agent.buffer.RingBuffer;
import agent.model.EndpointSample;
import agent.model.EndpointStats;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Drains the endpoint ring buffer and computes per-endpoint statistics.
 *
 * Runs on the aggregation daemon thread every few seconds.
 * Never runs on the request thread.
 *
 * <h2>P95 calculation</h2>
 * P95 (95th percentile) means: 95% of requests completed in this many
 * milliseconds or less. It is more useful than average latency because
 * it shows the worst experience most users get, without being skewed
 * by rare extreme outliers.
 *
 * To compute p95: sort all latencies, take the value at index (n * 0.95).
 */
public final class EndpointAggregator {

    private final RingBuffer<EndpointSample> buffer;

    // Keeps a rolling window of all samples seen — used for p95 calculation
    // Key: "METHOD /path", Value: list of latencies in the window
    private final Map<String, List<Long>> latencyWindows = new LinkedHashMap<>();

    // How many samples to keep per endpoint for statistical accuracy
    private static final int WINDOW_SIZE = 200;

    public EndpointAggregator(RingBuffer<EndpointSample> buffer) {
        this.buffer = buffer;
    }

    /**
     * Drains new samples from the buffer and computes updated stats.
     * Returns the top 20 endpoints sorted by average latency descending.
     *
     * Call this from the aggregation daemon thread.
     */
    public List<EndpointStats> aggregate() {
        // Drain new samples into a local list
        List<EndpointSample> newSamples = new ArrayList<>();
        buffer.drainTo(newSamples);

        if (newSamples.isEmpty() && latencyWindows.isEmpty()) {
            return List.of();
        }

        // Group new samples by endpoint key
        Map<String, List<EndpointSample>> grouped = newSamples.stream()
            .collect(Collectors.groupingBy(s -> s.method() + " " + s.path()));

        // Merge into rolling windows
        for (Map.Entry<String, List<EndpointSample>> entry : grouped.entrySet()) {
            String key = entry.getKey();
            latencyWindows.computeIfAbsent(key, k -> new ArrayList<>());

            List<Long> window = latencyWindows.get(key);
            for (EndpointSample sample : entry.getValue()) {
                window.add(sample.latencyMs());
                // Keep window at WINDOW_SIZE — remove oldest if over limit
                if (window.size() > WINDOW_SIZE) {
                    window.remove(0);
                }
            }
        }

        // Compute stats per endpoint
        long windowMs = 5000L; // Aggregation window for RPS calculation
        List<EndpointStats> statsList = new ArrayList<>();

        for (Map.Entry<String, List<Long>> entry : latencyWindows.entrySet()) {
            String key = entry.getKey();
            List<Long> latencies = entry.getValue();
            if (latencies.isEmpty()) continue;

            String[] parts  = key.split(" ", 2);
            String method   = parts[0];
            String path     = parts.length > 1 ? parts[1] : "unknown";

            // Count samples for this endpoint from the new batch
            long newCount = grouped.getOrDefault(key, List.of()).size();

            // Compute statistics
            List<Long> sorted   = latencies.stream().sorted().toList();
            double avgLatency   = latencies.stream().mapToLong(l -> l).average().orElse(0);
            long   maxLatency   = latencies.stream().mapToLong(l -> l).max().orElse(0);
            double p95Latency   = percentile(sorted, 95);

            // Average heap delta for new samples in this window
//            long avgHeapDelta   = grouped.getOrDefault(key, List.of()).stream()
//                .mapToLong(EndpointSample::heapDeltaBytes)
//                .average().stream()
//                .map(Math::round)
//                .orElse(0L);

            long avgHeapDelta = Math.round(
                    grouped.getOrDefault(key, List.of()).stream()
                            .mapToLong(EndpointSample::heapDeltaBytes)
                            .average()
                            .orElse(0.0)
            );

            // RPS = new requests in this window / window duration in seconds
            double rps = newCount / (windowMs / 1000.0);

            statsList.add(new EndpointStats(
                method, path,
                latencies.size(),
                Math.round(avgLatency * 100.0) / 100.0,
                Math.round(p95Latency * 100.0) / 100.0,
                maxLatency,
                avgHeapDelta,
                Math.round(rps * 100.0) / 100.0
            ));
        }

        // Sort by average latency descending — slowest endpoints first
        statsList.sort(Comparator.comparingDouble(EndpointStats::avgLatencyMs).reversed());

        return statsList.stream().limit(20).toList();
    }

    /**
     * Computes the Nth percentile of a sorted list of longs.
     *
     * @param sorted the list sorted ascending
     * @param n      the percentile (0–100)
     * @return the value at that percentile
     */
    private double percentile(List<Long> sorted, int n) {
        if (sorted.isEmpty()) return 0.0;
        int index = (int) Math.ceil((n / 100.0) * sorted.size()) - 1;
        return sorted.get(Math.max(0, index));
    }
}