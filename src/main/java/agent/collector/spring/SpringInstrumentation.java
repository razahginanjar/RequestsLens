package agent.collector.spring;

import agent.core.AgentConfig;
import agent.core.CollectorRegistry;
import agent.core.InstrumentationDiagnostics;
import agent.collector.trace.DebugMethodTraceAdvice;
import agent.collector.trace.MethodTraceAdvice;
import agent.collector.trace.AllocationInstrumentation;
import agent.collector.trace.JdbcPreparedStatementAdvice;
import agent.collector.trace.JdbcStatementAdvice;
import agent.collector.trace.RestTemplateAdvice;
import agent.collector.logging.Log4j2LoggingAdvice;
import agent.collector.logging.LogbackLoggingAdvice;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import java.lang.instrument.Instrumentation;
import java.util.logging.Logger;

/**
 * Configures and installs Byte Buddy bytecode transformations.
 *
 * This class is called once from AgentMain.premain() and installs
 * two interceptors:
 *   1. DispatcherServlet.doDispatch() — for endpoint tracking
 *   2. AbstractApplicationContext.refresh() — for bean discovery
 *
 * <h2>How AgentBuilder works</h2>
 * AgentBuilder.type() selects which classes to intercept using matchers.
 * .transform() defines what to do with matched classes — we use
 * Advice.to() to inject our advice code.
 * .installOn(instrumentation) activates the transformation.
 *
 * After installOn(), any class matching our selector that has not yet
 * been loaded will have our advice code injected when it is first loaded.
 * Classes already loaded can also be retransformed (which is why the
 * MANIFEST.MF declares Can-Retransform-Classes: true).
 */
public final class SpringInstrumentation {

    private static final Logger log =
        Logger.getLogger(SpringInstrumentation.class.getName());

    private final Instrumentation  jvmInstrumentation;
    private final CollectorRegistry registry;
    private final AgentConfig       config;

    /**
     * The shared bean mapper. Created once in AgentMain and passed to both
     * this class (which feeds it the Spring context when refresh() completes)
     * and the AggregationDaemon (which reads ranked beans from it). Both must
     * reference the SAME instance, otherwise the daemon's mapper never sees a
     * context and /profiler/beans stays empty.
     */
    private final BeanMemoryMapper  beanMapper;

    public SpringInstrumentation(Instrumentation jvmInstrumentation,
                                 CollectorRegistry registry,
                                 AgentConfig config,
                                 BeanMemoryMapper beanMapper) {
        this.jvmInstrumentation = jvmInstrumentation;
        this.registry           = registry;
        this.config             = config;
        this.beanMapper         = beanMapper;
    }

    /**
     * Installs all Byte Buddy transformations.
     * Call once from AgentMain.premain() before the application starts.
     */
    public void install() {
        // ── Wire static fields in advice classes ──────────────────────────
        // Byte Buddy advice methods are static. They access shared state via
        // static fields. We set those fields here, before installing.

        // DispatcherServletAdvice needs the endpoint buffer
        DispatcherServletAdvice.endpointBuffer = registry.endpointBuffer();
        DispatcherServletAdvice.selfMetrics = registry.selfMetrics();

        // ApplicationContextAdvice needs the Spring context listener.
        // The listener routes the discovered ApplicationContext into the SHARED
        // beanMapper (injected via the constructor) — the very same instance the
        // AggregationDaemon scans. Do NOT create a new BeanMemoryMapper here.
        SpringContextListener listener = new SpringContextListener(
            registry, beanMapper);
        ApplicationContextAdvice.listener = listener;

        // ── Build and install the AgentBuilder ────────────────────────────
        final String tracePkgs = config.getTracePackages();
        final InstrumentationDiagnostics diagnostics =
            registry.instrumentationDiagnostics();
        final ElementMatcher.Junction<MethodDescription> methodMatcher =
            ElementMatchers.isMethod()
                .and(ElementMatchers.not(ElementMatchers.isConstructor()))
                .and(ElementMatchers.not(ElementMatchers.isAbstract()))
                .and(ElementMatchers.not(ElementMatchers.isSynthetic()))
                .and(ElementMatchers.not(ElementMatchers.isTypeInitializer()))
                .and(ElementMatchers.not(ElementMatchers.isGetter()))
                .and(ElementMatchers.not(ElementMatchers.isSetter()));
        final boolean allocDetail = config.isAllocDetailEnabled();
        final boolean lineAllocDetail = config.isLineAllocationProfilingActive();
        final boolean deterministicLineDetail = config.isDeterministicLineProfilingActive();
        final boolean debugSnapshots = config.isRequestDebugSnapshotActive();
        final boolean debugCaptureReturn = config.isDebugSnapshotCaptureReturn();
        if (deterministicLineDetail) {
            diagnostics.enableLineNumberDiagnostics();
        }

        AgentBuilder builder = new AgentBuilder.Default()

            // Log which target types we transform, and any errors on types we
            // care about — so instrumentation failures are visible, not silent.
            .with(new AgentBuilder.Listener.Adapter() {
                @Override
                public void onDiscovery(String typeName, ClassLoader classLoader,
                                        net.bytebuddy.utility.JavaModule module,
                                        boolean loaded) {
                    if (matchesAnyPackage(typeName, tracePkgs)) {
                        diagnostics.recordTraceClassDiscovered(typeName, loaded);
                    }
                }

                @Override
                public void onTransformation(TypeDescription type, ClassLoader classLoader,
                                             net.bytebuddy.utility.JavaModule module,
                                             boolean loaded,
                                             net.bytebuddy.dynamic.DynamicType dynamicType) {
                    String n = type.getName();
                    // Framework targets at INFO; the (potentially many) traced app
                    // classes at FINE to keep startup logs readable.
                    if (n.contains("DispatcherServlet") || n.contains("AbstractApplicationContext")) {
                        diagnostics.recordFrameworkTransformation(n);
                        log.info("Instrumented: " + n);
                    } else {
                        log.fine(() -> "Instrumented (trace): " + n);
                    }
                    if (matchesAnyPackage(n, tracePkgs)) {
                        diagnostics.recordTraceTransformation(n, loaded,
                            type.getDeclaredMethods().filter(methodMatcher).size());
                    }
                }
                @Override
                public void onError(String typeName, ClassLoader classLoader,
                                    net.bytebuddy.utility.JavaModule module,
                                    boolean loaded, Throwable throwable) {
                    if (typeName.contains("DispatcherServlet")
                            || typeName.contains("AbstractApplicationContext")
                            || matchesAnyPackage(typeName, tracePkgs)) {
                        diagnostics.recordError(typeName, throwable);
                        log.warning("Instrumentation error on " + typeName + ": " + throwable);
                    }
                }
            })

            .ignore(ElementMatchers.nameStartsWith("agent.")
                .or(ElementMatchers.nameStartsWith("java."))
                .or(ElementMatchers.nameStartsWith("javax."))
                .or(ElementMatchers.nameStartsWith("jdk."))
                .or(ElementMatchers.nameStartsWith("sun.")))

            // Transformation 1: DispatcherServlet.doDispatch() — endpoint + trace root
            .type(ElementMatchers.named(
                "org.springframework.web.servlet.DispatcherServlet"))
            .transform((b, type, cl, module, domain) ->
                b.visit(Advice.to(DispatcherServletAdvice.class)
                              .on(ElementMatchers.named("doDispatch"))))

            // Transformation 2: AbstractApplicationContext.refresh() — bean discovery
            .type(ElementMatchers.named(
                "org.springframework.context.support.AbstractApplicationContext"))
            .transform((b, type, cl, module, domain) ->
                b.visit(Advice.to(ApplicationContextAdvice.class)
                              .on(ElementMatchers.named("refresh"))));

        if (config.isLogCaptureEnabled()) {
            ElementMatcher.Junction<MethodDescription> log4jLogMessage =
                ElementMatchers.named("logMessage")
                    .and(ElementMatchers.takesArguments(5)
                        .or(ElementMatchers.takesArguments(6)));
            builder = builder
                .type(ElementMatchers.named("ch.qos.logback.classic.Logger"))
                .transform((b, type, cl, module, domain) ->
                    b.visit(Advice.to(LogbackLoggingAdvice.class)
                        .on(ElementMatchers.named("callAppenders")
                            .and(ElementMatchers.takesArguments(1)))))
                .type(ElementMatchers.named("org.apache.logging.log4j.spi.AbstractLogger")
                    .or(ElementMatchers.named("org.apache.logging.log4j.core.Logger")))
                .transform((b, type, cl, module, domain) ->
                    b.visit(Advice.to(Log4j2LoggingAdvice.class)
                        .on(log4jLogMessage)));
        }

        // Transformation 3 (Phase 6): per-method tracing of configured app packages.
        // Gated on config — only when tracing is enabled AND packages are set, so we
        // never instrument "everything". The MethodTraceAdvice fast-path makes this a
        // no-op for requests that are not selected for tracing.
        if (config.isTraceEnabled() && tracePkgs != null && !tracePkgs.isBlank()) {
            ElementMatcher.Junction<TypeDescription> pkgMatcher = ElementMatchers.<TypeDescription>none();
            for (String p : tracePkgs.split(",")) {
                String prefix = p.trim();
                if (!prefix.isEmpty()) {
                    pkgMatcher = pkgMatcher.or(ElementMatchers.<TypeDescription>nameStartsWith(prefix));
                }
            }
            builder = builder
                .type(pkgMatcher.and(ElementMatchers.not(ElementMatchers.isInterface())))
                .transform((b, type, cl, module, domain) -> {
                    // 3a. method enter/exit timing + per-method alloc totals
                    net.bytebuddy.dynamic.DynamicType.Builder<?> nb;
                    if (debugSnapshots && debugCaptureReturn) {
                        nb = b.visit(Advice.to(DebugMethodTraceAdvice.WithReturn.class)
                                .on(methodMatcher.and(ElementMatchers.not(
                                    ElementMatchers.returns(void.class)))))
                            .visit(Advice.to(DebugMethodTraceAdvice.NoReturn.class)
                                .on(methodMatcher.and(ElementMatchers.returns(void.class))));
                    } else if (debugSnapshots) {
                        nb = b.visit(Advice.to(DebugMethodTraceAdvice.NoReturn.class)
                            .on(methodMatcher));
                    } else {
                        nb = b.visit(Advice.to(MethodTraceAdvice.class).on(methodMatcher));
                    }
                    // 3b. (Amendment A) per-object allocation capture at each
                    //     new/array site inside these methods.
                    if (allocDetail || lineAllocDetail || deterministicLineDetail) {
                        nb = nb.visit(AllocationInstrumentation.forMethods(methodMatcher,
                            lineAllocDetail, deterministicLineDetail,
                            allocDetail || lineAllocDetail, diagnostics));
                    }
                    return nb;
                });
            log.info("Method tracing enabled for packages: " + tracePkgs
                + (allocDetail ? " (+ per-object allocation detail)" : "")
                + (lineAllocDetail ? " (+ per-line allocation detail)" : "")
                + (deterministicLineDetail ? " (+ deterministic line timing)" : "")
                + (debugSnapshots ? " (+ request debug snapshots)" : ""));

            ElementMatcher.Junction<MethodDescription> jdbcExecuteWithSql =
                ElementMatchers.named("execute")
                    .or(ElementMatchers.named("executeQuery"))
                    .or(ElementMatchers.named("executeUpdate"))
                    .or(ElementMatchers.named("executeLargeUpdate"))
                    .and(ElementMatchers.takesArgument(0,
                        ElementMatchers.named("java.lang.String")));
            ElementMatcher.Junction<MethodDescription> preparedExecute =
                ElementMatchers.named("execute")
                    .or(ElementMatchers.named("executeQuery"))
                    .or(ElementMatchers.named("executeUpdate"))
                    .or(ElementMatchers.named("executeLargeUpdate"))
                    .and(ElementMatchers.takesArguments(0));
            ElementMatcher.Junction<MethodDescription> restTemplateDoExecute =
                ElementMatchers.named("doExecute")
                    .and(ElementMatchers.takesArgument(0,
                        ElementMatchers.named("java.net.URI")))
                    .and(ElementMatchers.takesArgument(2,
                        ElementMatchers.named("org.springframework.http.HttpMethod")));
            ElementMatcher.Junction<TypeDescription> jdbcStatementType =
                ElementMatchers.hasSuperType(ElementMatchers.named("java.sql.Statement"))
                    // Some drivers declare executeQuery(String) on an abstract
                    // jdbc.* superclass, then implement Statement only on a
                    // concrete subclass. Match the declaring driver class too.
                    .or(ElementMatchers.nameContains(".jdbc")
                        .and(ElementMatchers.declaresMethod(jdbcExecuteWithSql)));
            ElementMatcher.Junction<TypeDescription> jdbcPreparedStatementType =
                ElementMatchers.hasSuperType(ElementMatchers.named("java.sql.PreparedStatement"))
                    .or(ElementMatchers.nameContains(".jdbc")
                        .and(ElementMatchers.declaresMethod(preparedExecute)));

            builder = builder
                .type(jdbcStatementType)
                .transform((b, type, cl, module, domain) ->
                    b.visit(Advice.to(JdbcStatementAdvice.class)
                        .on(jdbcExecuteWithSql)))
                .type(jdbcPreparedStatementType)
                .transform((b, type, cl, module, domain) ->
                    b.visit(Advice.to(JdbcPreparedStatementAdvice.class)
                        .on(preparedExecute)))
                .type(ElementMatchers.named("org.springframework.web.client.RestTemplate"))
                .transform((b, type, cl, module, domain) ->
                    b.visit(Advice.to(RestTemplateAdvice.class)
                        .on(restTemplateDoExecute)));
        }

        builder.installOn(jvmInstrumentation);

        log.info("SpringInstrumentation installed — DispatcherServlet and "
            + "ApplicationContext interception active"
            + (config.isTraceEnabled() && tracePkgs != null && !tracePkgs.isBlank()
                ? " (+ method tracing)" : "")
            + (config.isLogCaptureEnabled() ? " (+ live logs)" : ""));
    }

    /** True if {@code typeName} starts with any comma-separated prefix in {@code packages}. */
    private static boolean matchesAnyPackage(String typeName, String packages) {
        if (packages == null || packages.isBlank()) return false;
        for (String p : packages.split(",")) {
            String prefix = p.trim();
            if (!prefix.isEmpty() && typeName.startsWith(prefix)) return true;
        }
        return false;
    }
}
