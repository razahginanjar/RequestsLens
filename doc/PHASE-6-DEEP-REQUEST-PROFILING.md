# Phase 6 — Deep Request Profiling (Method-Level CPU, Memory & Allocation)
## Full Implementation Guide

> **Goal by end of this phase:** For a selected API request, the agent produces a
> **per-method call tree** showing, for every application method invoked under that
> request: wall time, CPU time, allocated bytes, and the **types of objects
> allocated** inside it. In parallel, a low-overhead **always-on sampling profiler**
> builds per-endpoint flame-graph data. Both are exposed via new HTTP endpoints and
> dashboard panels. Because method instrumentation has real overhead, this phase
> ships with a **mandatory overhead benchmark** and **integration tests**.
>
> **Estimated time:** 8–10 days
> **Branch:** `phase/6-deep-request-profiling`
> **Prerequisite:** Phases 1–5 (HTTP API + dashboard) complete and the Spring
> classloader fixes in place (see "Advice rules" below).

---

## Before You Start

### What we are adding, in plain terms

Up to Phase 5 the agent answers *"which endpoint is slow?"* and *"how much heap is
the app using?"*. It does **not** answer the next question every developer asks:

> *"WHICH method inside `/api/orders` is slow, and what is it allocating?"*

Phase 6 answers that. When a request runs, we record a tree of the methods it
called, and for each method we capture:

- **Wall time** — elapsed nanoseconds (what the user feels).
- **CPU time** — nanoseconds the thread actually spent on-CPU (distinguishes
  "slow because computing" from "slow because waiting on I/O/locks").
- **Allocated bytes** — how much memory the method (and its callees) allocated.
- **Allocation detail** — *which object types* were allocated under the method,
  so you can see e.g. `byte[]` and `java.util.HashMap$Node` dominating a method.

### Two complementary techniques — and why we ship both

There is no single "best" way to profile. We ship two modes that answer different
questions, and the data model is shared so the dashboard renders both the same way.

| | **Sampling profiler** (always-on) | **Method tracing** (opt-in / sampled) |
|---|---|---|
| How | Periodically snapshot thread stacks | Byte Buddy advice on application methods |
| Overhead | Very low (~constant, tunable by interval) | Proportional to call volume — must be scoped |
| Accuracy | Statistical (misses short methods) | Exact per-call timing + allocation |
| Best for | "Where does CPU go overall?" (flame graph) | "Profile this one endpoint in depth" |
| Object detail | No | Yes (with JFR allocation sampling) |

Rule of thumb we will enforce in config: **sampling is on by default; method tracing
is off by default and is enabled per-package, and even then only a sampled fraction
of requests are fully traced.**

### Key JVM APIs you will use

1. **Per-thread CPU time** — `java.lang.management.ThreadMXBean`:
   - `isThreadCpuTimeSupported()`, `setThreadCpuTimeEnabled(true)`
   - `getCurrentThreadCpuTime()` → nanoseconds (or `getThreadCpuTime(id)`).
2. **Per-thread allocated bytes** — `com.sun.management.ThreadMXBean` (HotSpot;
   cast `ManagementFactory.getThreadMXBean()`):
   - `isThreadAllocatedMemorySupported()`, `setThreadAllocatedMemoryEnabled(true)`
   - `getThreadAllocatedBytes(long id)` → cumulative bytes for that thread.
   We measure a method's allocation as the **delta** of this counter between enter
   and exit. This is exact (not sampled) and very cheap.
3. **Object-type allocation detail** — **JFR** (`jdk.jfr.consumer.RecordingStream`,
   JDK 14+) streaming the `jdk.ObjectAllocationSample` event (JDK 16+). Each event
   carries the allocated `objectClass`, a `weight` (estimated bytes), and the
   `thread`. We correlate the event's thread to the method span currently executing
   on that thread (see Step 7). This gives type-level detail at low, throttled cost
   without instrumenting every `new`.

> **Why not instrument every `new`?** You *can* (advise constructors / `NEW`
> bytecodes), but it is brutally expensive and dwarfs the cost of the allocation
> itself. JFR allocation sampling is the production-grade way to get type detail.

### The advice rules we learned the hard way (carry these forward)

Method tracing inlines advice into **application** classes, loaded by a child
classloader (Spring Boot `LaunchedClassLoader`). Every rule we discovered fixing
the Spring instrumentation in earlier phases applies again — violate one and you
get `IllegalAccessError`/`NoClassDefFoundError`/silent skips:

1. **Advice static fields accessed by inlined code must be `public`** (inlined code
   runs in the app class, a different package/classloader).
2. **Advice must not reference framework/shaded types in signatures.** Take
   arguments as `Object` and use reflection, or use only JDK types. (Our
   `jakarta.servlet` parameter once got shaded to `agent.shaded.jakarta.servlet`
   and Byte Buddy refused to bind it.)
3. **Do not read advice-class non-public fields from inlined code** — even a private
   cache field triggers `IllegalAccessError`. Keep per-call state in JDK types or a
   `ThreadLocal` held in a *separate* helper class that the advice *calls* (not
   inlines).
4. **Advice must never throw into the app** — wrap bodies in `try/catch(Throwable)`.
5. **Keep an `AgentBuilder.Listener`** logging transformations + errors so a failed
   instrumentation is visible, not silent.

### Overhead is the #1 design constraint

Method instrumentation that is always-on and unscoped will tank a real application.
The design therefore enforces:

- **Scope by package** (`profiler.trace.packages`) — never instrument the JDK,
  the framework, or our own agent.
- **Gate by a thread-local "is this request being traced?" flag** — instrumented
  methods do a single `ThreadLocal` read + branch and return immediately when the
  request is not selected. (We still pay that branch on every call; the benchmark
  in Step 12 must quantify it.)
- **Sample requests** (`profiler.trace.sample.rate`) — fully trace e.g. 1 in 50
  requests, not all of them.
- **Bound the tree** (`profiler.trace.max.depth`, `profiler.trace.max.spans`) — stop
  recording beyond a depth/size cap so a pathological request cannot OOM the agent.

---

## Step 1 — New Models

`src/main/java/agent/model/MethodSpan.java`

```java
package agent.model;

import java.util.ArrayList;
import java.util.List;

/**
 * One node in a request's method call tree. Mutable while the request is in
 * flight (built on the request thread), then treated as immutable once published.
 *
 * Times are nanoseconds. selfWallNs/selfCpuNs/selfAllocBytes are this method
 * MINUS its children, computed when the span is finalized.
 */
public final class MethodSpan {
    public String className;
    public String methodName;
    public long   wallNs;
    public long   cpuNs;
    public long   allocBytes;
    public long   selfWallNs;
    public long   selfCpuNs;
    public long   selfAllocBytes;
    public int    invocations = 1;
    public final List<MethodSpan> children = new ArrayList<>();
    /** Top allocated types under this span (class name -> sampled bytes). */
    public final java.util.Map<String, Long> allocByType = new java.util.HashMap<>();
}
```

`src/main/java/agent/model/RequestTrace.java`

```java
package agent.model;

/**
 * A completed, request-scoped method call tree. Published to a ring buffer when
 * the request finishes and read by the HTTP API.
 */
public record RequestTrace(
    String     traceId,       // short unique id
    String     method,        // HTTP method
    String     path,          // request path
    long       timestampMs,
    long       totalWallNs,
    long       totalCpuNs,
    long       totalAllocBytes,
    MethodSpan root           // synthetic root = the request entry
) {}
```

`src/main/java/agent/model/FlameNode.java`

```java
package agent.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Aggregated stack node for the sampling profiler. `samples` is how many times a
 * stack passing through this frame was observed; children are keyed by frame.
 */
public final class FlameNode {
    public final String frame;            // "com.x.Foo.bar"
    public long samples;
    public final Map<String, FlameNode> children = new LinkedHashMap<>();
    public FlameNode(String frame) { this.frame = frame; }
}
```

---

## Step 2 — Configuration

Add to `AgentConfig` (follow the existing field/parse/getter pattern):

```java
// Phase 6 — deep profiling defaults
private static final boolean DEFAULT_SAMPLING_PROFILER_ENABLED = true;
private static final long    DEFAULT_SAMPLING_PROFILER_MS       = 20L;   // stack-sample interval
private static final boolean DEFAULT_TRACE_ENABLED              = false; // method tracing OFF by default
private static final String  DEFAULT_TRACE_PACKAGES             = "";    // CSV, e.g. "com.example"
private static final int     DEFAULT_TRACE_SAMPLE_RATE          = 50;    // trace 1 in N requests
private static final int     DEFAULT_TRACE_MAX_DEPTH            = 40;
private static final int     DEFAULT_TRACE_MAX_SPANS            = 5000;
private static final boolean DEFAULT_ALLOC_DETAIL_ENABLED       = true;  // JFR object-type detail
```

Properties (all overridable via file / `-D` / agent args, like existing config):

```
profiler.sampling.profiler.enabled      (default true)
profiler.sampling.profiler.interval.ms   (default 20)
profiler.trace.enabled                   (default false)
profiler.trace.packages                  (default "" — CSV of package prefixes)
profiler.trace.sample.rate               (default 50 — fully trace 1 of N requests)
profiler.trace.max.depth                 (default 40)
profiler.trace.max.spans                 (default 5000)
profiler.trace.alloc.detail.enabled      (default true)
```

Validation: clamp `interval.ms >= 5`, `sample.rate >= 1`, `max.depth >= 1`.
Add getters and add the keys to `applySystemProperties` (as in earlier phases).

> **Important:** if `profiler.trace.packages` is empty, method tracing is a no-op
> regardless of `trace.enabled` — we refuse to instrument "everything."

---

## Step 3 — Thread-local request profiling context

Held in a **separate helper class** (NOT the advice class) so the advice only ever
*calls* it — never inlines field access (rule 3).

`src/main/java/agent/profiling/RequestProfilingContext.java`

```java
package agent.profiling;

import agent.model.MethodSpan;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Per-thread state for an in-flight traced request. All access is single-threaded
 * (the request thread), so no synchronization is needed inside an instance.
 *
 * The advice calls the static enter()/exit() methods; this class keeps the call
 * stack and builds the tree.
 */
public final class RequestProfilingContext {

    /** One context per request thread; null when the current request is not traced. */
    private static final ThreadLocal<RequestProfilingContext> CURRENT = new ThreadLocal<>();

    /** Published by the sampling/alloc correlation: the span on top of each thread. */
    private static final java.util.concurrent.ConcurrentHashMap<Long, MethodSpan> TOP_SPAN =
        new java.util.concurrent.ConcurrentHashMap<>();

    private final Deque<MethodSpan> stack = new ArrayDeque<>();
    private final MethodSpan root;
    private int spanCount;
    private final int maxDepth;
    private final int maxSpans;

    private RequestProfilingContext(MethodSpan root, int maxDepth, int maxSpans) {
        this.root = root; this.maxDepth = maxDepth; this.maxSpans = maxSpans;
        stack.push(root);
    }

    // ── Lifecycle (called by the DispatcherServlet root advice) ───────────
    public static void begin(MethodSpan root, int maxDepth, int maxSpans) {
        CURRENT.set(new RequestProfilingContext(root, maxDepth, maxSpans));
        TOP_SPAN.put(Thread.currentThread().getId(), root);
    }
    public static MethodSpan end() {
        RequestProfilingContext c = CURRENT.get();
        CURRENT.remove();
        TOP_SPAN.remove(Thread.currentThread().getId());
        return c == null ? null : c.root;
    }
    public static boolean isTracing() { return CURRENT.get() != null; }

    // ── Per-method enter/exit (called by MethodTraceAdvice) ───────────────
    /** @return an opaque token to pass to methodExit, or null if not tracing/capped. */
    public static MethodSpan methodEnter(String className, String methodName) {
        RequestProfilingContext c = CURRENT.get();
        if (c == null) return null;
        if (c.stack.size() > c.maxDepth || c.spanCount >= c.maxSpans) return null;
        MethodSpan span = new MethodSpan();
        span.className = className; span.methodName = methodName;
        c.stack.peek().children.add(span);
        c.stack.push(span);
        c.spanCount++;
        TOP_SPAN.put(Thread.currentThread().getId(), span);
        return span;
    }
    public static void methodExit(MethodSpan span, long wallNs, long cpuNs, long allocBytes) {
        if (span == null) return;
        RequestProfilingContext c = CURRENT.get();
        if (c == null) return;
        span.wallNs = wallNs; span.cpuNs = cpuNs; span.allocBytes = allocBytes;
        c.stack.pop();
        TOP_SPAN.put(Thread.currentThread().getId(), c.stack.peek());
    }

    /** Used by the JFR allocation correlator (Step 7). */
    public static MethodSpan topSpanForThread(long threadId) { return TOP_SPAN.get(threadId); }
}
```

---

## Step 4 — The method-tracing advice

`src/main/java/agent/collector/trace/MethodTraceAdvice.java`

```java
package agent.collector.trace;

import agent.model.MethodSpan;
import agent.profiling.RequestProfilingContext;
import agent.profiling.ThreadMetrics;

import net.bytebuddy.asm.Advice;

/**
 * Inlined into every instrumented application method. Tier 1 — runs on the request
 * thread for every call, so the fast path (request not traced) must be nearly free.
 *
 * Follows the advice rules from the Phase 2–5 classloader work:
 *   - no shaded/framework types in signatures
 *   - all state lives in RequestProfilingContext / ThreadMetrics (helper classes we
 *     CALL, never inline field access from)
 *   - never throws into the app.
 */
public final class MethodTraceAdvice {

    @Advice.OnMethodEnter
    public static long[] enter(@Advice.Origin("#t") String className,
                               @Advice.Origin("#m") String methodName) {
        // Fast path: one ThreadLocal read. If not tracing, do nothing.
        if (!RequestProfilingContext.isTracing()) return null;
        try {
            MethodSpan span = RequestProfilingContext.methodEnter(className, methodName);
            if (span == null) return null;
            // Capture start counters: [wallNs, cpuNs, allocBytes] + span handle slot.
            return new long[]{ System.nanoTime(),
                               ThreadMetrics.cpuNs(),
                               ThreadMetrics.allocBytes(),
                               System.identityHashCode(span) }; // span re-fetched on exit
        } catch (Throwable t) { return null; }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(@Advice.Enter long[] start) {
        if (start == null) return;
        try {
            long wall  = System.nanoTime()        - start[0];
            long cpu   = ThreadMetrics.cpuNs()     - start[1];
            long alloc = ThreadMetrics.allocBytes()- start[2];
            // The span on top of the thread's stack is the one we entered.
            RequestProfilingContext.exitTop(wall, cpu, alloc);
        } catch (Throwable t) { /* never propagate */ }
    }
}
```

> **Design note on the enter/exit hand-off:** because we cannot store a typed object
> reference across `@Advice.Enter` safely without coupling, the cleanest approach is
> to have `RequestProfilingContext` track the current span on its own stack and
> expose `exitTop(...)` that pops + finalizes the top span. Adjust Step 3's
> `methodExit` to an argument-free `exitTop(wall, cpu, alloc)` that pops `stack` and
> writes the deltas. (Implement whichever of the two shapes you prefer — keep all
> object handling inside the helper, never in the advice.)

`src/main/java/agent/profiling/ThreadMetrics.java` — wraps the JMX beans and
degrades gracefully when CPU/allocation counters are unsupported:

```java
package agent.profiling;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

public final class ThreadMetrics {
    private static final ThreadMXBean TMX = ManagementFactory.getThreadMXBean();
    private static final com.sun.management.ThreadMXBean SUN =
        (TMX instanceof com.sun.management.ThreadMXBean s) ? s : null;
    private static volatile boolean cpuOk;
    private static volatile boolean allocOk;

    private ThreadMetrics() {}

    /** Call once at startup. Enables the counters if supported. */
    public static void init() {
        try {
            if (TMX.isThreadCpuTimeSupported()) { TMX.setThreadCpuTimeEnabled(true); cpuOk = true; }
        } catch (Throwable ignore) {}
        try {
            if (SUN != null && SUN.isThreadAllocatedMemorySupported()) {
                SUN.setThreadAllocatedMemoryEnabled(true); allocOk = true;
            }
        } catch (Throwable ignore) {}
    }

    public static long cpuNs()      { return cpuOk ? TMX.getCurrentThreadCpuTime() : 0L; }
    public static long allocBytes() {
        return allocOk ? SUN.getThreadAllocatedBytes(Thread.currentThread().getId()) : 0L;
    }
    public static boolean cpuSupported()   { return cpuOk; }
    public static boolean allocSupported() { return allocOk; }
}
```

---

## Step 5 — Install method instrumentation (scoped, gated)

Extend `SpringInstrumentation` (or add a `MethodTraceInstrumentation`) to add a third
Byte Buddy transformation, **only when** tracing is enabled AND packages are set:

```java
if (config.isTraceEnabled() && !config.getTracePackages().isEmpty()) {
    ElementMatcher.Junction<TypeDescription> pkgMatcher = none();
    for (String pkg : config.getTracePackages().split(",")) {
        pkgMatcher = pkgMatcher.or(ElementMatchers.nameStartsWith(pkg.trim()));
    }
    builder = builder
        .type(pkgMatcher
            .and(not(isInterface()))
            .and(not(ElementMatchers.nameStartsWith("agent."))))
        .transform((b, type, cl, mod, dom) -> b.visit(
            Advice.to(MethodTraceAdvice.class).on(
                isMethod()
                    .and(not(isConstructor()))
                    .and(not(isAbstract()))
                    .and(not(isSynthetic()))
                    .and(not(isGetter().or(isSetter())))   // skip trivial accessors
            )));
}
```

> **Why exclude getters/setters and synthetics?** They are extremely high-volume and
> low-value; instrumenting them multiplies overhead for noise. Make this configurable
> later if needed (`profiler.trace.include.accessors`).

Keep the `AgentBuilder.Listener` from Phase 5 — you want to see exactly which app
classes were instrumented and any errors.

---

## Step 6 — Tie the call tree to the request

The DispatcherServlet advice (Phase 2) is the request boundary. Extend it so that,
on **enter**, it decides whether to trace this request (sampling), and if so calls
`RequestProfilingContext.begin(rootSpan, maxDepth, maxSpans)`; on **exit**, it calls
`RequestProfilingContext.end()`, fills the synthetic root's totals, builds a
`RequestTrace`, and writes it to a new `traceBuffer` ring buffer.

Sampling decision (cheap, lock-free): keep an `AtomicLong counter`; trace when
`counter.incrementAndGet() % sampleRate == 0`. Store `traceId` (e.g. 8 hex chars)
on the root span so the dashboard can deep-link.

Keep the existing endpoint-stats path unchanged — Phase 6 *adds* a parallel
request-scoped tree; it does not replace per-endpoint latency aggregation.

---

## Step 7 — Object/type allocation detail via JFR

`src/main/java/agent/profiling/AllocationProfiler.java`

```java
package agent.profiling;

import agent.model.MethodSpan;
import jdk.jfr.consumer.RecordingStream;

import java.time.Duration;
import java.util.logging.Logger;

/**
 * Streams JFR jdk.ObjectAllocationSample events and attributes each sampled
 * allocation to the method span currently executing on the allocating thread.
 *
 * Runs on its own daemon thread. Throttled so it cannot dominate CPU. Only active
 * when trace.alloc.detail is enabled and JFR is available (JDK 16+).
 */
public final class AllocationProfiler {
    private static final Logger log = Logger.getLogger(AllocationProfiler.class.getName());

    public void start() {
        Thread t = new Thread(this::run, "profiler-jfr-alloc");
        t.setDaemon(true); t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private void run() {
        try (RecordingStream rs = new RecordingStream()) {
            rs.enable("jdk.ObjectAllocationSample")
              .with("throttle", "150/s");          // cap event rate
            rs.onEvent("jdk.ObjectAllocationSample", e -> {
                try {
                    long tid = e.getThread() == null ? -1 : e.getThread().getJavaThreadId();
                    MethodSpan span = RequestProfilingContext.topSpanForThread(tid);
                    if (span == null) return;       // allocation not under a traced request
                    String type = e.getClass("objectClass").getName();
                    long weight  = e.getLong("weight");   // estimated bytes
                    synchronized (span.allocByType) {
                        span.allocByType.merge(type, weight, Long::sum);
                    }
                } catch (Throwable ignore) {}
            });
            rs.start();   // blocks this daemon thread
        } catch (Throwable t) {
            log.warning("JFR allocation profiling unavailable: " + t.getMessage());
        }
    }
}
```

> **Accuracy note:** JFR allocation sampling is *statistical* (throttled), so
> `allocByType` is a best-effort breakdown of the dominant types, not an exact count.
> The per-method `allocBytes` from Step 4 (thread allocation delta) IS exact — use
> that for totals and use `allocByType` for "what kind of objects."

---

## Step 8 — The sampling profiler (always-on flame graph)

`src/main/java/agent/profiling/StackSampler.java` — a daemon that every
`interval.ms` snapshots stacks of request-handling threads and folds them into a
shared `FlameNode` tree (guarded by a lock; writes are infrequent relative to the
app's work).

```java
// Sketch:
ThreadMXBean tmx = ManagementFactory.getThreadMXBean();
for (ThreadInfo ti : tmx.dumpAllThreads(false, false)) {
    if (!isRequestThread(ti)) continue;            // e.g. name contains "http"/"exec"
    foldStack(flameRoot, ti.getStackTrace());      // bottom-up into the tree
}
```

Identify request threads by name pattern (Tomcat: `http-nio-*-exec-*`). Expose the
folded tree for the dashboard flame graph. This mode needs **no app instrumentation**
and is safe to leave on in production.

---

## Step 9 — Registry, buffers, aggregation

- Add `RingBuffer<RequestTrace> traceBuffer` (capacity e.g. 200) to `CollectorRegistry`,
  plus a published `List<RequestTrace>` snapshot (like endpoint stats) and the shared
  `FlameNode` root for the sampler.
- The `AggregationDaemon` drains `traceBuffer` each cycle and publishes the most
  recent N traces; it also snapshots the flame tree for the API.
- (Optional) persist traces to a new SQLite table `request_traces` (JSON blob of the
  tree) for history, reusing the Phase 3 persistence machinery.

---

## Step 10 — HTTP endpoints

Add to `ProfilerHttpServer` (Javalin 7 `config.routes.get(...)`):

- `GET /profiler/traces` → recent `RequestTrace` summaries (id, path, totalWallMs,
  totalCpuMs, totalAllocBytes).
- `GET /profiler/trace/{id}` → the full call tree for one trace (the `MethodSpan`
  tree, including `allocByType`).
- `GET /profiler/flamegraph` → the folded sampling tree (`FlameNode`).
- `GET /profiler/status` → add `cpuTimingSupported`, `allocTimingSupported`,
  `jfrAllocDetail`, `traceEnabled`, `tracedRequests` so users can see what is active.

---

## Step 11 — Dashboard panels

Extend the self-contained dashboard (Phase 5) with two panels — still vanilla JS,
Canvas, no CDN:

- **Flame graph** (from `/profiler/flamegraph`) — classic width-proportional-to-samples
  rectangles; click to zoom; tooltip shows frame + sample %.
- **Request traces** — a list (from `/profiler/traces`); selecting one loads
  `/profiler/trace/{id}` and renders the **call tree** with per-method wall/CPU/alloc
  bars and an expandable "allocated types" breakdown per method.

---

## Step 12 — Overhead benchmark (MANDATORY for this phase)

Because method tracing changes the app's hot path, this phase is not "done" until its
overhead is measured. Add a benchmark (JMH module under `bench/`, or a repeatable
load-test script against the Phase-5 demo app) measuring **throughput and p95
latency** in four configurations:

1. **No agent** (baseline).
2. **Agent, sampling profiler only** (tracing off).
3. **Agent, tracing instrumented but request NOT selected** (measures the
   thread-local-gate cost on instrumented methods).
4. **Agent, tracing active** (request selected, full tree + alloc detail).

Record the results in this doc / the README. Target guidance (tune to your hardware):
sampling-only < 2% throughput loss; gated-but-inactive < 5%; fully-traced is expected
to be heavier and is why tracing is sampled.

---

## Step 13 — Integration tests (MANDATORY)

Add an integration test that reproduces the real deployment (the gap that let the
Phase 2–5 classloader bugs through). Reuse the DB-free demo app (`demo/`):

- Build the demo fat jar, launch it with `-javaagent:<agent>=trace.enabled=true,
  trace.packages=demo,trace.sample.rate=1`.
- Hit `/hello` and `/slow`, then assert:
  - `GET /profiler/traces` returns ≥ 1 trace for `/slow`.
  - `GET /profiler/trace/{id}` contains a span for `demo.DemoApplication.slow`
    with `wallNs` ≳ 150ms and non-zero `allocBytes`.
  - `GET /profiler/flamegraph` is non-empty.
- Run it via Maven failsafe (`*IT.java`) so it is part of `mvn verify`, and wire a CI
  workflow that runs it.

This test is the single most valuable addition in the phase — it catches the class of
runtime/classloader bug that unit tests cannot.

---

## Step 14 — Unit tests

- `RequestProfilingContextTest` — enter/exit builds the expected tree; depth/span caps
  are honored; self-times = total − children.
- `FlameGraphTest` — folding a set of synthetic stacks yields correct sample counts.
- `ThreadMetricsTest` — supported flags don't throw; deltas are non-negative;
  graceful zeros when unsupported (use Mockito/assumptions).
- `AllocationProfilerTest` — correlation attributes a sampled event to the top span
  for the thread (feed synthetic events to the handler).

---

## Step 15 — Build & manual verification

```bash
# build agent (JDK 17) and demo app
mvn -q clean package -DskipTests
( cd demo && mvn -q clean package )

# run demo with deep tracing of the demo package, every request traced
java -javaagent:target/jvm-profiler-agent-1.0.0-SNAPSHOT.jar=port=7099,trace.enabled=true,trace.packages=demo,trace.sample.rate=1 \
     -jar demo/target/profiler-demo-app.jar

curl -s localhost:8080/slow
curl -s localhost:7099/profiler/traces | python -m json.tool
# pick an id, then:
curl -s "localhost:7099/profiler/trace/<id>" | python -m json.tool
curl -s localhost:7099/profiler/flamegraph | python -m json.tool
```

Expect a tree under `/slow` with `demo.DemoApplication.slow` ~150ms, non-zero
`allocBytes` (the 256 KB `byte[]`), and an `allocByType` entry for `byte[]`.

---

## Step 16 — Phase 6 Checklist

- [ ] Sampling profiler produces a non-empty flame graph with no app instrumentation
- [ ] With `trace.enabled` + `trace.packages`, instrumented app methods appear in `Instrumented:` logs (no errors)
- [ ] A traced request yields a call tree with correct parent/child nesting and self-times
- [ ] Per-method `cpuNs` and `allocBytes` are populated (and degrade to 0 with a clear `cpuTimingSupported=false` when unsupported)
- [ ] `allocByType` shows dominant object types for an allocation-heavy method
- [ ] Depth/span caps prevent unbounded trees
- [ ] Untraced requests pay only the thread-local gate (verified by Step 12 config #3)
- [ ] Overhead benchmark numbers recorded for all four configs
- [ ] Integration test (`*IT`) passes in `mvn verify` and in CI
- [ ] Dashboard renders the flame graph and a request call tree
- [ ] All advice obeys the rules (public fields, no shaded/framework types, catch Throwable)

```bash
git checkout develop
git merge --no-ff phase/6-deep-request-profiling -m "Merge Phase 6: Deep Request Profiling"
git tag phase-6-complete
git push origin develop --tags
```

---

## Troubleshooting Phase 6

**`allocBytes` always 0** — `com.sun.management.ThreadMXBean` not available (non-HotSpot
JVM) or counter not enabled. Check `/profiler/status.allocTimingSupported`; ensure
`ThreadMetrics.init()` runs at startup.

**`cpuNs` always 0 or huge on Windows** — thread CPU time granularity is coarse on some
platforms. Report `cpuTimingSupported`; prefer wall time for short methods.

**No spans appear though the request is traced** — the advice was inlined into app
classes but a rule was violated. Check the `AgentBuilder.Listener` log for
`Instrumentation error on <appClass>`; the usual culprits are a non-public field
accessed from inlined code or a shaded/framework type in the advice signature.

**Huge overhead / app slows to a crawl** — `trace.packages` is too broad (you
instrumented a hot library) or `trace.sample.rate` is 1 in production. Narrow the
package, raise the sample rate, and keep getters/setters excluded.

**JFR errors at startup** — `jdk.ObjectAllocationSample` requires JDK 16+. On older
JDKs disable `trace.alloc.detail`; per-method `allocBytes` still works without JFR.

**Trace tree OOMs or is enormous** — lower `trace.max.depth` / `trace.max.spans`;
recursion-heavy code can explode a tree without caps.

---

## Amendment A — Reliable per-object memory via allocation-site instrumentation

The original Step 7 attributed object-type detail using JFR allocation *sampling*.
In practice that is statistical and asynchronous: for short requests the JFR event
arrives after the method has returned, so the per-type breakdown comes back empty.
That is not good enough for the goal — *clicking a request and seeing, per function,
the objects it allocated (type, count, bytes) next to the timing.*

**This amendment replaces JFR object-detail with exact allocation-site instrumentation.**

### What changes
- **`MethodSpan.allocByType`** becomes `Map<String, TypeAlloc>` where
  `TypeAlloc = { count, bytes }` — per object type, how many were allocated and
  how many bytes (shallow size via `Instrumentation.getObjectSize`).
- **`AllocationRecorder.record(Object)`** is an agent helper that sizes the object,
  resolves the **method span currently on top of the request thread's stack**
  (`RequestProfilingContext.currentTopSpan()`), and adds to its `allocByType`.
- **Allocation-site instrumentation** — a Byte Buddy `AsmVisitorWrapper` injects a
  call to `AllocationRecorder.record(...)` immediately after each allocation
  bytecode **inside the instrumented (traced-package) methods**:
  - object creation: after the `NEW … INVOKESPECIAL <init>` pair (paired LIFO so
    `super()/this()` calls are not mistaken for allocations);
  - arrays: after `NEWARRAY` (primitive, e.g. `byte[]`), `ANEWARRAY` (object arrays),
    and `MULTIANEWARRAY`.
- The JFR `AllocationProfiler` is removed; `RequestProfilingContext` drops its
  cross-thread `TOP_SPAN` map (no longer needed — attribution is same-thread).

### Why this is reliable
Every allocation executed by instrumented code is captured exactly (not sampled),
on the request thread, and attributed to the function executing it. The per-method
**total** allocated bytes (thread allocation counter) is unchanged and still exact.

### Coverage caveat (by design, tunable)
Only allocations made **in instrumented packages** are broken down by type. Memory
allocated *inside* the JDK/framework (e.g. Hibernate building entities) still shows
in a method's exact **total** bytes but not in its per-type table — unless you widen
`profiler.trace.packages` to include those packages (more overhead). Coverage is thus
controlled by the same package scope as method tracing.

### Gating / overhead
The injected `record(...)` call first checks `currentTopSpan()` (a ThreadLocal read)
and returns immediately when the request is not being traced — so untraced requests
pay only that check per allocation in instrumented methods. Allocation detail is
gated by `profiler.trace.alloc.detail.enabled` and requires method tracing to be on.

### Dashboard
The call-tree detail (Request Traces panel) renders, under each function node, a
small **object table**: `type | count | memory`, sorted by memory — beside the
function's wall/self/CPU time.

---

*End of Phase 6.*
*Previous: [Phase 5 — Multi-Instance & Dashboard]. The dashboard (Phase 5 part 1) is
complete; the multi-instance registry remains a separate follow-up.*
