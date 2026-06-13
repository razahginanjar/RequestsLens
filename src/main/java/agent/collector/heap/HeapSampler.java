package agent.collector.heap;

import agent.buffer.RingBuffer;
import agent.core.AgentConfig;
import agent.core.AgentSelfMetrics;
import agent.core.CollectorRegistry;
import agent.model.HeapSnapshot;
import agent.sampling.SamplingStateHolder;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Periodically samples JVM heap usage via JMX and writes snapshots
 * to the ring buffer.
 *
 * <h2>JMX — Java Management Extensions</h2>
 * JMX is a built-in JDK API for monitoring JVM internals.
 * MemoryMXBean gives us total heap usage.
 * MemoryPoolMXBeans give us per-generation usage (Eden, Old Gen, etc.).
 * Both are available with zero dependencies — they are part of the JDK.
 *
 * <h2>Adaptive interval (Phase 4)</h2>
 * Instead of a fixed-rate schedule, the sampler runs a self-rescheduling loop:
 * it samples, then sleeps for the CURRENT effective interval read from
 * {@link SamplingStateHolder}. When the AdaptiveSamplingController throttles
 * under high load, the next sleep automatically lengthens — no rescheduling
 * machinery required.
 *
 * <h2>Daemon thread</h2>
 * The sampler runs on a daemon thread so it never prevents the target
 * application's JVM from shutting down. Always use daemon threads in agent code.
 */
public final class HeapSampler {

    private static final Logger log = Logger.getLogger(HeapSampler.class.getName());

    /** Delay before the first sample — lets the target app finish starting. */
    private static final long INITIAL_DELAY_MS = 1000L;

    private final RingBuffer<HeapSnapshot> buffer;
    private final AgentSelfMetrics         selfMetrics;
    private final AgentConfig              config;

    /**
     * The shared registry. We use it to publish the latest snapshot to the
     * volatile cache that the live /profiler/heap route reads (the heap ring
     * buffer is drained for persistence in Phase 3, so the cache is the
     * reliable source for the "current" value).
     */
    private final CollectorRegistry        registry;

    /** Source of the current effective sampling interval (Phase 4 adaptive). */
    private final SamplingStateHolder      samplingState;

    // JMX beans — read-only, thread-safe, obtained once at construction
    private final MemoryMXBean         memoryBean;
    private final List<MemoryPoolMXBean> poolBeans;

    // Delay detection — tracks when we expected the next tick
    private volatile long expectedNextTickMs = 0L;

    public HeapSampler(CollectorRegistry registry, AgentConfig config) {
        this.registry      = registry;
        this.buffer        = registry.heapBuffer();
        this.selfMetrics   = registry.selfMetrics();
        this.samplingState = registry.getSamplingStateHolder();
        this.config        = config;
        // Obtain JMX beans once at construction — safe and efficient
        this.memoryBean  = ManagementFactory.getMemoryMXBean();
        this.poolBeans   = ManagementFactory.getMemoryPoolMXBeans();
    }

    /**
     * Starts the sampling daemon. Returns immediately.
     * The actual sampling happens on a background thread.
     */
    public void start() {
        Thread samplerThread = new Thread(() -> {
            log.info("HeapSampler started — base interval="
                + config.getBaseIntervalMs() + "ms (adaptive)");

            // Give the target application time to start before the first sample.
            try {
                Thread.sleep(INITIAL_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            while (!Thread.currentThread().isInterrupted()) {
                try {
                    sample();

                    // Read the CURRENT effective interval each iteration — it may
                    // have changed since the last sample if the adaptive
                    // controller throttled or recovered.
                    long intervalMs = samplingState.getEffectiveIntervalMs();
                    Thread.sleep(intervalMs);

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.info("HeapSampler interrupted — stopping");
                    break;
                } catch (Exception e) {
                    // Never let an unexpected error kill the sampler thread.
                    log.warning("HeapSampler error: " + e.getMessage());
                }
            }
        }, "profiler-heap-sampler");

        samplerThread.setDaemon(true);                 // MUST be daemon
        samplerThread.setPriority(Thread.MIN_PRIORITY); // don't steal from the app
        samplerThread.start();
    }

    /**
     * Takes a single heap snapshot and writes it to the ring buffer.
     *
     * This method runs on the sampler daemon thread.
     * It is Tier 1 — no logging, no allocation, no blocking.
     */
    void sample() {
        long nowMs = System.currentTimeMillis();

        // ── Delay detection ───────────────────────────────────────────────
        // Compare against the EFFECTIVE interval (not the base), so throttled
        // sampling doesn't get falsely counted as a delay.
        long effectiveInterval = samplingState.getEffectiveIntervalMs();
        if (expectedNextTickMs > 0) {
            long delay        = nowMs - expectedNextTickMs;
            long halfInterval = effectiveInterval / 2;
            if (delay > halfInterval) {
                // We were paused (GC or CPU pressure) — count it
                selfMetrics.incrementSamplingDelays();
                // No logging — this is Tier 1
            }
        }
        expectedNextTickMs = nowMs + effectiveInterval;

        // ── Read heap totals from JMX ─────────────────────────────────────
        var heapUsage = memoryBean.getHeapMemoryUsage();

        // ── Read per-pool breakdown ───────────────────────────────────────
        // Build a map of pool-name → bytes-used
        // We build this map here on the sampler thread so the snapshot is
        // complete and immutable when it reaches the consumer.
        Map<String, Long> poolUsage = new HashMap<>();
        for (MemoryPoolMXBean pool : poolBeans) {
            long used = pool.getUsage().getUsed();
            if (used >= 0) {  // -1 means "not supported" for this pool
                poolUsage.put(pool.getName(), used);
            }
        }

        // ── Build snapshot ────────────────────────────────────────────────
        HeapSnapshot snapshot = new HeapSnapshot(
            nowMs,
            heapUsage.getUsed(),
            heapUsage.getCommitted(),
            heapUsage.getMax(),
            Map.copyOf(poolUsage)   // immutable copy — safe to share across threads
        );

        // ── Write to ring buffer ──────────────────────────────────────────
        boolean written = buffer.write(snapshot);
        if (!written) {
            selfMetrics.incrementDroppedSamples();
            // No logging here — Tier 1
        }

        // ── Publish to the latest-snapshot cache ──────────────────────────
        // The live /profiler/heap route reads this for its "current" value.
        // It must be updated every tick because the ring buffer gets drained
        // for persistence (Phase 3) and may be empty when the route is hit.
        registry.setLatestHeapSnapshot(snapshot);

        // ── Update last-sample timestamp ──────────────────────────────────
        selfMetrics.setLastSampleTs(nowMs);
    }
}