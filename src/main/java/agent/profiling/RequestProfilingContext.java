package agent.profiling;

import agent.model.MethodSpan;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Per-thread state for an in-flight traced request (Phase 6).
 *
 * <p>All access to a given context is single-threaded (the request thread), so no
 * synchronization is needed inside an instance. The Byte Buddy method advice only
 * ever CALLS the static methods here (never inlines field access), which keeps it
 * clear of the cross-classloader access rules that bit earlier phases.
 *
 * <p>The synthetic root span is created by {@link TraceSupport} when a request is
 * selected for tracing; per-method spans are pushed/popped by the method advice.
 */
public final class RequestProfilingContext {

    /** Active context per request thread; null when the current request is not traced. */
    private static final ThreadLocal<RequestProfilingContext> CURRENT = new ThreadLocal<>();

    private final Deque<MethodSpan> stack      = new ArrayDeque<>();
    private final Deque<long[]>     startStack = new ArrayDeque<>();  // [wallNs, cpuNs, allocBytes]
    private final int maxDepth;
    private final int maxSpans;
    private int spanCount;

    private RequestProfilingContext(MethodSpan root, int maxDepth, int maxSpans) {
        this.maxDepth = maxDepth;
        this.maxSpans = maxSpans;
        stack.push(root);
    }

    // ── Request lifecycle (called by TraceSupport) ────────────────────────

    public static void begin(MethodSpan root, int maxDepth, int maxSpans) {
        CURRENT.set(new RequestProfilingContext(root, maxDepth, maxSpans));
    }

    /** Ends tracing for the current thread and returns the root span (or null). */
    public static MethodSpan end() {
        RequestProfilingContext c = CURRENT.get();
        CURRENT.remove();
        return c == null ? null : c.stack.peekLast();   // root is the bottom of the stack
    }

    public static boolean isTracing() { return CURRENT.get() != null; }

    /**
     * The method span currently executing on THIS thread (top of the stack), or
     * null if the current request is not being traced. Used by AllocationRecorder
     * to attribute an allocation to the function that made it. Cheap: one
     * ThreadLocal read + a stack peek (no shared map).
     */
    public static MethodSpan currentTopSpan() {
        RequestProfilingContext c = CURRENT.get();
        return c == null ? null : c.stack.peek();
    }

    // ── Per-method enter/exit (called by MethodTraceAdvice) ───────────────

    /**
     * Pushes a span for an entered method.
     * @return true if a span was pushed (caller must call {@link #methodExit()} on
     *         exit), false if not tracing or a depth/span cap was hit.
     */
    public static boolean methodEnter(String className, String methodName) {
        RequestProfilingContext c = CURRENT.get();
        if (c == null) return false;
        if (c.stack.size() > c.maxDepth || c.spanCount >= c.maxSpans) return false;

        MethodSpan span = new MethodSpan();
        span.className  = className;
        span.methodName = methodName;
        c.stack.peek().children.add(span);
        c.stack.push(span);
        c.startStack.push(new long[]{
            System.nanoTime(), ThreadMetrics.cpuNs(), ThreadMetrics.allocBytes()
        });
        c.spanCount++;
        return true;
    }

    /** Pops the current method span and records its wall/cpu/alloc deltas. */
    public static void methodExit() {
        RequestProfilingContext c = CURRENT.get();
        if (c == null || c.stack.size() <= 1) return;   // never pop the root here

        MethodSpan span = c.stack.pop();
        long[] start    = c.startStack.pop();
        span.wallNs     = System.nanoTime()      - start[0];
        span.cpuNs      = ThreadMetrics.cpuNs()   - start[1];
        span.allocBytes = ThreadMetrics.allocBytes() - start[2];
    }
}
