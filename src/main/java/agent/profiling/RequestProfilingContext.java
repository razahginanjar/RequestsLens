package agent.profiling;

import agent.core.AgentSelfMetrics;
import agent.model.MethodSpan;

import java.lang.reflect.Array;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.Map;

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
    public static volatile AgentSelfMetrics selfMetrics;
    private static volatile DebugSnapshotConfig debugSnapshotConfig =
        DebugSnapshotConfig.disabled();

    private final Deque<MethodSpan> stack      = new ArrayDeque<>();
    private final Deque<long[]>     startStack = new ArrayDeque<>();  // [wallNs, cpuNs, allocBytes]
    private final Deque<LineState>  lineStack  = new ArrayDeque<>();
    private final String traceId;
    private final int maxDepth;
    private final int maxSpans;
    private final boolean debugSnapshotsEnabled;
    private final boolean debugCaptureArgs;
    private final boolean debugCaptureReturn;
    private final int debugMaxSnapshotsPerTrace;
    private final int debugMaxSnapshotsPerSpan;
    private final int debugMaxValueLength;
    private int spanCount;
    private int suppressedDepth;
    private int droppedSpans;
    private int debugSnapshotCount;
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
        DebugSnapshotConfig debugConfig = debugSnapshotConfig;
        this.traceId = traceId;
        this.maxDepth = maxDepth;
        this.maxSpans = maxSpans;
        this.debugSnapshotsEnabled = debugConfig.enabled();
        this.debugCaptureArgs = debugConfig.captureArgs();
        this.debugCaptureReturn = debugConfig.captureReturn();
        this.debugMaxSnapshotsPerTrace = debugConfig.maxSnapshotsPerTrace();
        this.debugMaxSnapshotsPerSpan = debugConfig.maxSnapshotsPerSpan();
        this.debugMaxValueLength = debugConfig.maxValueLength();
        stack.push(root);
        lineStack.push(new LineState());
    }

    private record DebugSnapshotConfig(
        boolean enabled,
        boolean captureArgs,
        boolean captureReturn,
        int maxSnapshotsPerTrace,
        int maxSnapshotsPerSpan,
        int maxValueLength
    ) {
        static DebugSnapshotConfig disabled() {
            return new DebugSnapshotConfig(false, true, true, 1, 1, 1);
        }
    }

    private static final class LineState {
        int lineNumber = -1;
        long wallStartNs;
        long cpuStartNs;
        long childWallNs;
        long childCpuNs;
    }

    public static void configureDebugSnapshots(boolean enabled,
                                               boolean captureArgs,
                                               boolean captureReturn,
                                               int maxSnapshotsPerTrace,
                                               int maxSnapshotsPerSpan,
                                               int maxValueLength) {
        debugSnapshotConfig = new DebugSnapshotConfig(
            enabled,
            captureArgs,
            captureReturn,
            Math.max(1, maxSnapshotsPerTrace),
            Math.max(1, maxSnapshotsPerSpan),
            Math.max(1, maxValueLength));
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
        int state = c.canEnterSpan();
        if (state != ENTER_SPAN) return state;

        MethodSpan span = new MethodSpan();
        span.className  = className;
        span.methodName = methodName;
        c.pushSpan(span);
        return ENTER_SPAN;
    }

    /** Pushes a span and optionally records bounded method argument summaries. */
    public static int methodEnterState(String className, String methodName,
                                       Object[] args) {
        int state = methodEnterState(className, methodName);
        if (state != ENTER_SPAN) return state;
        try {
            RequestProfilingContext c = CURRENT.get();
            if (c != null && c.debugSnapshotsEnabled && c.debugCaptureArgs) {
                c.recordArgumentSnapshots(args);
            }
        } catch (Throwable failure) {
            recordInternalError();
            // Debug capture must never affect application execution.
        }
        return state;
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
     * Completes a debug-traced method span and records a bounded return or
     * exception summary before the span is popped.
     */
    public static void methodExit(int enterState, Object returned, Throwable thrown,
                                  boolean hasReturnValue) {
        try {
            RequestProfilingContext c = CURRENT.get();
            if (c != null && enterState == ENTER_SPAN && c.debugSnapshotsEnabled
                    && c.stack.size() > 1) {
                if (thrown != null) {
                    c.recordActiveSnapshot("throw", "throwable", thrown);
                } else if (hasReturnValue && c.debugCaptureReturn) {
                    c.recordActiveSnapshot("return", "return", returned);
                }
            }
        } catch (Throwable failure) {
            recordInternalError();
            // Debug capture must never affect application execution.
        }
        methodExit(enterState);
    }

    public static void methodExit(int enterState, Object returned, Throwable thrown) {
        methodExit(enterState, returned, thrown, true);
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
        } catch (Throwable failure) {
            recordInternalError();
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
        } catch (Throwable failure) {
            recordInternalError();
            // Allocation probes must never break the application.
        }
    }

    private static void recordInternalError() {
        AgentSelfMetrics metrics = selfMetrics;
        if (metrics != null) {
            metrics.incrementInternalErrors();
        }
    }

    private void recordArgumentSnapshots(Object[] args) {
        if (args == null || args.length == 0) return;
        for (int i = 0; i < args.length; i++) {
            recordActiveSnapshot("arg", "arg" + i, args[i]);
        }
    }

    private void recordActiveSnapshot(String kind, String name, Object value) {
        MethodSpan span = stack.peek();
        if (span == null) return;
        recordSnapshot(span, snapshot(kind, name, value, debugMaxValueLength));
    }

    private void recordSnapshot(MethodSpan span, MethodSpan.DebugSnapshot snapshot) {
        if (span == null || snapshot == null) return;
        if (debugSnapshotCount >= debugMaxSnapshotsPerTrace) {
            span.droppedDebugSnapshots++;
            span.debugSnapshotsTruncated = true;
            return;
        }
        if (span.debugSnapshots.size() >= debugMaxSnapshotsPerSpan) {
            span.droppedDebugSnapshots++;
            span.debugSnapshotsTruncated = true;
            return;
        }
        span.debugSnapshots.add(snapshot);
        debugSnapshotCount++;
    }

    private static MethodSpan.DebugSnapshot snapshot(String kind, String name,
                                                     Object value, int maxLength) {
        String type = "null";
        String summary = "null";
        if (value != null) {
            Class<?> valueType = value.getClass();
            type = valueType.getName();
            summary = summarizeValue(value, valueType);
        }
        TruncatedText truncated = truncate(summary, maxLength);
        return new MethodSpan.DebugSnapshot(kind, name, type,
            truncated.text(), truncated.truncated());
    }

    private static String summarizeValue(Object value, Class<?> valueType) {
        try {
            if (valueType.isArray()) {
                int length = Array.getLength(value);
                Class<?> componentType = valueType.getComponentType();
                return componentTypeName(componentType) + "[" + length + "]";
            }
            if (value instanceof CharSequence
                    || value instanceof Number
                    || value instanceof Boolean
                    || value instanceof Character
                    || valueType.isEnum()) {
                return String.valueOf(value);
            }
            if (value instanceof Collection<?> collection) {
                return valueType.getName() + " size=" + collection.size();
            }
            if (value instanceof Map<?, ?> map) {
                return valueType.getName() + " size=" + map.size();
            }
            return String.valueOf(value);
        } catch (Throwable t) {
            return valueType.getName() + " (summary unavailable: "
                + t.getClass().getSimpleName() + ")";
        }
    }

    private static String componentTypeName(Class<?> componentType) {
        return componentType == null ? "unknown" : componentType.getName();
    }

    private static TruncatedText truncate(String value, int maxLength) {
        String text = value == null ? "" : value;
        int limit = Math.max(1, maxLength);
        if (text.length() <= limit) {
            return new TruncatedText(text, false);
        }
        if (limit <= 3) {
            return new TruncatedText(text.substring(0, limit), true);
        }
        return new TruncatedText(text.substring(0, limit - 3) + "...", true);
    }

    private record TruncatedText(String text, boolean truncated) {
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
