package agent.collector.spring;

import agent.buffer.RingBuffer;
import agent.model.EndpointSample;

import net.bytebuddy.asm.Advice;
import java.lang.management.ManagementFactory;

/**
 * Byte Buddy advice injected into DispatcherServlet.doDispatch().
 *
 * <h2>Why this advice references NO jakarta.servlet types</h2>
 * The agent jar shades/relocates {@code jakarta.servlet} to
 * {@code agent.shaded.jakarta.servlet}. If the advice declared its parameter as
 * {@code jakarta.servlet.http.HttpServletRequest}, the relocation rewrote it to
 * the shaded name, and Byte Buddy then refused to bind the real
 * {@code jakarta.servlet.http.HttpServletRequest} argument of {@code doDispatch}
 * ("Cannot assign ... to interface agent.shaded.jakarta.servlet..."), silently
 * skipping the transformation. So we take the request as {@link Object} and read
 * {@code getMethod()} / {@code getRequestURI()} reflectively — there is nothing
 * for the shade relocation to break.
 *
 * <h2>How Byte Buddy @Advice works</h2>
 * This class is NOT instantiated at runtime. Byte Buddy copies the onEnter/onExit
 * instructions directly into DispatcherServlet.doDispatch(), so they run on the
 * request thread (Tier 1): no logging, minimal allocation, no blocking.
 *
 * <h2>Static fields in advice</h2>
 * The ring buffer is a {@code public static} field set by SpringInstrumentation
 * before install. Public because inlined access runs from Spring's class in a
 * different package/classloader (see {@link ApplicationContextAdvice}).
 */
public final class DispatcherServletAdvice {

    private static final String BEST_MATCHING_PATTERN_ATTRIBUTE =
        "org.springframework.web.servlet.HandlerMapping.bestMatchingPattern";

    /**
     * The ring buffer where we write EndpointSamples.
     * Set by SpringInstrumentation before the AgentBuilder is installed.
     * Public + static — inlined access happens from Spring's class.
     */
    public static RingBuffer<EndpointSample> endpointBuffer;

    /**
     * Called when DispatcherServlet.doDispatch() is entered.
     *
     * @return a long[] of [startNanos, heapBeforeBytes, tracing?1:0], passed to onExit.
     */
    @Advice.OnMethodEnter
    public static long[] onEnter() {
        long startNs    = System.nanoTime();
        long heapBefore = ManagementFactory.getMemoryMXBean()
            .getHeapMemoryUsage().getUsed();
        // Phase 6: decide whether to deep-trace this request (sampled). The helper
        // begins a per-request method-trace context if selected. Calling an agent
        // class (not inlining its fields) keeps this advice classloader-safe.
        long tracing = 0L;
        try {
            if (agent.profiling.TraceSupport.requestEnter()) tracing = 1L;
        } catch (Throwable t) { /* never break the request */ }
        return new long[]{ startNs, heapBefore, tracing };
    }

    /**
     * Called when DispatcherServlet.doDispatch() exits (normally or via exception).
     *
     * @param request the HttpServletRequest argument, taken as Object (see class javadoc)
     * @param entered the long[] from onEnter — [startNs, heapBefore, tracing]
     */
    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(@Advice.Argument(0) Object request,
                              @Advice.Enter long[] entered) {
        if (entered == null) return;

        // Resolve method/path once (used by both the endpoint sample and the trace).
        String method = "UNKNOWN", path = "unknown";
        try {
            if (request != null) {
                Class<?> cls = request.getClass();
                method = String.valueOf(cls.getMethod("getMethod").invoke(request));
                Object pattern = cls.getMethod("getAttribute", String.class)
                    .invoke(request, BEST_MATCHING_PATTERN_ATTRIBUTE);
                if (pattern != null) {
                    path = String.valueOf(pattern);
                } else {
                    path = String.valueOf(cls.getMethod("getRequestURI").invoke(request));
                }
            }
        } catch (Throwable t) { /* keep defaults */ }

        // ── Endpoint latency/heap sample (Phase 2) ────────────────────────
        try {
            if (endpointBuffer != null) {
                long latencyMs = (System.nanoTime() - entered[0]) / 1_000_000L;
                long heapAfter = ManagementFactory.getMemoryMXBean()
                    .getHeapMemoryUsage().getUsed();
                endpointBuffer.write(new EndpointSample(
                    method, path, latencyMs, entered[1], heapAfter,
                    System.currentTimeMillis()));
            }
        } catch (Throwable t) { /* swallow — never break the request */ }

        // ── Finalize the deep request trace (Phase 6), if we started one ──
        try {
            if (entered.length > 2 && entered[2] == 1L) {
                agent.profiling.TraceSupport.requestExit(method, path);
            }
        } catch (Throwable t) { /* swallow */ }
    }
}
