package agent.collector.logging;

import net.bytebuddy.asm.Advice;

public final class Log4j2LoggingAdvice {

    private Log4j2LoggingAdvice() {
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void onEnter(@Advice.This(optional = true) Object logger,
                        @Advice.AllArguments Object[] args) {
        LogCaptureSupport.recordLog4j2Event(logger, args);
    }
}
