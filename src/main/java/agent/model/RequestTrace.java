package agent.model;

/**
 * A completed, request-scoped method call tree (Phase 6).
 *
 * <p>Published to a ring buffer when the request finishes; read by the HTTP API
 * (GET /profiler/traces and /profiler/trace/{id}).
 */
public record RequestTrace(
    String     traceId,        // short unique id (hex)
    String     method,         // HTTP method
    String     path,           // request path
    long       timestampMs,
    long       totalWallNs,
    long       totalCpuNs,
    long       totalAllocBytes,
    int        capturedSpans,
    int        droppedSpans,
    boolean    truncated,
    boolean    depthLimitExceeded,
    boolean    spanLimitExceeded,
    MethodSpan root            // synthetic root = the request entry
) {
    public long totalWallMs() { return totalWallNs / 1_000_000L; }
}
