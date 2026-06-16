package agent.collector.trace;

import agent.profiling.ExternalSpanSupport;

import net.bytebuddy.asm.Advice;

/** Advice for JDBC PreparedStatement no-argument execute methods. */
public final class JdbcPreparedStatementAdvice {

    @Advice.OnMethodEnter
    public static int enter(@Advice.This Object statement) {
        return ExternalSpanSupport.enterPreparedSql(statement);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(@Advice.Enter int enterState) {
        ExternalSpanSupport.exit(enterState);
    }
}
