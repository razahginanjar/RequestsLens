# Phase 2 — Spring Boot Integration
## Full Implementation Guide

> **Goal by end of this phase:** When the agent attaches to a Spring Boot app,
> it automatically detects the ApplicationContext, maps beans to estimated heap
> footprints, and intercepts every HTTP request to collect per-endpoint latency
> and memory delta — all with zero annotations or code changes in the target app.
>
> **Estimated time:** 5–7 days
> **Branch:** `phase/2-spring-integration`
> **Prerequisite:** Phase 1 complete and merged to `develop`

---

## Before You Start

### What Is Byte Buddy?

Byte Buddy is a Java library that lets you modify class bytecode at runtime —
after the JVM has loaded a class but before it runs. You use it to inject
code into existing methods without touching the source.

Think of it like this: you want to know how long
`DispatcherServlet.doDispatch()` takes. Normally you would add timing code
inside that method. But you cannot — it is Spring's code, not yours.
Byte Buddy lets you inject that timing code at runtime, invisibly.

The two Byte Buddy concepts you need to understand:

**AgentBuilder** — tells Byte Buddy which classes to intercept and which
advice to apply. You configure this in `premain()` using the `Instrumentation`
object the JVM passes in.

**@Advice** — annotations you put on your own methods to inject code at the
entry (`@Advice.OnMethodEnter`) or exit (`@Advice.OnMethodExit`) of the target
method. Byte Buddy copies your advice code directly into the target method's
bytecode — your advice class does not exist at runtime, only its instructions do.

### Why Shading Is Critical Here

Byte Buddy must be shaded and relocated inside the agent JAR. Many Spring Boot
apps use Byte Buddy indirectly through Hibernate, Mockito, or Spring itself.
If both the agent and the app load Byte Buddy from the same package name
(`net.bytebuddy`), only one version wins and the other silently breaks.

After relocation, the agent's Byte Buddy lives at `agent.shaded.bytebuddy.*` —
completely invisible to the target app.

---

## Step 1 — Add Byte Buddy to pom.xml

Open `pom.xml` and add inside `<dependencies>`:

```xml
<!-- ── Byte Buddy — bytecode instrumentation ──────────────────────────── -->
<!--
  byte-buddy (not byte-buddy-agent) gives us the AgentBuilder and Advice APIs.
  It will be shaded and relocated in the final JAR.
-->
<dependency>
  <groupId>net.bytebuddy</groupId>
  <artifactId>byte-buddy</artifactId>
  <version>1.14.12</version>
</dependency>
```

Then add the relocation inside the shade plugin's `<relocations>` block:

```xml
<relocation>
  <pattern>net.bytebuddy</pattern>
  <shadedPattern>agent.shaded.bytebuddy</shadedPattern>
</relocation>
```

Rebuild and verify the relocation:

```bash
mvn package -DskipTests
jar -tf target/jvm-profiler-agent-1.0.0-SNAPSHOT.jar | grep "agent/shaded/bytebuddy" | head -5
```

You must see entries like `agent/shaded/bytebuddy/agent/builder/AgentBuilder.class`.
If you see `net/bytebuddy/` without the prefix — stop and fix the pom.xml before continuing.

---

## Step 2 — New Data Models

### 2.1 EndpointSample

A raw, per-request measurement. Written on the request thread, consumed by the aggregator.

`src/main/java/agent/model/EndpointSample.java`

```java
package agent.model;

/**
 * A single HTTP request measurement captured by the DispatcherServlet advice.
 *
 * This is intentionally minimal — only raw numbers, no computed statistics.
 * Statistics (avg, p95, etc.) are computed by EndpointAggregator on the
 * aggregation daemon thread, not here on the request thread.
 */
public record EndpointSample(
    /** HTTP method — GET, POST, PUT, DELETE, etc. */
    String method,

    /** Request path — e.g. "/api/users", "/hello" */
    String path,

    /** How long the request took in milliseconds */
    long latencyMs,

    /** Heap bytes used BEFORE the request (captured at entry) */
    long heapBeforeBytes,

    /** Heap bytes used AFTER the request (captured at exit) */
    long heapAfterBytes,

    /** When the request completed — milliseconds since epoch */
    long timestampMs
) {
    /** Convenience: heap change during this request (positive = allocated, negative = freed by GC) */
    public long heapDeltaBytes() {
        return heapAfterBytes - heapBeforeBytes;
    }
}
```

### 2.2 EndpointStats

The computed statistics for one endpoint, built by the aggregator.

`src/main/java/agent/model/EndpointStats.java`

```java
package agent.model;

/**
 * Aggregated performance statistics for a single HTTP endpoint.
 *
 * Built by EndpointAggregator from a batch of EndpointSamples.
 * Exposed via GET /profiler/endpoints.
 */
public record EndpointStats(
    String method,
    String path,
    long   requestCount,
    double avgLatencyMs,
    double p95LatencyMs,
    long   maxLatencyMs,
    long   avgHeapDeltaBytes,

    /**
     * Requests per second — computed over the most recent aggregation window.
     * Used by the AdaptiveSamplingController in Phase 4.
     */
    double currentRps
) {}
```

### 2.3 BeanMemoryInfo

`src/main/java/agent/model/BeanMemoryInfo.java`

```java
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
```

---

## Step 3 — Update CollectorRegistry

Add the new buffers and state to `CollectorRegistry.java`:

```java
package agent.core;

import agent.buffer.RingBuffer;
import agent.model.BeanMemoryInfo;
import agent.model.EndpointSample;
import agent.model.GcEvent;
import agent.model.HeapSnapshot;

import java.lang.instrument.Instrumentation;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class CollectorRegistry {

    // ── Phase 1 buffers ───────────────────────────────────────────────────
    private final RingBuffer<HeapSnapshot> heapBuffer;
    private final RingBuffer<GcEvent>      gcBuffer;
    private final AgentSelfMetrics         selfMetrics;

    // ── Phase 2 additions ─────────────────────────────────────────────────

    /**
     * Ring buffer for raw endpoint measurements.
     * Written by DispatcherServlet advice (Tier 1), drained by EndpointAggregator.
     */
    private final RingBuffer<EndpointSample> endpointBuffer;

    /**
     * Latest bean memory rankings — updated every 30s by BeanMemoryMapper.
     * CopyOnWriteArrayList is used because reads (HTTP thread) and writes
     * (aggregation thread) happen concurrently. Writes are rare (every 30s),
     * reads are frequent.
     */
    private final CopyOnWriteArrayList<BeanMemoryInfo> beanMemoryRanking;

    /**
     * The JVM Instrumentation object — passed from premain().
     * Needed by BeanMemoryMapper to call Instrumentation.getObjectSize().
     */
    private volatile Instrumentation instrumentation;

    /**
     * Current requests per second across all endpoints.
     * Written by EndpointAggregator, read by AdaptiveSamplingController (Phase 4).
     */
    private volatile double currentRps = 0.0;

    public CollectorRegistry() {
        this.heapBuffer         = new RingBuffer<>(1000);
        this.gcBuffer           = new RingBuffer<>(500);
        this.endpointBuffer     = new RingBuffer<>(2000);
        this.selfMetrics        = new AgentSelfMetrics();
        this.beanMemoryRanking  = new CopyOnWriteArrayList<>();
    }

    // ── Getters ───────────────────────────────────────────────────────────
    public RingBuffer<HeapSnapshot>   heapBuffer()          { return heapBuffer; }
    public RingBuffer<GcEvent>        gcBuffer()            { return gcBuffer; }
    public RingBuffer<EndpointSample> endpointBuffer()      { return endpointBuffer; }
    public AgentSelfMetrics           selfMetrics()         { return selfMetrics; }
    public List<BeanMemoryInfo>       beanMemoryRanking()   { return Collections.unmodifiableList(beanMemoryRanking); }
    public double                     getCurrentRps()       { return currentRps; }
    public Instrumentation            getInstrumentation()  { return instrumentation; }

    // ── Setters ───────────────────────────────────────────────────────────
    public void setInstrumentation(Instrumentation inst)    { this.instrumentation = inst; }
    public void setCurrentRps(double rps)                   { this.currentRps = rps; }
    public void updateBeanRanking(List<BeanMemoryInfo> ranking) {
        beanMemoryRanking.clear();
        beanMemoryRanking.addAll(ranking);
    }
}
```

---

## Step 4 — DispatcherServlet Advice

This is the most critical class in Phase 2. Read every comment.

`src/main/java/agent/collector/spring/DispatcherServletAdvice.java`

```java
package agent.collector.spring;

import agent.buffer.RingBuffer;
import agent.model.EndpointSample;

import net.bytebuddy.asm.Advice;
import javax.servlet.http.HttpServletRequest;
import java.lang.management.ManagementFactory;

/**
 * Byte Buddy advice injected into DispatcherServlet.doDispatch().
 *
 * <h2>How Byte Buddy @Advice works</h2>
 * This class is NOT instantiated at runtime. Byte Buddy reads its bytecode
 * at agent startup and copies the instructions from onEnter() and onExit()
 * directly into DispatcherServlet.doDispatch(). It is as if you had written
 * this timing code inside doDispatch() yourself.
 *
 * Because the methods are inlined into the target, they run on the request
 * thread — Tier 1. Rules apply:
 *   - No logging
 *   - Minimal allocation
 *   - No blocking
 *
 * <h2>Passing state between enter and exit</h2>
 * @Advice.OnMethodEnter returns a value. That value is passed to
 * @Advice.OnMethodExit as a parameter annotated with @Advice.Enter.
 * We use this to pass the start timestamp and pre-request heap size.
 *
 * <h2>Static fields in advice</h2>
 * Byte Buddy advice methods must be static. To access the ring buffer,
 * we use a package-private static field that AgentMain sets after building
 * the agent. This is the standard pattern for Byte Buddy advice.
 */
public final class DispatcherServletAdvice {

    /**
     * The ring buffer where we write EndpointSamples.
     * Set by SpringInstrumentation before the AgentBuilder is installed.
     * Must be static because advice methods must be static.
     */
    static RingBuffer<EndpointSample> endpointBuffer;

    /**
     * Called when DispatcherServlet.doDispatch() is entered.
     *
     * @param request the HttpServletRequest argument of doDispatch()
     * @return a long[] containing [startNanos, heapBeforeBytes]
     *         This array is passed to onExit() via @Advice.Enter.
     */
    @Advice.OnMethodEnter
    public static long[] onEnter(
            @Advice.Argument(0) HttpServletRequest request) {

        // Capture start time in nanoseconds for precise latency calculation
        long startNs = System.nanoTime();

        // Capture heap before request — this lets us measure heap delta per request
        long heapBefore = ManagementFactory.getMemoryMXBean()
            .getHeapMemoryUsage().getUsed();

        return new long[]{ startNs, heapBefore };
    }

    /**
     * Called when DispatcherServlet.doDispatch() exits (normally or via exception).
     *
     * @param request  the same HttpServletRequest from onEnter
     * @param entered  the long[] returned by onEnter — contains [startNs, heapBefore]
     */
    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(
            @Advice.Argument(0) HttpServletRequest request,
            @Advice.Enter       long[] entered) {

        // Safety check — if onEnter failed for any reason, entered may be null
        if (entered == null || endpointBuffer == null) return;

        long startNs     = entered[0];
        long heapBefore  = entered[1];
        long latencyMs   = (System.nanoTime() - startNs) / 1_000_000;
        long heapAfter   = ManagementFactory.getMemoryMXBean()
            .getHeapMemoryUsage().getUsed();

        // Build the sample — record() is used because it is a value object
        EndpointSample sample = new EndpointSample(
            request.getMethod(),
            request.getRequestURI(),
            latencyMs,
            heapBefore,
            heapAfter,
            System.currentTimeMillis()
        );

        // Write to ring buffer — lock-free, allocation-free write path
        endpointBuffer.write(sample);
    }
}
```

---

## Step 5 — BeanMemoryMapper

`src/main/java/agent/collector/spring/BeanMemoryMapper.java`

```java
package agent.collector.spring;

import agent.model.BeanMemoryInfo;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.context.ApplicationContext;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.logging.Logger;

/**
 * Maps Spring bean instances to estimated heap footprints.
 *
 * <h2>Strategy</h2>
 * 1. Get all singleton bean names from the ApplicationContext.
 * 2. For each bean, call Instrumentation.getObjectSize() — this gives
 *    the "shallow" size (the object header + fields, not what fields point to).
 * 3. Walk the object graph up to 3 levels deep using reflection to get a
 *    deeper estimate.
 * 4. Cache results for 30 seconds — the scan is moderately expensive.
 *
 * <h2>Why approximate?</h2>
 * Perfectly accurate heap attribution per bean is impossible without a
 * full heap dump and object ownership graph. Our goal is to rank beans
 * by relative memory usage, not to give exact byte counts. Approximate
 * is good enough for that.
 *
 * <h2>Thread safety</h2>
 * This class runs on the aggregation daemon thread. Results are written
 * to CollectorRegistry.beanMemoryRanking via CopyOnWriteArrayList.
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
    private ApplicationContext   currentContext   = null;

    public BeanMemoryMapper(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
    }

    /**
     * Called by SpringContextListener when ApplicationContext.refresh() completes.
     * We store the context for use in subsequent scans.
     */
    public void onContextRefreshed(ApplicationContext context) {
        this.currentContext   = context;
        this.cacheExpiresAtMs = 0L;  // Invalidate cache — context changed
        log.info("BeanMemoryMapper: ApplicationContext registered with "
            + context.getBeanDefinitionCount() + " beans");
    }

    /**
     * Returns the top N beans by estimated memory, using cache if fresh.
     * Call from the aggregation daemon thread.
     */
    public List<BeanMemoryInfo> getTopBeans() {
        if (currentContext == null) return List.of();

        if (System.currentTimeMillis() < cacheExpiresAtMs) {
            return cachedRanking;
        }

        // Cache expired — rescan
        cachedRanking    = scanBeans(currentContext);
        cacheExpiresAtMs = System.currentTimeMillis() + CACHE_DURATION_MS;
        return cachedRanking;
    }

    /**
     * Scans all singleton beans and builds a ranked list.
     * This is moderately expensive — runs on aggregation thread, not hot path.
     */
    private List<BeanMemoryInfo> scanBeans(ApplicationContext context) {
        long startMs = System.currentTimeMillis();
        List<BeanMemoryInfo> results = new ArrayList<>();

        ListableBeanFactory factory = context;
        String[] names = factory.getBeanDefinitionNames();

        for (String name : names) {
            try {
                Object bean  = context.getBean(name);
                String scope = context.getBeanDefinition(name).getScope();
                if (scope == null || scope.isBlank()) scope = "singleton";

                long estimated = estimateSize(bean);

                results.add(new BeanMemoryInfo(
                    name,
                    bean.getClass().getName(),
                    scope,
                    estimated
                ));
            } catch (Exception e) {
                // Some beans throw on getBean() — skip them silently
                // (e.g. factory beans, abstract beans, scope proxies)
            }
        }

        // Sort descending by estimated size, take top N
        results.sort(Comparator.comparingLong(BeanMemoryInfo::estimatedBytes).reversed());
        List<BeanMemoryInfo> top = results.stream().limit(TOP_N_BEANS).toList();

        log.fine(() -> "BeanMemoryMapper scanned " + names.length + " beans in "
            + (System.currentTimeMillis() - startMs) + "ms");

        return top;
    }

    /**
     * Estimates the heap size of an object by walking its field graph
     * up to MAX_GRAPH_DEPTH levels deep.
     *
     * Uses an IdentityHashMap to avoid counting the same object twice
     * (important for objects with circular references or shared references).
     */
    private long estimateSize(Object root) {
        if (root == null) return 0L;

        IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();
        return walkGraph(root, 0, visited);
    }

    private long walkGraph(Object obj, int depth, IdentityHashMap<Object, Boolean> visited) {
        if (obj == null) return 0L;
        if (visited.containsKey(obj)) return 0L;  // Already counted
        if (depth > MAX_GRAPH_DEPTH) return 0L;    // Stop at depth limit

        visited.put(obj, Boolean.TRUE);

        // Shallow size of this object
        long size = instrumentation.getObjectSize(obj);

        // Only walk deeper if not at the limit
        if (depth < MAX_GRAPH_DEPTH) {
            Class<?> cls = obj.getClass();

            // Walk non-primitive, non-null fields
            while (cls != null && cls != Object.class) {
                for (Field field : cls.getDeclaredFields()) {
                    // Skip static fields, primitive fields, and synthetic fields
                    if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                    if (field.getType().isPrimitive()) continue;
                    if (field.isSynthetic()) continue;

                    try {
                        field.setAccessible(true);
                        Object value = field.get(obj);
                        size += walkGraph(value, depth + 1, visited);
                    } catch (Exception e) {
                        // InaccessibleObjectException (Java modules) — skip this field
                    }
                }
                cls = cls.getSuperclass();
            }
        }

        return size;
    }
}
```

---

## Step 6 — EndpointAggregator

`src/main/java/agent/collector/spring/EndpointAggregator.java`

```java
package agent.collector.spring;

import agent.buffer.RingBuffer;
import agent.model.EndpointSample;
import agent.model.EndpointStats;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Drains the endpoint ring buffer and computes per-endpoint statistics.
 *
 * Runs on the aggregation daemon thread every few seconds.
 * Never runs on the request thread.
 *
 * <h2>P95 calculation</h2>
 * P95 (95th percentile) means: 95% of requests completed in this many
 * milliseconds or less. It is more useful than average latency because
 * it shows the worst experience most users get, without being skewed
 * by rare extreme outliers.
 *
 * To compute p95: sort all latencies, take the value at index (n * 0.95).
 */
public final class EndpointAggregator {

    private final RingBuffer<EndpointSample> buffer;

    // Keeps a rolling window of all samples seen — used for p95 calculation
    // Key: "METHOD /path", Value: list of latencies in the window
    private final Map<String, List<Long>> latencyWindows = new LinkedHashMap<>();

    // How many samples to keep per endpoint for statistical accuracy
    private static final int WINDOW_SIZE = 200;

    public EndpointAggregator(RingBuffer<EndpointSample> buffer) {
        this.buffer = buffer;
    }

    /**
     * Drains new samples from the buffer and computes updated stats.
     * Returns the top 20 endpoints sorted by average latency descending.
     *
     * Call this from the aggregation daemon thread.
     */
    public List<EndpointStats> aggregate() {
        // Drain new samples into a local list
        List<EndpointSample> newSamples = new ArrayList<>();
        buffer.drainTo(newSamples);

        if (newSamples.isEmpty() && latencyWindows.isEmpty()) {
            return List.of();
        }

        // Group new samples by endpoint key
        Map<String, List<EndpointSample>> grouped = newSamples.stream()
            .collect(Collectors.groupingBy(s -> s.method() + " " + s.path()));

        // Merge into rolling windows
        for (Map.Entry<String, List<EndpointSample>> entry : grouped.entrySet()) {
            String key = entry.getKey();
            latencyWindows.computeIfAbsent(key, k -> new ArrayList<>());

            List<Long> window = latencyWindows.get(key);
            for (EndpointSample sample : entry.getValue()) {
                window.add(sample.latencyMs());
                // Keep window at WINDOW_SIZE — remove oldest if over limit
                if (window.size() > WINDOW_SIZE) {
                    window.remove(0);
                }
            }
        }

        // Compute stats per endpoint
        long windowMs = 5000L; // Aggregation window for RPS calculation
        List<EndpointStats> statsList = new ArrayList<>();

        for (Map.Entry<String, List<Long>> entry : latencyWindows.entrySet()) {
            String key = entry.getKey();
            List<Long> latencies = entry.getValue();
            if (latencies.isEmpty()) continue;

            String[] parts  = key.split(" ", 2);
            String method   = parts[0];
            String path     = parts.length > 1 ? parts[1] : "unknown";

            // Count samples for this endpoint from the new batch
            long newCount = grouped.getOrDefault(key, List.of()).size();

            // Compute statistics
            List<Long> sorted   = latencies.stream().sorted().toList();
            double avgLatency   = latencies.stream().mapToLong(l -> l).average().orElse(0);
            long   maxLatency   = latencies.stream().mapToLong(l -> l).max().orElse(0);
            double p95Latency   = percentile(sorted, 95);

            // Average heap delta for new samples in this window
            long avgHeapDelta   = grouped.getOrDefault(key, List.of()).stream()
                .mapToLong(EndpointSample::heapDeltaBytes)
                .average()
                .map(Math::round)
                .orElse(0L);

            // RPS = new requests in this window / window duration in seconds
            double rps = newCount / (windowMs / 1000.0);

            statsList.add(new EndpointStats(
                method, path,
                latencies.size(),
                Math.round(avgLatency * 100.0) / 100.0,
                Math.round(p95Latency * 100.0) / 100.0,
                maxLatency,
                avgHeapDelta,
                Math.round(rps * 100.0) / 100.0
            ));
        }

        // Sort by average latency descending — slowest endpoints first
        statsList.sort(Comparator.comparingDouble(EndpointStats::avgLatencyMs).reversed());

        return statsList.stream().limit(20).toList();
    }

    /**
     * Computes the Nth percentile of a sorted list of longs.
     *
     * @param sorted the list sorted ascending
     * @param n      the percentile (0–100)
     * @return the value at that percentile
     */
    private double percentile(List<Long> sorted, int n) {
        if (sorted.isEmpty()) return 0.0;
        int index = (int) Math.ceil((n / 100.0) * sorted.size()) - 1;
        return sorted.get(Math.max(0, index));
    }
}
```

---

## Step 7 — SpringContextListener

This is the glue between the Spring ApplicationContext and our collectors.
Byte Buddy calls this when `ApplicationContext.refresh()` completes.

`src/main/java/agent/collector/spring/SpringContextListener.java`

```java
package agent.collector.spring;

import agent.core.CollectorRegistry;
import org.springframework.context.ApplicationContext;
import java.util.logging.Logger;

/**
 * Receives the ApplicationContext reference from the Byte Buddy advice
 * and routes it to the appropriate collectors.
 *
 * This class is NOT an advice class — it is a normal class called FROM
 * the advice class (ApplicationContextAdvice).
 *
 * Separation of concerns: the advice class handles Byte Buddy plumbing,
 * this class handles what to do with the context.
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
     * The context is now fully initialized and all beans are available.
     *
     * @param context the fully initialized ApplicationContext
     */
    public void onContextRefreshed(ApplicationContext context) {
        log.info("Spring ApplicationContext detected — beans: "
            + context.getBeanDefinitionCount());
        beanMapper.onContextRefreshed(context);
    }
}
```

---

## Step 8 — ApplicationContextAdvice

`src/main/java/agent/collector/spring/ApplicationContextAdvice.java`

```java
package agent.collector.spring;

import net.bytebuddy.asm.Advice;
import org.springframework.context.ApplicationContext;

/**
 * Byte Buddy advice injected into AbstractApplicationContext.refresh().
 *
 * When refresh() exits, the Spring ApplicationContext is fully initialized.
 * We capture the context reference and pass it to SpringContextListener
 * for bean scanning.
 *
 * <h2>Why AbstractApplicationContext and not ApplicationContext?</h2>
 * AbstractApplicationContext is the concrete base class that implements
 * refresh(). The interface ApplicationContext does not define refresh().
 * We intercept the base class so all Spring context implementations
 * (AnnotationConfigApplicationContext, WebApplicationContext, etc.)
 * are covered by a single interception.
 */
public final class ApplicationContextAdvice {

    /**
     * Set by SpringInstrumentation before the AgentBuilder is installed.
     * Static because Byte Buddy advice methods must be static.
     */
    static SpringContextListener listener;

    /**
     * Called after AbstractApplicationContext.refresh() completes.
     *
     * @Advice.This gives us the `this` reference of the intercepted method.
     * Since we intercept AbstractApplicationContext.refresh(), `this` is
     * the ApplicationContext itself.
     */
    @Advice.OnMethodExit
    public static void onRefreshComplete(@Advice.This Object context) {
        if (listener == null) return;

        // context is AbstractApplicationContext which implements ApplicationContext
        if (context instanceof ApplicationContext ctx) {
            try {
                listener.onContextRefreshed(ctx);
            } catch (Exception e) {
                // Never propagate — cannot crash the Spring startup sequence
            }
        }
    }
}
```

---

## Step 9 — SpringInstrumentation

This is where Byte Buddy's AgentBuilder is configured. This class wires
everything together and installs the bytecode transformers.

`src/main/java/agent/collector/spring/SpringInstrumentation.java`

```java
package agent.collector.spring;

import agent.core.AgentConfig;
import agent.core.CollectorRegistry;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
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

    public SpringInstrumentation(Instrumentation jvmInstrumentation,
                                 CollectorRegistry registry,
                                 AgentConfig config) {
        this.jvmInstrumentation = jvmInstrumentation;
        this.registry           = registry;
        this.config             = config;
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

        // ApplicationContextAdvice needs the Spring context listener
        BeanMemoryMapper mapper = new BeanMemoryMapper(
            jvmInstrumentation);
        SpringContextListener listener = new SpringContextListener(
            registry, mapper);
        ApplicationContextAdvice.listener = listener;

        // ── Build and install the AgentBuilder ────────────────────────────
        new AgentBuilder.Default()

            /*
             * Ignore certain packages to avoid instrumenting the agent itself,
             * the JDK internals, and other packages that should not be touched.
             * This is important — without this, Byte Buddy may try to instrument
             * its own shaded classes and cause infinite recursion.
             */
            .ignore(ElementMatchers.nameStartsWith("agent.shaded.")
                .or(ElementMatchers.nameStartsWith("java."))
                .or(ElementMatchers.nameStartsWith("javax."))
                .or(ElementMatchers.nameStartsWith("jdk."))
                .or(ElementMatchers.nameStartsWith("sun.")))

            /*
             * Transformation 1: Intercept DispatcherServlet.doDispatch()
             *
             * ElementMatchers.named("doDispatch") matches the method name.
             * ElementMatchers.namedIgnoreCase("DispatcherServlet") matches the class.
             *
             * We use namedIgnoreCase for the class and named for the method to be
             * precise. The full class name also works:
             * ElementMatchers.named("org.springframework.web.servlet.DispatcherServlet")
             */
            .type(ElementMatchers.named(
                "org.springframework.web.servlet.DispatcherServlet"))
            .transform((builder, typeDescription, classLoader, module, domain) ->
                builder.visit(
                    Advice.to(DispatcherServletAdvice.class)
                          .on(ElementMatchers.named("doDispatch"))
                )
            )

            /*
             * Transformation 2: Intercept AbstractApplicationContext.refresh()
             *
             * We target the abstract base class so all Spring context
             * implementations are covered automatically.
             */
            .type(ElementMatchers.named(
                "org.springframework.context.support.AbstractApplicationContext"))
            .transform((builder, typeDescription, classLoader, module, domain) ->
                builder.visit(
                    Advice.to(ApplicationContextAdvice.class)
                          .on(ElementMatchers.named("refresh"))
                )
            )

            // Install — activates all transformations
            .installOn(jvmInstrumentation);

        log.info("SpringInstrumentation installed — DispatcherServlet and "
            + "ApplicationContext interception active");
    }
}
```

---

## Step 10 — Update AgentMain

Add SpringInstrumentation to the startup sequence:

```java
package agent.core;

import agent.collector.gc.GcListener;
import agent.collector.heap.HeapSampler;
import agent.collector.spring.SpringInstrumentation;
import agent.http.ProfilerHttpServer;

import java.lang.instrument.Instrumentation;
import java.util.logging.Logger;

public final class AgentMain {

    private static final Logger log = Logger.getLogger(AgentMain.class.getName());

    public static void premain(String agentArgs, Instrumentation instrumentation) {
        log.info("JVM Profiler Agent starting...");

        try {
            AgentConfig config = AgentConfig.load(agentArgs);
            CollectorRegistry registry = new CollectorRegistry();

            // Store instrumentation for use by BeanMemoryMapper
            registry.setInstrumentation(instrumentation);

            // Phase 1 — core collection
            new HeapSampler(registry.heapBuffer(), registry.selfMetrics(), config).start();
            new GcListener(registry.gcBuffer()).attach();

            // Phase 2 — Spring instrumentation (install BEFORE app starts)
            // This must be called before Spring loads DispatcherServlet
            new SpringInstrumentation(instrumentation, registry, config).install();

            // Start HTTP server
            new ProfilerHttpServer(registry, config).start();

            log.info("JVM Profiler Agent started — port=" + config.getHttpPort());

        } catch (Exception e) {
            log.severe("JVM Profiler Agent failed to start: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void agentmain(String agentArgs, Instrumentation instrumentation) {
        premain(agentArgs, instrumentation);
    }
}
```

---

## Step 11 — Update the HTTP Server

Add the new Phase 2 routes to `ProfilerHttpServer.java`. Add these inside
`registerRoutes()` after the existing routes:

```java
// ── GET /profiler/endpoints ───────────────────────────────────────────
app.get("/profiler/endpoints", ctx -> {
    // Aggregate on-demand when the endpoint is called
    EndpointAggregator aggregator = new EndpointAggregator(registry.endpointBuffer());
    List<EndpointStats> stats = aggregator.aggregate();

    // Update the RPS gauge on the registry for Phase 4 adaptive sampler
    double totalRps = stats.stream()
        .mapToDouble(EndpointStats::currentRps)
        .sum();
    registry.setCurrentRps(totalRps);

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("endpointCount", stats.size());
    response.put("totalRps",      Math.round(totalRps * 100.0) / 100.0);
    response.put("endpoints",     stats);
    ctx.json(response);
});

// ── GET /profiler/beans ───────────────────────────────────────────────
app.get("/profiler/beans", ctx -> {
    List<BeanMemoryInfo> beans = registry.beanMemoryRanking();

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("beanCount",    beans.size());
    response.put("beans",        beans);
    ctx.json(response);
});
```

You will also need to add imports at the top of ProfilerHttpServer:

```java
import agent.collector.spring.EndpointAggregator;
import agent.model.BeanMemoryInfo;
import agent.model.EndpointStats;
```

---

## Step 12 — Add the Aggregation Daemon

Right now, `EndpointAggregator` is called on-demand in the HTTP route.
That works for Phase 2, but it means the RPS gauge and bean ranking are
only updated when someone hits the endpoint. Add a background daemon to
update them periodically.

`src/main/java/agent/core/AggregationDaemon.java`

```java
package agent.core;

import agent.collector.spring.BeanMemoryMapper;
import agent.collector.spring.EndpointAggregator;
import agent.model.EndpointStats;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Background daemon that periodically aggregates collected metrics.
 *
 * Runs every 5 seconds. Drains the endpoint buffer, computes stats,
 * updates the registry, and refreshes the bean ranking cache.
 *
 * This is Tier 2 — moderate logging allowed, but not on every tick.
 */
public final class AggregationDaemon {

    private static final Logger log =
        Logger.getLogger(AggregationDaemon.class.getName());

    private static final long INTERVAL_SECONDS = 5L;

    private final CollectorRegistry registry;
    private final EndpointAggregator endpointAggregator;
    private final BeanMemoryMapper   beanMapper;

    public AggregationDaemon(CollectorRegistry registry,
                             EndpointAggregator endpointAggregator,
                             BeanMemoryMapper beanMapper) {
        this.registry            = registry;
        this.endpointAggregator  = endpointAggregator;
        this.beanMapper          = beanMapper;
    }

    public void start() {
        ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "profiler-aggregation-daemon");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });

        scheduler.scheduleAtFixedRate(
            this::aggregate,
            INTERVAL_SECONDS,
            INTERVAL_SECONDS,
            TimeUnit.SECONDS
        );

        log.info("AggregationDaemon started — interval=" + INTERVAL_SECONDS + "s");
    }

    private void aggregate() {
        try {
            // ── Aggregate endpoint samples ────────────────────────────────
            List<EndpointStats> stats = endpointAggregator.aggregate();

            // Update total RPS on registry (used by Phase 4 adaptive sampler)
            double totalRps = stats.stream()
                .mapToDouble(EndpointStats::currentRps)
                .sum();
            registry.setCurrentRps(totalRps);

            // ── Refresh bean memory ranking (uses internal 30s cache) ─────
            var beans = beanMapper.getTopBeans();
            registry.updateBeanRanking(beans);

        } catch (Exception e) {
            // Never let aggregation failures propagate and kill the daemon
            log.warning("Aggregation cycle failed: " + e.getMessage());
        }
    }
}
```

Update `AgentMain` to start the aggregation daemon. Add after
`SpringInstrumentation.install()`:

```java
// Phase 2 — aggregation daemon
BeanMemoryMapper beanMapper = new BeanMemoryMapper(instrumentation);
EndpointAggregator endpointAggregator =
    new EndpointAggregator(registry.endpointBuffer());
new AggregationDaemon(registry, endpointAggregator, beanMapper).start();
```

---

## Step 13 — Build and Test

```bash
mvn package -DskipTests

# Verify all shade relocations
jar -tf target/jvm-profiler-agent-1.0.0-SNAPSHOT.jar | grep "agent/shaded/bytebuddy" | wc -l
# Should show > 100 entries
```

Start the demo app with the agent:

```bash
java \
  -javaagent:target/jvm-profiler-agent-1.0.0-SNAPSHOT.jar \
  -jar demo-app/target/jvm-profiler-demo-app-1.0.0-SNAPSHOT.jar
```

You should see this additional log line during startup:
```
INFO agent.collector.spring.SpringInstrumentation: SpringInstrumentation installed
INFO agent.collector.spring.BeanMemoryMapper: ApplicationContext registered with 142 beans
```

Generate some traffic:
```bash
for i in {1..20}; do curl -s http://localhost:8080/hello > /dev/null; done
for i in {1..10}; do curl -s http://localhost:8080/slow > /dev/null; done
```

Then check the endpoints:
```bash
curl -s http://localhost:7070/profiler/endpoints | python3 -m json.tool
curl -s http://localhost:7070/profiler/beans | python3 -m json.tool
```

Expected `/profiler/endpoints` output:
```json
{
  "endpointCount": 2,
  "totalRps": 0.6,
  "endpoints": [
    {
      "method": "GET",
      "path": "/slow",
      "requestCount": 10,
      "avgLatencyMs": 203.4,
      "p95LatencyMs": 208.0,
      "maxLatencyMs": 215,
      "avgHeapDeltaBytes": 12345,
      "currentRps": 0.2
    },
    {
      "method": "GET",
      "path": "/hello",
      "requestCount": 20,
      "avgLatencyMs": 2.1,
      "p95LatencyMs": 4.0,
      "maxLatencyMs": 12,
      "avgHeapDeltaBytes": 1024,
      "currentRps": 0.4
    }
  ]
}
```

---

## Step 14 — Unit Tests

### 14.1 EndpointAggregator Tests

`src/test/java/agent/collector/spring/EndpointAggregatorTest.java`

```java
package agent.collector.spring;

import agent.buffer.RingBuffer;
import agent.model.EndpointSample;
import agent.model.EndpointStats;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class EndpointAggregatorTest {

    @Test
    void returnsEmptyWhenNoSamples() {
        RingBuffer<EndpointSample> buffer = new RingBuffer<>(100);
        EndpointAggregator agg = new EndpointAggregator(buffer);
        assertTrue(agg.aggregate().isEmpty());
    }

    @Test
    void computesAverageLatency() {
        RingBuffer<EndpointSample> buffer = new RingBuffer<>(100);
        buffer.write(sample("GET", "/hello", 10));
        buffer.write(sample("GET", "/hello", 20));
        buffer.write(sample("GET", "/hello", 30));

        EndpointAggregator agg = new EndpointAggregator(buffer);
        List<EndpointStats> stats = agg.aggregate();

        assertEquals(1, stats.size());
        assertEquals(20.0, stats.get(0).avgLatencyMs(), 0.1);
    }

    @Test
    void separatesEndpointsByPathAndMethod() {
        RingBuffer<EndpointSample> buffer = new RingBuffer<>(100);
        buffer.write(sample("GET",  "/hello", 10));
        buffer.write(sample("POST", "/hello", 50));
        buffer.write(sample("GET",  "/slow",  200));

        EndpointAggregator agg = new EndpointAggregator(buffer);
        List<EndpointStats> stats = agg.aggregate();

        assertEquals(3, stats.size());
    }

    @Test
    void sortsByAverageLatencyDescending() {
        RingBuffer<EndpointSample> buffer = new RingBuffer<>(100);
        buffer.write(sample("GET", "/fast", 5));
        buffer.write(sample("GET", "/slow", 300));

        EndpointAggregator agg = new EndpointAggregator(buffer);
        List<EndpointStats> stats = agg.aggregate();

        // Slowest endpoint should be first
        assertEquals("/slow", stats.get(0).path());
        assertEquals("/fast", stats.get(1).path());
    }

    @Test
    void computesMaxLatency() {
        RingBuffer<EndpointSample> buffer = new RingBuffer<>(100);
        buffer.write(sample("GET", "/api", 10));
        buffer.write(sample("GET", "/api", 500));
        buffer.write(sample("GET", "/api", 50));

        EndpointAggregator agg = new EndpointAggregator(buffer);
        List<EndpointStats> stats = agg.aggregate();

        assertEquals(500L, stats.get(0).maxLatencyMs());
    }

    // Helper method — builds a minimal EndpointSample for testing
    private EndpointSample sample(String method, String path, long latencyMs) {
        return new EndpointSample(method, path, latencyMs, 0L, 0L,
            System.currentTimeMillis());
    }
}
```

### 14.2 Run Tests

```bash
mvn test
```

All tests must pass. If `EndpointAggregatorTest` fails, the most common cause
is the buffer not being drained before calling `aggregate()`. Check that
`buffer.drainTo()` is called inside `aggregate()`.

---

## Step 15 — Phase 2 Checklist

- [ ] `mvn package` succeeds
- [ ] `jar -tf agent.jar | grep agent/shaded/bytebuddy` shows entries
- [ ] Agent attaches to demo app with NO `ClassNotFoundException` or `ClassCircularityError`
- [ ] `BeanMemoryMapper: ApplicationContext registered` log line appears
- [ ] After 20 requests: `GET /profiler/endpoints` shows correct paths and latencies
- [ ] `GET /profiler/beans` returns ≥ 10 beans with `estimatedBytes` > 0
- [ ] Agent JAR does not appear in `/profiler/beans` output
- [ ] All unit tests pass
- [ ] `mvn verify` green on CI

```bash
git checkout develop
git merge --no-ff phase/2-spring-integration \
  -m "Merge Phase 2: Spring Boot Integration"
git tag phase-2-complete
git push origin develop --tags
git checkout -b phase/3-persistence
```

---

## Troubleshooting Phase 2

**Problem:** `ClassNotFoundException: net.bytebuddy.agent.builder.AgentBuilder`
**Cause:** Byte Buddy is not shaded or the relocation is wrong.
**Fix:** Check pom.xml `<relocation>` for `net.bytebuddy → agent.shaded.bytebuddy`.
Run `jar -tf agent.jar | grep "agent/shaded/bytebuddy/agent/builder"` — must show results.

---

**Problem:** `GET /profiler/beans` returns an empty list
**Cause:** `ApplicationContextAdvice` did not fire — Spring may have loaded
`AbstractApplicationContext` before the agent installed its transformation.
**Fix:** Ensure `SpringInstrumentation.install()` is called in `premain()`
BEFORE the application's `main()` runs. This is guaranteed when using
`-javaagent:` at startup — premain always runs first.

---

**Problem:** `GET /profiler/endpoints` returns empty after making requests
**Cause:** DispatcherServlet advice did not fire.
**Fix:** Check that the demo app is using Spring MVC (not Spring WebFlux).
Spring WebFlux uses a different dispatcher class (`DispatcherHandler`, not
`DispatcherServlet`). Check `demo-app/pom.xml` — it should depend on
`spring-boot-starter-web`, not `spring-boot-starter-webflux`.

---

**Problem:** Spring Boot fails to start with `ClassCircularityError`
**Cause:** The Byte Buddy `ignore()` rules are not excluding the right packages.
**Fix:** Add the failing class's package to the `ignore()` chain in
`SpringInstrumentation.install()`.

---

*End of Phase 2.*
*Next: [Phase 3 — Persistence & History](./PHASE-3-PERSISTENCE.md)*
