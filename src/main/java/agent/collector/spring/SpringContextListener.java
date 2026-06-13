package agent.collector.spring;

import agent.core.CollectorRegistry;

import java.util.logging.Logger;

/**
 * Receives the ApplicationContext reference from the Byte Buddy advice and
 * routes it to the collectors that need it.
 *
 * <h2>Why the parameter is Object, not ApplicationContext</h2>
 * This class is loaded by the agent's (system) classloader, which cannot see
 * Spring in a fat-jar deployment. If the method signature named a Spring type,
 * resolving this class would fail with NoClassDefFoundError. We therefore accept
 * the context as {@link Object} and let {@link BeanMemoryMapper} access it via
 * reflection.
 */
public final class SpringContextListener {

    private static final Logger log =
        Logger.getLogger(SpringContextListener.class.getName());

    private final CollectorRegistry registry;
    private final BeanMemoryMapper  beanMapper;

    public SpringContextListener(CollectorRegistry registry,
                                 BeanMemoryMapper beanMapper) {
        this.registry   = registry;
        this.beanMapper = beanMapper;
    }

    /**
     * Called when Spring's ApplicationContext.refresh() completes.
     *
     * @param context the fully initialized ApplicationContext, as a raw Object
     */
    public void onContextRefreshed(Object context) {
        log.info("Spring ApplicationContext detected — registering for bean scan");
        beanMapper.onContextRefreshed(context);
    }
}
