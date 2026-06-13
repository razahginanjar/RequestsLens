package agent.collector.spring;

import net.bytebuddy.asm.Advice;

/**
 * Byte Buddy advice injected into AbstractApplicationContext.refresh().
 *
 * <h2>Why this class references NO org.springframework types</h2>
 * The agent's classes are loaded by the system classloader, but in a Spring
 * Boot fat jar Spring itself is loaded by a child classloader (LaunchedClassLoader).
 * If this advice — or anything it calls — referenced a Spring type in a method
 * signature, the JVM would try to resolve that type from the system classloader
 * and fail with NoClassDefFoundError. So we treat the context purely as
 * {@link Object} and hand it to {@link SpringContextListener}, which uses
 * reflection to talk to it.
 *
 * <h2>Inlining</h2>
 * Byte Buddy copies this method's bytecode into AbstractApplicationContext.refresh().
 * The {@code listener} field must be {@code public} so that inlined access from
 * Spring's class (a different package/classloader) is legal.
 */
public final class ApplicationContextAdvice {

    /**
     * Set by SpringInstrumentation before the AgentBuilder is installed.
     * Public + static — see the class javadoc and {@link DispatcherServletAdvice}.
     */
    public static SpringContextListener listener;

    /**
     * Called after AbstractApplicationContext.refresh() completes.
     *
     * @Advice.This gives us the intercepted instance (the ApplicationContext)
     * as a plain Object — we never cast it to a Spring type here.
     */
    @Advice.OnMethodExit
    public static void onRefreshComplete(@Advice.This Object context) {
        if (listener == null) return;
        try {
            listener.onContextRefreshed(context);
        } catch (Throwable t) {
            // Must NEVER propagate — this runs inside Spring's refresh(). Catch
            // Throwable (not just Exception) so even a linkage Error can't crash
            // the target application's startup.
        }
    }
}
