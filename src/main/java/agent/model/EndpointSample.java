package agent.model;

/**
 * A single HTTP request measurement captured by the DispatcherServlet advice.
 *
 * This is intentionally minimal — only raw numbers, no computed statistics.
 * Statistics (avg, p95, etc.) are computed by EndpointAggregator on the
 * aggregation daemon thread, not here on the request thread.
 */
public record EndpointSample(
    /** HTTP method — GET, POST, PUT, DELETE, etc. */
    String method,

    /** Matched Spring route pattern when available, otherwise raw request URI. */
    String path,

    /** How long the request took in milliseconds */
    long latencyMs,

    /** Heap bytes used BEFORE the request (captured at entry) */
    long heapBeforeBytes,

    /** Heap bytes used AFTER the request (captured at exit) */
    long heapAfterBytes,

    /** When the request completed — milliseconds since epoch */
    long timestampMs
) {
    /** Convenience: heap change during this request (positive = allocated, negative = freed by GC) */
    public long heapDeltaBytes() {
        return heapAfterBytes - heapBeforeBytes;
    }
}
