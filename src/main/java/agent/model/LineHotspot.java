package agent.model;

/**
 * Aggregated request-scoped source-line hotspot.
 *
 * <p>Line hotspots are statistical: each sample records the target application
 * line visible on the request thread stack at that instant. Wall and CPU values
 * are estimates derived from sample counts and sampler interval. Allocation
 * values are exact shallow allocation-site counts when line allocation detail is
 * enabled, not retained memory.
 */
public record LineHotspot(
    String className,
    String methodName,
    String fileName,
    int lineNumber,
    long samples,
    long cpuSamples,
    long estimatedWallNs,
    long estimatedCpuNs,
    long allocationCount,
    long allocatedBytes
) {
}
