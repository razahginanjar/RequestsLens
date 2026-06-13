package agent.model;

/**
 * Estimated memory footprint of a single Spring bean.
 *
 * "Estimated" is the key word — we use Instrumentation.getObjectSize()
 * which gives shallow size (the object itself, not its references).
 * We walk the object graph up to depth 3 for a deeper estimate, but
 * this is never perfectly accurate. It is accurate enough to rank beans
 * by relative memory usage, which is the goal.
 */
public record BeanMemoryInfo(
    /** The Spring bean name, e.g. "userService", "dataSource" */
    String beanName,

    /** The fully qualified class name */
    String className,

    /** Spring scope: "singleton", "prototype", "request", etc. */
    String scope,

    /** Estimated bytes used by this bean and its immediate object graph */
    long estimatedBytes
) {}