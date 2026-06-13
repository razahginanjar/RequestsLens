package agent.profiling;

import agent.buffer.RingBuffer;
import agent.model.MethodSpan;
import agent.model.RequestTrace;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Bridges the request boundary (DispatcherServlet advice) to the per-request
 * method call tree (Phase 6).
 *
 * <p>The DispatcherServlet advice calls {@link #requestEnter()} on entry and, if
 * it returned true, {@link #requestExit(String, String)} on exit. All state and
 * logic live here (a normal agent class) so the advice stays a thin set of calls —
 * never touching agent fields from inlined code.
 *
 * <p>Configuration fields are public + volatile and set once by AgentMain after
 * config load; they are read on the request thread.
 */
public final class TraceSupport {

    // ── Configuration (set by AgentMain at startup) ───────────────────────
    public static volatile boolean enabled    = false;
    public static volatile int     sampleRate = 50;   // fully trace 1 of N requests
    public static volatile int     maxDepth   = 40;
    public static volatile int     maxSpans   = 5000;

    /** Where finished traces are published (set by AgentMain). */
    public static volatile RingBuffer<RequestTrace> traceBuffer;

    private static final AtomicLong COUNTER = new AtomicLong();

    private TraceSupport() {}

    /**
     * Decides whether to trace the current request and, if so, begins a context.
     * @return true if tracing started (the caller MUST call requestExit on exit)
     */
    public static boolean requestEnter() {
        if (!enabled || traceBuffer == null) return false;
        if (RequestProfilingContext.isTracing()) return false;          // no nested begin
        if (COUNTER.incrementAndGet() % sampleRate != 0) return false;  // sample 1 of N

        MethodSpan root = new MethodSpan();
        root.className  = "HTTP";
        root.methodName = "request";
        RequestProfilingContext.begin(root, maxDepth, maxSpans);
        // Stash start counters on the root via a parallel slot in the context is
        // overkill; capture them here and recompute the delta in requestExit using
        // a thread-local-free approach: store starts on the root's allocByType? No —
        // keep it simple: record starts in the three locals below via a holder.
        ROOT_START.set(new long[]{
            System.nanoTime(), ThreadMetrics.cpuNs(), ThreadMetrics.allocBytes()
        });
        return true;
    }

    private static final ThreadLocal<long[]> ROOT_START = new ThreadLocal<>();

    /**
     * Finalizes the current request trace and publishes it.
     * @param httpMethod HTTP method (e.g. GET)
     * @param path       request path
     */
    public static void requestExit(String httpMethod, String path) {
        RequestProfilingContext.CompletedTrace completed = RequestProfilingContext.finish();
        long[] start = ROOT_START.get();
        ROOT_START.remove();
        if (completed == null || completed.root() == null || start == null) return;

        MethodSpan root = completed.root();
        root.wallNs     = System.nanoTime()          - start[0];
        root.cpuNs      = ThreadMetrics.cpuNs()        - start[1];
        root.allocBytes = ThreadMetrics.allocBytes()   - start[2];
        root.className  = "HTTP";
        root.methodName = httpMethod + " " + path;

        computeSelfTimes(root);

        String id = Long.toHexString(System.nanoTime() ^ (COUNTER.get() << 16));
        RingBuffer<RequestTrace> buf = traceBuffer;
        if (buf != null) {
            buf.write(new RequestTrace(
                id, httpMethod, path, System.currentTimeMillis(),
                root.wallNs, root.cpuNs, root.allocBytes,
                completed.capturedSpans(),
                completed.droppedSpans(),
                completed.truncated(),
                completed.depthLimitExceeded(),
                completed.spanLimitExceeded(),
                root));
        }
    }

    /** Sets self* = total − sum(children totals) for every node, recursively. */
    private static void computeSelfTimes(MethodSpan node) {
        long childWall = 0, childCpu = 0, childAlloc = 0;
        for (MethodSpan c : node.children) {
            computeSelfTimes(c);
            childWall  += c.wallNs;
            childCpu   += c.cpuNs;
            childAlloc += c.allocBytes;
        }
        node.selfWallNs     = Math.max(0, node.wallNs     - childWall);
        node.selfCpuNs      = Math.max(0, node.cpuNs      - childCpu);
        node.selfAllocBytes = Math.max(0, node.allocBytes - childAlloc);
    }
}
