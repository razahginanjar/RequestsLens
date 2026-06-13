package agent.model;

/**
 * A detected memory-leak pattern — heap growing without GC relief.
 *
 * <p>Produced by {@code LeakDetector}, exposed via GET /profiler/leaks and
 * used to fire webhook alerts.
 */
public record LeakWarning(
    /** When the leak pattern was detected — epoch milliseconds. */
    long   detectedAtMs,

    /** Heap growth over the analysis window, in bytes. */
    long   heapGrowthBytes,

    /** The analysis window length, in milliseconds. */
    long   windowMs,

    /** Growth as a percentage of the window's starting heap usage. */
    double growthPercent,

    /** "WARN" (>10% growth) or "CRITICAL" (>25% growth). */
    String severity
) {}
