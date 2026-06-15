package agent.model;

import java.util.List;

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
    MethodSpan root,           // synthetic root = the request entry
    List<LineHotspot> lineHotspots,
    int        lineSampleCount,
    int        droppedLineSamples,
    int        droppedLineHotspots,
    boolean    lineHotspotsTruncated,
    long       lineSampleIntervalMs
) {
    public RequestTrace(
        String traceId,
        String method,
        String path,
        long timestampMs,
        long totalWallNs,
        long totalCpuNs,
        long totalAllocBytes,
        int capturedSpans,
        int droppedSpans,
        boolean truncated,
        boolean depthLimitExceeded,
        boolean spanLimitExceeded,
        MethodSpan root
    ) {
        this(traceId, method, path, timestampMs, totalWallNs, totalCpuNs,
            totalAllocBytes, capturedSpans, droppedSpans, truncated,
            depthLimitExceeded, spanLimitExceeded, root, List.of(),
            0, 0, 0, false, 0L);
    }

    public RequestTrace {
        lineHotspots = lineHotspots == null ? List.of() : List.copyOf(lineHotspots);
    }

    public long totalWallMs() { return totalWallNs / 1_000_000L; }

    public RequestTrace withLineProfile(
            List<LineHotspot> hotspots,
            int sampleCount,
            int droppedSamples,
            int droppedHotspots,
            boolean hotspotsTruncated,
            long sampleIntervalMs) {
        return new RequestTrace(traceId, method, path, timestampMs, totalWallNs,
            totalCpuNs, totalAllocBytes, capturedSpans, droppedSpans, truncated,
            depthLimitExceeded, spanLimitExceeded, root, hotspots, sampleCount,
            droppedSamples, droppedHotspots, hotspotsTruncated, sampleIntervalMs);
    }
}
