package agent.collector.trace;

import agent.profiling.ExternalSpanSupport;

import net.bytebuddy.asm.Advice;

/** Advice for JDBC Statement methods that receive the SQL string as argument 0. */
public final class JdbcStatementAdvice {

    @Advice.OnMethodEnter
    public static int enter(@Advice.Argument(0) String sql) {
        return ExternalSpanSupport.enterSql(sql);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(@Advice.Enter int enterState) {
        ExternalSpanSupport.exit(enterState);
    }
}
