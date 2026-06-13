package agent.model;

import java.util.Map;

/**
 * An immutable point-in-time snapshot of JVM heap memory.
 *
 * This is a Java record — it automatically generates a constructor,
 * getters, equals(), hashCode(), and toString(). No boilerplate needed.
 *
 * Why immutable? Because this object is written on the sampler thread
 * and read on the HTTP thread. Immutable objects are always thread-safe —
 * no synchronization needed.
 */
public record HeapSnapshot(
    /** When this snapshot was taken — milliseconds since epoch (System.currentTimeMillis()) */
    long timestampMs,

    /** Bytes currently in use on the heap */
    long usedBytes,

    /** Bytes committed (reserved by the JVM) — always >= usedBytes */
    long committedBytes,

    /** Maximum heap size — set by -Xmx flag. -1 if no limit. */
    long maxBytes,

    /**
     * Heap usage broken down by memory pool.
     * Keys are pool names like "PS Eden Space", "G1 Old Gen", "Metaspace".
     * Values are bytes used in that pool.
     *
     * Note: Map.copyOf() makes this map unmodifiable — consistent with
     * the immutability of the record.
     */
    Map<String, Long> poolUsage
) {}