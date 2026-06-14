package agent.model;

/**
 * Aggregated performance statistics for a single HTTP endpoint.
 *
 * Built by EndpointAggregator from a batch of EndpointSamples.
 * Exposed via GET /profiler/endpoints.
 */
public record EndpointStats(
    String method,
    String path,
    long   requestCount,
    double avgLatencyMs,
    double p95LatencyMs,
    long   maxLatencyMs,
    long   avgHeapDeltaBytes,
    double avgCpuMs,
    double maxCpuMs,
    double avgCpuToWallPercent,

    /**
     * Requests per second — computed over the most recent aggregation window.
     * Used by the AdaptiveSamplingController in Phase 4.
     */
    double currentRps
) {}
