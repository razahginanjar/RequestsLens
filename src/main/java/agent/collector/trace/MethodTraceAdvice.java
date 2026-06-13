package agent.collector.trace;

import agent.profiling.RequestProfilingContext;

import net.bytebuddy.asm.Advice;

/**
 * Byte Buddy advice inlined into every instrumented application method (Phase 6).
 *
 * <h2>Tier 1 — runs on every call of every instrumented method</h2>
 * The fast path (request not being traced) is a single ThreadLocal read inside
 * {@link RequestProfilingContext#methodEnterState}: it returns immediately and
 * the method runs as normal. Only when a request is selected for tracing does the
 * call tree get built.
 *
 * <h2>Advice rules (carried from the Phase 2–5 classloader fixes)</h2>
 * This advice is inlined into application classes loaded by a child classloader,
 * so it must:
 * <ul>
 *   <li>reference NO framework/shaded types — it uses only {@code String}/{@code boolean}
 *       and calls {@link RequestProfilingContext} (an agent class, visible via parent
 *       delegation);</li>
 *   <li>access NO advice-class fields from inlined code (there are none here);</li>
 *   <li>never throw into the application (the helper methods swallow their own errors).</li>
 * </ul>
 * The enter value is a primitive {@code int} so no object type crosses the
 * enter/exit boundary.
 */
public final class MethodTraceAdvice {

    /**
     * @param type   declaring class name (Byte Buddy fills #t)
     * @param method method name (Byte Buddy fills #m)
     * @return enter state; pushed spans and suppressed subtrees must be closed on exit
     */
    @Advice.OnMethodEnter
    public static int enter(@Advice.Origin("#t") String type,
                            @Advice.Origin("#m") String method) {
        return RequestProfilingContext.methodEnterState(type, method);
    }

    /**
     * @param enterState the value returned by {@link #enter}
     */
    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(@Advice.Enter int enterState) {
        if (enterState != RequestProfilingContext.ENTER_NONE) {
            RequestProfilingContext.methodExit(enterState);
        }
    }
}
