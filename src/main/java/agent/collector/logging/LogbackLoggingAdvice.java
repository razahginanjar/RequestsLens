package agent.collector.logging;

import net.bytebuddy.asm.Advice;

public final class LogbackLoggingAdvice {

    private LogbackLoggingAdvice() {
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void onEnter(@Advice.This(optional = true) Object logger,
                        @Advice.Argument(0) Object event) {
        LogCaptureSupport.recordLogbackEvent(logger, event);
    }
}
