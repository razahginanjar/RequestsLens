package agent.collector.trace;

import agent.profiling.RequestProfilingContext;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * Opt-in method advice for request debug snapshot mode. This is selected only
 * when debug snapshots are enabled, so normal method tracing does not pay the
 * argument/return binding cost.
 */
public final class DebugMethodTraceAdvice {

    private DebugMethodTraceAdvice() {
    }

    /** Advice for void methods: arguments and thrown exceptions only. */
    public static final class NoReturn {

        private NoReturn() {
        }

        @Advice.OnMethodEnter
        public static int enter(@Advice.Origin("#t") String type,
                                @Advice.Origin("#m") String method,
                                @Advice.AllArguments(readOnly = true,
                                    typing = Assigner.Typing.DYNAMIC) Object[] args) {
            return RequestProfilingContext.methodEnterState(type, method, args);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Enter int enterState,
                                @Advice.Thrown Throwable thrown) {
            if (enterState != RequestProfilingContext.ENTER_NONE) {
                RequestProfilingContext.methodExit(enterState, null, thrown, false);
            }
        }
    }

    /** Advice for non-void methods: arguments, return value, and exceptions. */
    public static final class WithReturn {

        private WithReturn() {
        }

        @Advice.OnMethodEnter
        public static int enter(@Advice.Origin("#t") String type,
                                @Advice.Origin("#m") String method,
                                @Advice.AllArguments(readOnly = true,
                                    typing = Assigner.Typing.DYNAMIC) Object[] args) {
            return RequestProfilingContext.methodEnterState(type, method, args);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Enter int enterState,
                                @Advice.Return(readOnly = true,
                                    typing = Assigner.Typing.DYNAMIC) Object returned,
                                @Advice.Thrown Throwable thrown) {
            if (enterState != RequestProfilingContext.ENTER_NONE) {
                RequestProfilingContext.methodExit(enterState, returned, thrown, true);
            }
        }
    }
}
