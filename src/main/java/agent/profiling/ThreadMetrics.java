package agent.profiling;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/**
 * Per-thread CPU time and allocated-bytes counters, used to attribute CPU and
 * memory to individual method spans (Phase 6).
 *
 * <h2>Graceful degradation</h2>
 * Thread CPU time and (HotSpot-only) thread allocation counters are optional JVM
 * features. We probe support once at {@link #init()} and return 0 when a counter
 * is unsupported, so the rest of the agent works regardless.
 *
 * <h2>Cost</h2>
 * {@code getCurrentThreadCpuTime()} and {@code getThreadAllocatedBytes()} are cheap
 * native reads — safe to call on the request thread (Tier 1) around method enter/exit.
 */
public final class ThreadMetrics {

    private static final ThreadMXBean TMX = ManagementFactory.getThreadMXBean();

    /** The HotSpot extension that exposes per-thread allocation, if available. */
    private static final com.sun.management.ThreadMXBean SUN =
        (TMX instanceof com.sun.management.ThreadMXBean s) ? s : null;

    private static volatile boolean cpuOk;
    private static volatile boolean allocOk;

    private ThreadMetrics() {}

    /** Probe + enable the counters. Call once at agent startup. */
    public static void init() {
        try {
            if (TMX.isThreadCpuTimeSupported()) {
                TMX.setThreadCpuTimeEnabled(true);
                cpuOk = true;
            }
        } catch (Throwable ignore) { cpuOk = false; }
        try {
            if (SUN != null && SUN.isThreadAllocatedMemorySupported()) {
                SUN.setThreadAllocatedMemoryEnabled(true);
                allocOk = true;
            }
        } catch (Throwable ignore) { allocOk = false; }
    }

    /** Current thread's CPU time in nanoseconds, or 0 if unsupported. */
    public static long cpuNs() {
        if (!cpuOk) return 0L;
        try { return TMX.getCurrentThreadCpuTime(); }
        catch (Throwable t) { return 0L; }
    }

    /** Current thread's cumulative allocated bytes, or 0 if unsupported. */
    public static long allocBytes() {
        if (!allocOk) return 0L;
        try { return SUN.getThreadAllocatedBytes(Thread.currentThread().getId()); }
        catch (Throwable t) { return 0L; }
    }

    public static boolean cpuSupported()   { return cpuOk; }
    public static boolean allocSupported() { return allocOk; }
}
