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

    public static final int ENTER_NONE = 0;
    public static final int ENTER_SPAN = 1;
    public static final int ENTER_SUPPRESSED = 2;

    /** Active context per request thread; null when the current request is not traced. */
    private static final ThreadLocal<RequestProfilingContext> CURRENT = new ThreadLocal<>();

    private final Deque<MethodSpan> stack      = new ArrayDeque<>();
    private final Deque<long[]>     startStack = new ArrayDeque<>();  // [wallNs, cpuNs, allocBytes]
    private final Deque<LineState>  lineStack  = new ArrayDeque<>();
    private final String traceId;
    private final int maxDepth;
    private final int maxSpans;
    private int spanCount;
    private int suppressedDepth;
    private int droppedSpans;
    private boolean depthLimitExceeded;
    private boolean spanLimitExceeded;

    public record CompletedTrace(
        MethodSpan root,
        int capturedSpans,
        int droppedSpans,
        boolean depthLimitExceeded,
        boolean spanLimitExceeded
    ) {
        public boolean truncated() {
            return droppedSpans > 0 || depthLimitExceeded || spanLimitExceeded;
        }
    }

    private RequestProfilingContext(String traceId, MethodSpan root, int maxDepth, int maxSpans) {
        this.traceId = traceId;
        this.maxDepth = maxDepth;
        this.maxSpans = maxSpans;
        stack.push(root);
        lineStack.push(new LineState());
    }

    private static final class LineState {
        int lineNumber = -1;
        long wallStartNs;
        long cpuStartNs;
        long childWallNs;
        long childCpuNs;
    }

    // ── Request lifecycle (called by TraceSupport) ────────────────────────

    public static void begin(MethodSpan root, int maxDepth, int maxSpans) {
        begin(null, root, maxDepth, maxSpans);
    }

    public static void begin(String traceId, MethodSpan root, int maxDepth, int maxSpans) {
        CURRENT.set(new RequestProfilingContext(traceId, root, maxDepth, maxSpans));
    }

    /** Ends tracing for the current thread and returns the root span (or null). */
    public static MethodSpan end() {
        CompletedTrace completed = finish();
        return completed == null ? null : completed.root();
    }

    /** Ends tracing and returns the root span plus trace quality metadata. */
    public static CompletedTrace finish() {
        RequestProfilingContext c = CURRENT.get();
        CURRENT.remove();
        if (c == null) return null;
        return new CompletedTrace(
            c.stack.peekLast(),
            c.spanCount,
            c.droppedSpans,
            c.depthLimitExceeded,
            c.spanLimitExceeded);
    }

    public static boolean isTracing() { return CURRENT.get() != null; }

    public static String currentTraceId() {
        RequestProfilingContext c = CURRENT.get();
        return c == null || c.suppressedDepth > 0 ? null : c.traceId;
    }

    /**
     * The method span currently executing on THIS thread (top of the stack), or
     * null if the current request is not being traced. Used by AllocationRecorder
     * to attribute an allocation to the function that made it. Cheap: one
     * ThreadLocal read + a stack peek (no shared map).
     */
    public static MethodSpan currentTopSpan() {
        RequestProfilingContext c = CURRENT.get();
        return c == null || c.suppressedDepth > 0 ? null : c.stack.peek();
    }

    // ── Per-method enter/exit (called by MethodTraceAdvice) ───────────────

    /**
     * Pushes a span for an entered method.
     * @return true if a span was pushed (caller must call {@link #methodExit()} on
     *         exit), false if not tracing or a depth/span cap was hit.
     */
    public static boolean methodEnter(String className, String methodName) {
        return methodEnterState(className, methodName) == ENTER_SPAN;
    }

    /**
     * Pushes a span or marks an untracked subtree when caps are exceeded.
     * @return one of ENTER_NONE, ENTER_SPAN, ENTER_SUPPRESSED
     */
    public static int methodEnterState(String className, String methodName) {
        RequestProfilingContext c = CURRENT.get();
        if (c == null) return ENTER_NONE;
        if (c.suppressedDepth > 0) {
            c.suppressedDepth++;
            c.droppedSpans++;
            return ENTER_SUPPRESSED;
        }
        if (c.stack.size() > c.maxDepth) {
            c.depthLimitExceeded = true;
            c.suppressedDepth = 1;
            c.droppedSpans++;
            return ENTER_SUPPRESSED;
        }
        if (c.spanCount >= c.maxSpans) {
            c.spanLimitExceeded = true;
            c.suppressedDepth = 1;
            c.droppedSpans++;
            return ENTER_SUPPRESSED;
        }

        MethodSpan span = new MethodSpan();
        span.className  = className;
        span.methodName = methodName;
        c.pushSpan(span);
        return ENTER_SPAN;
    }

    /**
     * Pushes an external dependency span under the active traced method.
     * External spans share the same caps as method spans.
     */
    public static int externalEnter(String kind, String className,
                                    String methodName, String resource) {
        return externalEnter(kind, className, methodName, methodName, resource);
    }

    /**
     * Pushes an external dependency span with separate display and operation labels.
     */
    public static int externalEnter(String kind, String className,
                                    String methodName, String operation,
                                    String resource) {
        RequestProfilingContext c = CURRENT.get();
        if (c == null) return ENTER_NONE;
        int state = c.canEnterSpan();
        if (state != ENTER_SPAN) return state;

        MethodSpan span = new MethodSpan();
        span.className = safeLabel(className, "External");
        span.methodName = safeLabel(methodName, "call");
        span.spanKind = safeLabel(kind, "external");
        span.externalOperation = safeLabel(operation, "");
        span.externalResource = safeLabel(resource, "");
        c.pushSpan(span);
        return ENTER_SPAN;
    }

    /** Pops the current method span and records its wall/cpu/alloc deltas. */
    public static void methodExit() {
        methodExit(ENTER_SPAN);
    }

    /** Completes the enter state returned by {@link #methodEnterState(String, String)}. */
    public static void methodExit(int enterState) {
        RequestProfilingContext c = CURRENT.get();
        if (c == null || enterState == ENTER_NONE) return;

        if (enterState == ENTER_SUPPRESSED) {
            if (c.suppressedDepth > 0) c.suppressedDepth--;
            return;
        }

        if (c.stack.size() <= 1) return;   // never pop the root here

        c.finishCurrentLine();
        MethodSpan span = c.stack.pop();
        c.lineStack.pop();
        long[] start    = c.startStack.pop();
        span.wallNs     = System.nanoTime()      - start[0];
        span.cpuNs      = ThreadMetrics.cpuNs()   - start[1];
        span.allocBytes = ThreadMetrics.allocBytes() - start[2];
        c.recordChildTimeOnActiveLine(span);
    }

    /**
     * Records deterministic source-line execution for the active method span.
     * Called from injected line probes when profiler.line.mode=deterministic.
     */
    public static void lineEnter(String className, String methodName,
                                 String fileName, int lineNumber) {
        if (lineNumber <= 0) return;
        try {
            RequestProfilingContext c = CURRENT.get();
            if (c == null || c.suppressedDepth > 0 || c.stack.size() <= 1) return;

            MethodSpan span = c.stack.peek();
            if (span == null
                    || !span.className.equals(className)
                    || !span.methodName.equals(methodName)) {
                return;
            }

            c.finishCurrentLine();
            LineState state = c.lineStack.peek();
            if (state == null) return;
            span.recordLineHit(fileName, lineNumber);
            state.lineNumber = lineNumber;
            state.wallStartNs = System.nanoTime();
            state.cpuStartNs = ThreadMetrics.cpuNs();
        } catch (Throwable ignored) {
            // Deterministic probes run inside application bytecode.
        }
    }

    /** Records one allocation against the active method's deterministic line stats. */
    public static void recordLineAllocation(String className, String methodName,
                                            String fileName, int lineNumber,
                                            String type, long bytes) {
        if (lineNumber <= 0 || type == null) return;
        try {
            RequestProfilingContext c = CURRENT.get();
            if (c == null || c.suppressedDepth > 0 || c.stack.size() <= 1) return;
            MethodSpan span = c.stack.peek();
            if (span == null
                    || !span.className.equals(className)
                    || !span.methodName.equals(methodName)) {
                return;
            }
            span.recordLineAlloc(fileName, lineNumber, type, bytes);
        } catch (Throwable ignored) {
            // Allocation probes must never break the application.
        }
    }

    private void finishCurrentLine() {
        LineState state = lineStack.peek();
        MethodSpan span = stack.peek();
        if (state == null || span == null || state.lineNumber <= 0) return;
        long wallNs = System.nanoTime() - state.wallStartNs;
        long cpuNs = ThreadMetrics.cpuNs() - state.cpuStartNs;
        span.recordLineTime(state.lineNumber, wallNs, cpuNs,
            state.childWallNs, state.childCpuNs);
        state.lineNumber = -1;
        state.wallStartNs = 0L;
        state.cpuStartNs = 0L;
        state.childWallNs = 0L;
        state.childCpuNs = 0L;
    }

    private void recordChildTimeOnActiveLine(MethodSpan child) {
        LineState parentLine = lineStack.peek();
        if (parentLine == null || parentLine.lineNumber <= 0 || child == null) return;
        parentLine.childWallNs += Math.max(0L, child.wallNs);
        parentLine.childCpuNs += Math.max(0L, child.cpuNs);
    }

    private int canEnterSpan() {
        if (suppressedDepth > 0) {
            suppressedDepth++;
            droppedSpans++;
            return ENTER_SUPPRESSED;
        }
        if (stack.size() > maxDepth) {
            depthLimitExceeded = true;
            suppressedDepth = 1;
            droppedSpans++;
            return ENTER_SUPPRESSED;
        }
        if (spanCount >= maxSpans) {
            spanLimitExceeded = true;
            suppressedDepth = 1;
            droppedSpans++;
            return ENTER_SUPPRESSED;
        }
        return ENTER_SPAN;
    }

    private void pushSpan(MethodSpan span) {
        stack.peek().children.add(span);
        stack.push(span);
        lineStack.push(new LineState());
        startStack.push(new long[]{
            System.nanoTime(), ThreadMetrics.cpuNs(), ThreadMetrics.allocBytes()
        });
        spanCount++;
    }

    private static String safeLabel(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
