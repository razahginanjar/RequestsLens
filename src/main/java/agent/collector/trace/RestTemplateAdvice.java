package agent.collector.trace;

import agent.profiling.ExternalSpanSupport;

import net.bytebuddy.asm.Advice;

/** Advice for Spring RestTemplate outbound HTTP calls. */
public final class RestTemplateAdvice {

    @Advice.OnMethodEnter
    public static int enter(@Advice.Argument(0) Object uri,
                            @Advice.Argument(2) Object method) {
        return ExternalSpanSupport.enterHttp(method, uri);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(@Advice.Enter int enterState) {
        ExternalSpanSupport.exit(enterState);
    }
}
