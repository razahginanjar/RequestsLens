package agent.model;

/**
 * An immutable record of a single garbage collection event.
 *
 * The JVM fires a notification after every GC cycle. We capture the
 * key fields from that notification and store them here.
 */
public record GcEvent(
    /** When the GC completed — milliseconds since epoch */
    long timestampMs,

    /**
     * Name of the GC collector, e.g. "G1 Young Generation",
     * "PS MarkSweep", "ZGC Cycles".
     * Useful for identifying which collector is running.
     */
    String gcName,

    /**
     * Why the GC was triggered, e.g. "G1 Evacuation Pause",
     * "Allocation Failure", "System.gc()".
     */
    String gcCause,

    /** How long this GC pause lasted in milliseconds */
    long durationMs,

    /** Heap bytes in use BEFORE this GC ran */
    long heapBeforeBytes,

    /** Heap bytes in use AFTER this GC ran — should be less than heapBeforeBytes */
    long heapAfterBytes
) {}