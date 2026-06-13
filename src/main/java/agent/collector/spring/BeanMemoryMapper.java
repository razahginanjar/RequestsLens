package agent.collector.spring;

import agent.model.BeanMemoryInfo;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.logging.Logger;

/**
 * Maps Spring bean instances to estimated heap footprints.
 *
 * <h2>Why this class uses reflection instead of Spring types</h2>
 * The agent is loaded by the system classloader; in a Spring Boot fat jar,
 * Spring lives in a child classloader the system classloader cannot see. If
 * this class imported {@code org.springframework.*}, it would fail to link at
 * runtime with NoClassDefFoundError. So the ApplicationContext is held as
 * {@link Object} and every Spring call (getBeanFactory, getBeanDefinitionNames,
 * getBean, getBeanDefinition, getScope) is made reflectively against the live
 * object's own class — which resolves correctly because reflection dispatches
 * on the runtime type, not on a compile-time link.
 *
 * <h2>Strategy</h2>
 * <ol>
 *   <li>Get the bean factory and its bean-definition names from the context.</li>
 *   <li>For each bean, walk its object graph up to {@link #MAX_GRAPH_DEPTH}
 *       levels deep, summing {@code Instrumentation.getObjectSize()}.</li>
 *   <li>Cache the ranked result for {@link #CACHE_DURATION_MS}.</li>
 * </ol>
 * Sizes are estimates — good enough to rank beans by relative memory, not for
 * exact accounting.
 *
 * <h2>Threading</h2>
 * Scanning runs on the aggregation daemon thread. Results are published to
 * CollectorRegistry.beanMemoryRanking.
 */
public final class BeanMemoryMapper {

    private static final Logger log = Logger.getLogger(BeanMemoryMapper.class.getName());

    private static final int    MAX_GRAPH_DEPTH   = 3;
    private static final int    TOP_N_BEANS       = 20;
    private static final long   CACHE_DURATION_MS = 30_000L;

    private final Instrumentation instrumentation;

    // Cache state — updated every CACHE_DURATION_MS
    private List<BeanMemoryInfo> cachedRanking    = List.of();
    private long                 cacheExpiresAtMs = 0L;

    /** The ApplicationContext, held as Object to avoid linking Spring types. */
    private volatile Object      currentContext   = null;

    public BeanMemoryMapper(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
    }

    /**
     * Called by SpringContextListener when ApplicationContext.refresh() completes.
     * Stores the context (as Object) for subsequent reflective scans.
     */
    public void onContextRefreshed(Object context) {
        this.currentContext   = context;
        this.cacheExpiresAtMs = 0L;   // invalidate cache — context changed
        log.info("BeanMemoryMapper: ApplicationContext registered");
    }

    /**
     * Returns the top N beans by estimated memory, using the cache if fresh.
     * Call from the aggregation daemon thread.
     */
    public List<BeanMemoryInfo> getTopBeans() {
        Object context = currentContext;
        if (context == null) return List.of();

        if (System.currentTimeMillis() < cacheExpiresAtMs) {
            return cachedRanking;
        }

        cachedRanking    = scanBeans(context);
        cacheExpiresAtMs = System.currentTimeMillis() + CACHE_DURATION_MS;
        return cachedRanking;
    }

    /**
     * Reflectively scans all bean definitions and builds a ranked list.
     * Moderately expensive — runs on the aggregation thread, not a hot path.
     */
    private List<BeanMemoryInfo> scanBeans(Object context) {
        long startMs = System.currentTimeMillis();
        List<BeanMemoryInfo> results = new ArrayList<>();

        // factory = ((ConfigurableApplicationContext) context).getBeanFactory()
        Object factory = invoke(context, "getBeanFactory");
        if (factory == null) {
            log.fine("BeanMemoryMapper: getBeanFactory() unavailable — skipping scan");
            return List.of();
        }

        Object namesObj = invoke(factory, "getBeanDefinitionNames");
        if (!(namesObj instanceof String[] names)) {
            return List.of();
        }

        for (String name : names) {
            try {
                Object bean = invoke(factory, "getBean", String.class, name);
                if (bean == null) continue;

                // Resolve the bean's scope via getBeanDefinition(name).getScope();
                // default to "singleton" if anything is unavailable.
                String scope = "singleton";
                Object beanDef = invoke(factory, "getBeanDefinition", String.class, name);
                Object scopeObj = beanDef == null ? null : invoke(beanDef, "getScope");
                if (scopeObj instanceof String s && !s.isBlank()) scope = s;

                long estimated = estimateSize(bean);

                results.add(new BeanMemoryInfo(
                    name,
                    bean.getClass().getName(),
                    scope,
                    estimated
                ));
            } catch (Throwable t) {
                // Some beans throw on getBean() (factory beans, scope proxies,
                // lazy beans that fail to init) — skip them silently.
            }
        }

        results.sort(Comparator.comparingLong(BeanMemoryInfo::estimatedBytes).reversed());
        List<BeanMemoryInfo> top = results.stream().limit(TOP_N_BEANS).toList();

        log.fine(() -> "BeanMemoryMapper scanned " + names.length + " beans in "
            + (System.currentTimeMillis() - startMs) + "ms");

        return top;
    }

    // ── Reflection helpers ──────────────────────────────────────────────────
    // All swallow failures and return null: the agent must never destabilize the
    // target app, and Spring internals vary across versions.

    private static Object invoke(Object target, String method) {
        return invoke(target, method, null, null);
    }

    private static Object invoke(Object target, String method, Class<?> argType, Object arg) {
        if (target == null) return null;
        try {
            Method m = (argType == null)
                ? target.getClass().getMethod(method)
                : target.getClass().getMethod(method, argType);
            m.setAccessible(true);  // the resolved method may live on a non-public class
            return (argType == null) ? m.invoke(target) : m.invoke(target, arg);
        } catch (Throwable t) {
            return null;
        }
    }

    // ── Size estimation (no Spring types involved) ──────────────────────────

    /**
     * Estimates an object's heap size by walking its field graph up to
     * MAX_GRAPH_DEPTH levels, using an IdentityHashMap to avoid double-counting
     * shared/circular references.
     */
    private long estimateSize(Object root) {
        if (root == null) return 0L;
        return walkGraph(root, 0, new IdentityHashMap<>());
    }

    private long walkGraph(Object obj, int depth, IdentityHashMap<Object, Boolean> visited) {
        if (obj == null) return 0L;
        if (visited.containsKey(obj)) return 0L;   // already counted
        if (depth > MAX_GRAPH_DEPTH) return 0L;     // depth limit

        visited.put(obj, Boolean.TRUE);

        long size = instrumentation.getObjectSize(obj);

        if (depth < MAX_GRAPH_DEPTH) {
            Class<?> cls = obj.getClass();
            while (cls != null && cls != Object.class) {
                for (Field field : cls.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) continue;
                    if (field.getType().isPrimitive()) continue;
                    if (field.isSynthetic()) continue;
                    try {
                        field.setAccessible(true);
                        size += walkGraph(field.get(obj), depth + 1, visited);
                    } catch (Throwable e) {
                        // InaccessibleObjectException (JDK modules) etc. — skip field.
                    }
                }
                cls = cls.getSuperclass();
            }
        }
        return size;
    }
}
