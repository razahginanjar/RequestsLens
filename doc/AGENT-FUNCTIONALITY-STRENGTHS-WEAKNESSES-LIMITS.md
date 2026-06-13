# JVM Profiler Agent
## Functionality, Strengths, Weaknesses & Limits
### Technical Reference Document

---

## Table of Contents

1. [What This Agent Does — Full Functionality](#1-what-this-agent-does--full-functionality)
2. [How Each Feature Works Internally](#2-how-each-feature-works-internally)
3. [Strengths](#3-strengths)
4. [Weaknesses](#4-weaknesses)
5. [Hard Limits — Things That Cannot Be Fixed](#5-hard-limits--things-that-cannot-be-fixed)
6. [Soft Limits — Things That Can Be Tuned](#6-soft-limits--things-that-can-be-tuned)
7. [Comparison to Existing Tools](#7-comparison-to-existing-tools)
8. [What This Agent Is Not](#8-what-this-agent-is-not)
9. [Decision Guide — When to Use and When Not To](#9-decision-guide--when-to-use-and-when-not-to)

---

## 1. What This Agent Does — Full Functionality

This section describes every capability the agent provides, written in plain
terms so both technical and non-technical stakeholders can understand what
was built and what it delivers.

---

### 1.1 Zero-Config Attachment

**What it does:**
The agent attaches to any JVM-based application with a single command-line flag.
No source code changes, no annotations, no configuration files required.

```bash
java -javaagent:jvm-profiler-agent.jar -jar your-spring-boot-app.jar
```

**What happens when you attach:**
The JVM calls the agent's `premain()` method before the application's `main()`
runs. The agent starts its background threads and returns in under 500ms.
The application then starts normally — it does not know the agent is there.

**Why it matters:**
Developers can profile any application without modifying it. This is useful
when profiling third-party code, legacy codebases, or when you want to add
profiling to an existing deployment without a code release.

---

### 1.2 Live Heap Monitoring

**What it does:**
Samples JVM heap memory usage every 10ms (configurable) and exposes the data
via HTTP. Tracks:

- Total heap used, committed, and maximum
- Per-memory-pool breakdown: Eden Space, Survivor Space, Old Generation, Metaspace
- Rolling time-series of the last 1000 samples (~10 seconds at default interval)

**How to access it:**
```
GET http://localhost:7070/profiler/heap
```

**Example response:**
```json
{
  "sampleCount": 987,
  "current": {
    "usedBytes": 54525952,
    "usedMb": 52,
    "committedBytes": 134217728,
    "maxBytes": 4294967296,
    "poolUsage": {
      "G1 Eden Space": 10485760,
      "G1 Old Gen": 44040192,
      "Metaspace": 47185920
    }
  },
  "samples": [ ... ]
}
```

---

### 1.3 Garbage Collection Tracking

**What it does:**
Listens for every GC event fired by the JVM and records:

- GC collector name (G1 Young Generation, ZGC Cycles, PS MarkSweep, etc.)
- GC cause (Allocation Failure, G1 Evacuation Pause, System.gc(), etc.)
- Pause duration in milliseconds
- Heap size before and after the GC
- Computed statistics: total GC count, average pause, maximum pause, GC overhead %

**How to access it:**
```
GET http://localhost:7070/profiler/gc
```

**What GC overhead % means:**
Time spent in GC pauses divided by elapsed wall-clock time. A healthy application
should be below 5%. Above 15% means GC is consuming a significant fraction of
CPU time and the application's throughput is being noticeably affected.

---

### 1.4 Spring Bean Memory Mapping

**What it does:**
After the Spring ApplicationContext finishes initializing, the agent scans all
registered singleton beans, estimates their heap contribution, and ranks them
by estimated memory usage.

**How to access it:**
```
GET http://localhost:7070/profiler/beans
```

**Example response:**
```json
{
  "beanCount": 20,
  "beans": [
    {
      "beanName": "entityManagerFactory",
      "className": "org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean",
      "scope": "singleton",
      "estimatedBytes": 8388608
    },
    {
      "beanName": "dataSource",
      "className": "com.zaxxer.hikari.HikariDataSource",
      "scope": "singleton",
      "estimatedBytes": 4194304
    }
  ]
}
```

**Important caveat:** Sizes are estimates. The agent walks the object graph up
to three levels deep using Java reflection and `Instrumentation.getObjectSize()`.
This is accurate enough for ranking purposes but not for exact memory accounting.

---

### 1.5 Per-Endpoint Performance Tracking

**What it does:**
Intercepts every HTTP request handled by Spring's `DispatcherServlet` and
records — without any annotation in the application code:

- Request count per endpoint
- Average latency (milliseconds)
- P95 latency — 95% of requests completed within this time
- Maximum latency
- Average heap delta — how much heap changed during this request
- Current requests per second (RPS)

**How to access it:**
```
GET http://localhost:7070/profiler/endpoints
```

**Example response:**
```json
{
  "endpointCount": 3,
  "totalRps": 47.3,
  "endpoints": [
    {
      "method": "GET",
      "path": "/api/users",
      "requestCount": 1243,
      "avgLatencyMs": 187.4,
      "p95LatencyMs": 312.0,
      "maxLatencyMs": 892,
      "avgHeapDeltaBytes": 204800,
      "currentRps": 32.1
    }
  ]
}
```

---

### 1.6 Memory Leak Detection

**What it does:**
Runs a sliding-window analysis over recent heap snapshots every 5 seconds.
If heap usage grows by more than 10% within a 60-second window without any
GC event clearing it, the agent raises a `WARN` leak warning. Growth above
25% in the same window raises a `CRITICAL` warning.

**How to access it:**
```
GET http://localhost:7070/profiler/leaks
```

**What a warning looks like:**
```json
{
  "activeWarnings": 1,
  "warnings": [
    {
      "detectedAtMs": 1748000000000,
      "heapGrowthBytes": 52428800,
      "windowMs": 60000,
      "growthPercent": 11.4,
      "severity": "WARN"
    }
  ]
}
```

---

### 1.7 Metric Persistence (SQLite)

**What it does:**
Persists heap snapshots and GC events to an embedded SQLite database every
5 seconds. Data is retained for 7 days by default (configurable). After a
JVM restart, historical data is queryable via the API.

**How to query history:**
```
GET http://localhost:7070/profiler/history/heap?from=1748000000000&to=1748003600000
GET http://localhost:7070/profiler/history/gc?from=1748000000000&to=1748003600000
```

**What it enables:**
- Compare heap usage before and after a deployment
- Identify memory trends over hours or days
- Correlate GC spikes with specific events

---

### 1.8 Adaptive Sampling

**What it does:**
Monitors the application's current request-per-second rate. When RPS exceeds
the configured threshold (default 500 req/s), the agent automatically slows
its sampling frequency from the base interval (10ms) to a reduced rate (50ms
by default). When load drops, it returns to the base rate.

**How to see current state:**
```
GET http://localhost:7070/profiler/status
```

```json
{
  "samplingState": "THROTTLED",
  "effectiveIntervalMs": 50,
  "baseIntervalMs": 10,
  "currentRps": 623.4,
  "rpsThreshold": 500.0
}
```

**Why it matters:** Without adaptive sampling, the agent's fixed 10ms interval
adds compounding overhead at high request volumes. Adaptive sampling keeps
overhead below 2% CPU even under peak load.

---

### 1.9 Webhook Alerting

**What it does:**
When a leak warning or GC overhead threshold breach is detected, the agent
sends an HTTP POST to a configured webhook URL. This enables integration with
Slack, PagerDuty, Microsoft Teams, or any HTTP-capable alerting system.

**Configuration:**
```properties
profiler.alert.webhook.url=https://hooks.slack.com/services/your-webhook
profiler.alert.gc.overhead.threshold=15
```

**Alert payload example:**
```json
{
  "instanceId": "app-server-1:7070",
  "alertType": "LEAK_WARNING",
  "severity": "WARN",
  "message": "Heap grew 11.4% in 60s with no GC relief",
  "timestampMs": 1748000000000,
  "metadata": {
    "growthPercent": 11.4,
    "growthBytes": 52428800
  }
}
```

**Alert lifecycle:** One POST when the condition first becomes true, one POST
when it resolves. Not repeated every 5 seconds.

---

### 1.10 Multi-Instance Registry

**What it does:**
When multiple instances of the same application run simultaneously (multiple
pods, multiple servers), each agent can register with a designated registry
host. The registry provides:

- List of all live instances
- Aggregated heap across all instances (sum)
- Union of all active leak warnings across all instances

**How it works:**
One agent instance acts as the registry host — no separate server needed.
Other instances point their `profiler.instance.registry.url` config at it.

```bash
# Instance 1 — acts as registry host (no extra config)
java -javaagent:agent.jar=port=7070 -jar app.jar

# Instance 2 — registers with Instance 1
java -javaagent:agent.jar=port=7071,instance.registry.url=http://instance-1:7070 -jar app.jar
```

---

### 1.11 Self-Monitoring

**What it does:**
The agent monitors its own health and exposes the data at `/profiler/status`.
This answers the question: "Is the profiler itself working correctly?"

| Metric | What It Tells You |
|---|---|
| `agentHeapUsedBytes` | How much heap the agent itself consumes |
| `droppedSamples` | Ring buffer overflows — data loss indicator |
| `droppedPersistenceSamples` | SQLite falling behind — persistence data loss |
| `persistenceQueueDepth` | How backed up the SQLite write queue is |
| `webhookDeliveryFailures` | Failed alert deliveries |
| `samplingDelays` | How often GC or CPU pressure delayed the sampler |
| `registryHeartbeatFailures` | Registry connectivity problems |
| `lastSampleTimestampMs` | When the sampler last successfully ran |

---

### 1.12 Embedded Web Dashboard

**What it does:**
Serves a visual dashboard at `http://localhost:7070/profiler/dashboard` from
within the agent JAR itself. No external dependencies, no Grafana, no Prometheus.

**Dashboard panels:**
- Live heap chart — auto-updating line chart, last 100 samples
- Historical heap — toggle to date-range view backed by SQLite
- GC summary — count, avg/max pause, color-coded overhead indicator
- Slowest endpoints table — ranked by average latency
- Top beans table — ranked by estimated heap footprint
- Leak warnings banner — red banner when active warnings exist
- Agent health panel — all self-monitoring metrics with color indicators
- Multi-instance panel — appears when more than one instance is registered
- Status bar — instance ID, sampling state, uptime, current RPS

---

## 2. How Each Feature Works Internally

This section is for developers who want to understand the implementation
choices behind each feature, not just what it does.

---

### 2.1 Why JMX for Heap and GC Data

JMX (Java Management Extensions) is built into the JDK since Java 5. The
`MemoryMXBean` provides heap totals. `MemoryPoolMXBeans` provide per-pool
breakdown. `GarbageCollectorMXBeans` implement `NotificationEmitter`, allowing
event-driven GC capture without polling.

**Alternative considered:** Polling `GarbageCollectorMXBean.getCollectionCount()`
on a timer. Rejected because it misses the duration, cause, and before/after
heap sizes available only in the notification payload.

**Alternative considered:** JVMTI (JVM Tool Interface) for native-level profiling.
Rejected because it requires writing C++ native code, breaking the pure-Java
design and making the agent platform-specific.

---

### 2.2 Why Byte Buddy for Instrumentation

Byte Buddy intercepts `DispatcherServlet.doDispatch()` and
`AbstractApplicationContext.refresh()` by injecting advice code into their
bytecode at class-load time.

**Alternative considered:** AOP via Spring's `@Aspect`. Rejected because it
requires adding a dependency to the target application and only works if the
application uses Spring AOP (not all Spring Boot apps do).

**Alternative considered:** ASM (raw bytecode manipulation). Rejected because
ASM requires writing bytecode by hand — brittle, hard to test, and error-prone
compared to Byte Buddy's type-safe API. Byte Buddy is used by Mockito, Hibernate,
and OpenTelemetry for the same reason.

**Why shading is required:** Byte Buddy must be relocated in the agent JAR
(`net.bytebuddy → agent.shaded.bytebuddy`) because Hibernate, Spring, and
Mockito all use Byte Buddy. If two different versions share the same package
name, the JVM loads one and ignores the other, causing silent version mismatches.

---

### 2.3 Why Lock-Free Ring Buffer

The `HeapSampler` runs 100 times per second. Writing samples to a
`BlockingQueue` would require locking on every write. Lock acquisition is
expensive under contention — at 100 writes/second with occasional GC pauses,
contention is real.

The ring buffer uses a single `AtomicLong` for the write index. `getAndIncrement()`
is a single CPU instruction (CAS — compare and swap) with no thread blocking.
The write path allocates nothing and never blocks.

**Trade-off:** Ring buffers overwrite old data when full. This is acceptable
because the persistence layer (SQLite) drains the buffer every 5 seconds —
at 10ms interval and 1000-slot capacity, the buffer holds 10 seconds of data.
The persistence daemon runs every 5 seconds. Under normal conditions, the
buffer never fills.

---

### 2.4 Why Async Batch Writes to SQLite

Writing every heap sample directly to SQLite would serialize the sampling
thread on disk I/O. Disk writes can take 1–50ms. At 10ms sampling interval,
this would delay every sample by the disk write time, making the interval
unreliable and inflating heap measurements.

Instead: samples accumulate in a `BlockingQueue` (capacity 5000). A daemon
thread drains it every 5 seconds and writes in a single batch transaction.
Batch writes are 10–50x faster than individual inserts because the entire
batch is written in one disk flush.

**The sampling thread's write path:** `blockingQueue.offer(sample)` — returns
immediately whether the queue is full or not. No blocking ever occurs on the
hot path.

---

### 2.5 Why Peer-to-Peer Multi-Instance (Not a Dedicated Server)

A dedicated registry server would require deploying an additional process,
configuring it, keeping it running, and managing its lifecycle. This conflicts
with the zero-infrastructure design goal.

The peer-to-peer approach: one agent instance hosts the registry (in-memory,
no persistence). Other instances register with it. The registry is just an
HTTP endpoint — any live agent can be the registry host.

**Trade-off:** If the registry host instance goes down, the registry is lost.
Instances would need to re-register with a new host. This is acceptable for
the development and staging use case — it is not designed for production
high-availability scenarios.

---

### 2.6 Why the Leak Detector Skips When GC Occurred

Immediately after a GC event, the heap drops. Immediately after the drop,
the application allocates new objects and the heap rises again. This rise
looks like growth — but it is normal post-GC behavior, not a leak.

The detector checks if any GC event occurred within the detection window.
If yes, the rising heap is explained by post-GC re-allocation — skip detection
this cycle. If no GC occurred and the heap is still rising, that is the
signature of a real leak.

**Known false-positive scenario:** Cache warming during application startup.
The heap grows continuously as caches fill, no GC has occurred yet (JVM may
not have reached GC threshold), and the detector fires a WARN. This is why
the documentation recommends disabling leak detection during load test warm-up.

---

## 3. Strengths

---

### 3.1 Zero Infrastructure Required

The agent is a single JAR. It needs no:
- Separate database server
- Metrics collector (Prometheus, StatsD)
- Visualization tool (Grafana, Kibana)
- Message broker
- Sidecar container

A developer adds one flag to their startup command and has a working profiling
dashboard in under 5 seconds. This is the primary differentiator from every
production APM tool.

---

### 3.2 Spring-Native Data Presentation

Raw JVM profilers (JFR, async-profiler, VisualVM) output data in JVM terms:
object classes, allocation sites, thread stacks. A developer debugging a slow
Spring endpoint has to mentally map `org.hibernate.impl.SessionFactoryImpl` to
which bean it belongs to and which request it was handling.

This agent presents data in Spring terms developers already understand:
- `/profiler/beans` — "Which of my Spring beans is using the most memory?"
- `/profiler/endpoints` — "Which of my HTTP endpoints is slowest?"

No translation needed.

---

### 3.3 Observable Observer

The agent monitors its own health (`/profiler/status`) and exposes the data
alongside application metrics. A developer can immediately see if the profiler
itself is dropping samples, falling behind on persistence, or failing to deliver
alerts. Most profiling tools are black boxes — you do not know when they fail.

---

### 3.4 Honest Overhead With Adaptive Mitigation

The agent is transparent about its overhead: benchmark results are documented
in the README. At the default 10ms interval on CPU-bound workloads: ~2%
overhead. On I/O-bound workloads (more realistic): <0.5%.

Adaptive sampling reduces overhead further under high load — the scenario where
overhead matters most — by automatically backing off sampling frequency when
RPS exceeds the threshold.

---

### 3.5 Configurable Without Code Changes

All agent behavior is configurable via a properties file or JVM system properties
with safe defaults. A developer who wants different settings never needs to
rebuild the agent JAR — they edit a properties file and restart.

---

### 3.6 Classpath Isolation

Every agent dependency (Byte Buddy, Javalin, Jackson, SQLite) is shaded and
relocated inside the agent JAR. The target application's classpath is not
contaminated. An application using Jackson 2.15 is not affected by the agent's
shaded Jackson 2.17.

---

### 3.7 Demonstrable Engineering Decisions

For portfolio purposes: every design decision (lock-free buffers, event-driven
GC capture, async batch persistence, adaptive state machine) is motivated by a
specific technical problem that a simpler approach would fail to solve. This
demonstrates mid-level engineering thinking — not just building, but making
deliberate tradeoffs.

---

## 4. Weaknesses

These are real limitations of the current design — not bugs, but architectural
choices that have known downsides. Understanding these is important because
a developer or interviewer may ask about them.

---

### 4.1 Bean Size Estimates Are Approximate

**The weakness:**
`Instrumentation.getObjectSize()` returns shallow size — the size of the object
itself, not the objects it references. Walking the object graph up to depth 3
gives a deeper estimate, but it is still not precise.

**Why it matters:**
A Spring bean holding a large `List<String>` — the list itself is at depth 1,
each `String` is at depth 2, each `String`'s `char[]` is at depth 3. The agent
captures this. But objects at depth 4 and beyond are not counted. A bean with
a deeply nested object graph will show a lower-than-actual estimate.

**Real example where this fails:**
A `DataSource` bean holds a connection pool. Each connection holds a buffer.
Each buffer holds a large `byte[]`. The agent may only count the connection
pool object and miss the buffer arrays.

**Why it is acceptable:**
The goal is ranking beans by relative memory usage, not exact accounting. A bean
that shows 8MB estimated is almost certainly larger than one showing 800KB,
even if both actual sizes are higher than reported.

**How to get exact sizes:** Take a heap dump with `jmap -dump:format=b` and
analyze with Eclipse MAT or VisualVM. The agent is a diagnostic direction-finder,
not a heap dump replacement.

---

### 4.2 No Authentication on HTTP API

**The weakness:**
`http://localhost:7070/profiler/beans` is accessible to anyone who can reach
port 7070. The response reveals your application's internal Spring bean
structure, class names, memory usage, and endpoint routing. This is sensitive
internal information.

**Why it matters in the wrong environment:**
If port 7070 is accidentally exposed on a public network, an attacker learns
the application's internal architecture. In a shared staging environment,
other users on the same network can read profiling data.

**Current mitigation:**
The documentation explicitly states: do not expose port 7070 on shared or
public networks. Use firewall rules or network-level access control.

**Permanent fix:** v2 roadmap — Bearer token authentication. A single token
configured in properties, checked on every request. Rejected for v1 to maintain
scope focus and because the tool is explicitly designed for local development.

---

### 4.3 DispatcherServlet Only — No WebFlux Support

**The weakness:**
Endpoint tracking works by intercepting `DispatcherServlet.doDispatch()`.
Spring WebFlux (reactive programming) uses `DispatcherHandler`, not
`DispatcherServlet`. The two are fundamentally different — WebFlux is non-blocking
and event-driven, while DispatcherServlet is synchronous.

**Why it matters:**
Applications built with `spring-boot-starter-webflux` get no endpoint tracking.
The agent still works (heap monitoring, GC, bean mapping, leak detection all
function), but `/profiler/endpoints` always returns an empty list.

**The fix would require:**
A separate Byte Buddy interceptor targeting `DispatcherHandler.handle()`, plus
custom logic to track reactive request lifecycle (request start, completion, error)
across the async event loop — significantly more complex than the synchronous case.
Deferred to v2.

---

### 4.4 Object Graph Walk Can Be Expensive Above 500 Beans

**The weakness:**
For each bean, `BeanMemoryMapper` walks the object graph up to depth 3 using
reflection (`field.setAccessible(true)` + `field.get(object)`). This is done
in a loop across all registered beans.

A typical Spring Boot application has 100–300 beans. At 300 beans, a full scan
takes approximately 200–500ms. At 500 beans, it can approach 1 second. The 30-second
cache prevents this from being continuous overhead, but the first scan after
startup is always a cold scan.

**Why it matters:**
In a large enterprise application with 800+ beans (JPA entities, repositories,
services, controllers, interceptors, configuration classes), the first scan can
take several seconds and generate significant GC pressure from all the reflection
calls.

**Current mitigation:** Results are cached for 30 seconds. The scan runs once
per 30-second window, not continuously. Only the first call is expensive.

**Better fix:** Limit the scan to beans in packages the user configures, skipping
framework-internal beans. Deferred to v2.

---

### 4.5 Persistence Write Queue Can Drop Data

**The weakness:**
The persistence `BlockingQueue` has a capacity of 5000 entries. At 10ms sampling
interval, the sampler produces 100 samples/second. The daemon flushes every 5
seconds — draining 500 samples per cycle. Under normal conditions, the queue
stays near empty.

But if SQLite write latency spikes — for example, due to disk I/O contention
from another process, a slow filesystem, or a very large batch — the queue can
fill faster than it drains. When full, `offer()` returns false and the sample
is silently dropped. The `droppedPersistenceSamples` counter in `/profiler/status`
tracks this.

**Why silent drop instead of blocking:**
If the write call blocked, the sampling thread would stall waiting for disk I/O.
This would directly cause the sampler delays we are trying to measure — the
observer effect in action.

**When this is a real problem:**
Running the agent on a system with very slow disk I/O (network-attached storage,
a heavily loaded shared filesystem) while at a high sampling rate.

---

### 4.6 Registry Is Not Persistent — Lost on Host Restart

**The weakness:**
The multi-instance registry is in-memory only. If the registry host instance
restarts, all registration data is lost. Other instances detect the registry
is gone when their next heartbeat fails and log a warning — but they do not
automatically re-register until the next heartbeat interval (30 seconds).

**Why it matters:**
If the registry host crashes and restarts quickly, there is a 30-60 second
window where the registry appears empty. Any dashboard request during this
window shows no instances in the multi-instance panel.

**Mitigation:** Other instances re-register within one heartbeat interval.
The registry recovers automatically. There is no data loss for the individual
instances — they continue collecting metrics regardless of registry state.

---

### 4.7 GC Overhead Calculation Is Approximate

**The weakness:**
GC overhead is calculated as:
`total GC pause time in window / window duration × 100`

The window is the 5-second aggregation interval. If a GC event that started
before the window finished during the window, its entire duration is counted
in the current window even though part of the pause happened in the previous
one.

**Why it matters:**
GC pauses that straddle a window boundary are double-counted or under-counted
depending on timing. For very long GC pauses (Old Gen collection in large heaps
can take hundreds of milliseconds), this error can be significant.

**The precise alternative:** Track the exact timestamp of each pause start and
end, then compute overlap with the measurement window. This is significantly
more complex and the error in the current approach rarely exceeds a few percent.
Not a priority fix.

---

## 5. Hard Limits — Things That Cannot Be Fixed

These are not implementation choices — they are fundamental technical constraints
that cannot be resolved without a completely different approach.

---

### 5.1 JVM Mode Only — GraalVM Native Incompatible

**The limit:**
The agent uses Byte Buddy to modify class bytecode at runtime. GraalVM native
image compilation compiles Java to native code ahead-of-time. There is no
JVM, no bytecode, no class loading at runtime in a native image — the entire
basis of Java agent instrumentation disappears.

**Impact:**
Applications compiled to native binary with GraalVM (common in Quarkus native
mode) cannot use this agent at all. The `-javaagent:` flag is silently ignored.
No error is thrown, no data is collected.

**There is no fix for this.** The agent would need to be completely rewritten
using GraalVM's substitution API, which works at compile-time rather than
runtime. This is a fundamentally different programming model.

**Workaround:** Run the application in JVM mode during profiling, even if the
production deployment uses native compilation.

---

### 5.2 Java 11 Minimum

**The limit:**
The agent uses:
- `GarbageCollectionNotificationInfo` — available since Java 7, but the
  `getGcInfo().getMemoryUsageBeforeGc()` method has reliable behavior only
  from Java 9+
- `java.net.http.HttpClient` (for webhook and registry HTTP calls) — Java 11+
- `HttpClient.orTimeout()` (CompletableFuture) — Java 9+
- Text blocks (`"""..."""`) — Java 15+
- Records — Java 16+
- Pattern matching for instanceof (`if (x instanceof Y y)`) — Java 16+

**Impact:** Applications running on Java 8 or Java 10 cannot use this agent.

**Partial fix possible:** Removing text blocks, records, and pattern matching
and replacing with traditional Java 8 syntax would lower the minimum to Java 9.
The HttpClient dependency requires Java 11. Going below Java 11 would require
a third-party HTTP client library (OkHttp, Apache HttpClient) — adding another
shaded dependency.

---

### 5.3 Observer Effect Is Inherent

**The limit:**
Any Java profiler running inside the JVM heap it measures will always have some
observer effect. Every object the agent creates adds to the heap it is measuring.
Every CPU cycle the agent consumes is a cycle unavailable to the application.

Even with lock-free buffers, daemon threads, and adaptive sampling, there is a
floor on overhead that cannot be eliminated in pure Java.

**The only way to eliminate this:** Write the profiler in native code outside
the JVM, as async-profiler does. This is out of scope for a pure-Java agent.

**The honest number:** At 10ms interval, ~2% CPU overhead on CPU-bound workloads.
This number cannot be reduced to zero while maintaining the same data collection
frequency in pure Java.

---

### 5.4 Shallow Bean Size Estimation Cannot Be Made Exact Without a Heap Dump

**The limit:**
The agent estimates bean sizes using reflection and `Instrumentation.getObjectSize()`.
Reflection-based object graph walking cannot determine which objects are
exclusively owned by a specific bean and which are shared across multiple beans.

For example: a `List<User>` might be referenced by both a `UserService` bean
and a `UserCacheService` bean. Counting it under both double-counts the memory.
Counting it under neither under-counts both.

**The only way to get exact attribution:** Parse a full heap dump and build an
object ownership graph. This is what Eclipse MAT does. It requires pausing the
JVM to take the dump and minutes of analysis time.

**The agent's position:** Directional accuracy for ranking, not exact accounting.

---

### 5.5 Spring WebFlux Endpoint Tracking Cannot Be Added Trivially

**The limit:**
WebFlux request handling is inherently asynchronous. A request starts on one
thread, suspends while waiting for I/O, and completes on a different thread.
The timing pattern (enter on thread A, exit on thread B) is fundamentally
incompatible with the `@Advice.OnMethodEnter` / `@Advice.OnMethodExit` pattern
used for DispatcherServlet.

**Impact:** WebFlux applications get no endpoint tracking.

**A solution exists but is complex:** Track request timing via
`WebFilter` or `WebExceptionHandler` rather than `DispatcherHandler` advice.
These operate at the HTTP layer and see the full request lifecycle including
async completion. But correlating heap state at start and end of an async
request requires storing state across thread switches — a non-trivial problem
that increases agent complexity significantly.

---

## 6. Soft Limits — Things That Can Be Tuned

These are limits that exist at the default configuration but can be changed
via properties.

---

### 6.1 Sampling Interval

| Setting | Default | Minimum | Maximum | Impact |
|---|---|---|---|---|
| `profiler.sampling.interval.ms` | 10ms | 5ms | No hard cap | Below 5ms: observer effect dominates. Above 100ms: heap chart becomes too coarse. |

At 1ms: the sampler runs 1000 times/second. On most hardware this causes 5–15%
CPU overhead and the heap measurements become dominated by the sampler's own
allocations — the observer effect destroys accuracy.

At 50ms (adaptive throttled rate): 20 samples/second, ~0.4% overhead. Accuracy
is sufficient for trend analysis and leak detection but the heap chart loses
resolution — short-lived spikes are missed.

**Practical recommendation:** 10ms for local development, 50ms for staging,
disable for production.

---

### 6.2 Ring Buffer Capacity

| Setting | Default | Minimum | Maximum | Impact |
|---|---|---|---|---|
| Heap buffer | 1000 slots | 100 | 100,000 | Memory used: ~200 bytes per slot = 200KB at default |
| GC buffer | 500 slots | 50 | 10,000 | Each GcEvent is ~100 bytes |
| Endpoint buffer | 2000 slots | 200 | 50,000 | Each EndpointSample is ~80 bytes |

Increasing capacity reduces the risk of dropped samples during heavy load but
increases agent heap usage. At 10ms interval the heap buffer covers 10 seconds —
more than enough for the 5-second aggregation cycle.

---

### 6.3 Persistence Queue Capacity

| Setting | Default | Safe Range | Impact |
|---|---|---|---|
| `QUEUE_CAPACITY` in PersistenceWriter | 5000 entries | 1000–20,000 | Higher: less data loss on slow disk. Lower: less agent memory use. |

At 100 samples/second (10ms interval), 5000 capacity = 50 seconds of buffer.
If SQLite falls behind for more than 50 seconds, samples start dropping.
On healthy hardware with fast SSD, SQLite writes batches of 500 rows in under
10ms — the queue never fills.

---

### 6.4 Bean Graph Walk Depth

| Setting | Default | Range | Impact |
|---|---|---|---|
| `MAX_GRAPH_DEPTH` in BeanMemoryMapper | 3 | 1–5 | Higher: more accurate but slower and more GC pressure. |

Depth 1 (shallow only): very fast, very inaccurate.
Depth 3 (default): reasonable accuracy, acceptable performance.
Depth 5: captures most objects but can take 3–5 seconds for large bean graphs
and generates significant GC pressure from reflection calls.

---

### 6.5 Multi-Instance Dead Threshold

| Setting | Default | Minimum | Impact |
|---|---|---|---|
| `DEAD_THRESHOLD_MS` in InstanceInfo | 90,000ms (90s) | 45,000ms | Lower: faster dead instance removal. Higher: more tolerance for slow heartbeats. |

The heartbeat interval is 30 seconds. At 90-second threshold, an instance
must miss 3 consecutive heartbeats before being declared dead. This tolerates
two consecutive failures (network blip, GC pause delaying heartbeat) while
still detecting genuinely dead instances reasonably quickly.

---

### 6.6 Leak Detection Window

| Setting | Default | Minimum | Maximum | Impact |
|---|---|---|---|---|
| `profiler.leak.detection.window.ms` | 60,000ms (60s) | 30,000ms | 300,000ms | Shorter: faster detection, more false positives. Longer: fewer false positives, slower detection. |

A window shorter than 30 seconds will fire WARN warnings on normal post-GC
heap growth. A window longer than 5 minutes reduces the agent's ability to
detect slow leaks in time to be useful.

---

## 7. Comparison to Existing Tools

---

### 7.1 Feature Comparison Table

| Feature | This Agent | JFR | async-profiler | Micrometer + Prometheus | Datadog APM |
|---|---|---|---|---|---|
| Zero config | ✅ | ✅ | ❌ Requires shell flags | ❌ Requires annotations | ❌ Requires account + config |
| Spring-aware beans | ✅ | ❌ | ❌ | ❌ | ✅ (paid tier) |
| Auto endpoint tracking | ✅ | ❌ | ❌ | ❌ Requires @Timed | ✅ |
| Leak detection | ✅ | ✅ (raw data) | ❌ | ❌ | ✅ |
| Built-in dashboard | ✅ | ❌ JMC required | ❌ | ❌ Grafana required | ✅ |
| Metric persistence | ✅ SQLite | ✅ .jfr file | ❌ | ✅ TSDB | ✅ cloud |
| Multi-instance | ✅ peer-to-peer | ❌ | ❌ | ✅ via Prometheus | ✅ |
| Webhook alerting | ✅ | ❌ | ❌ | ✅ via AlertManager | ✅ |
| CPU overhead | ~2% @ 10ms | <1% | <1% | 1-3% | 3-8% |
| GraalVM native | ❌ | ✅ (JFR in native since JDK 21) | ✅ | ✅ | ✅ |
| Spring WebFlux | ❌ | ✅ | ✅ | ✅ | ✅ |
| Production-grade | ❌ No auth | ✅ | ✅ | ✅ | ✅ |
| Cost | Free | Free (JDK built-in) | Free | Free + infra cost | $$$$ |

---

### 7.2 Use Case Positioning

**Use this agent when:**
- You want to diagnose a Spring Boot performance problem locally in under 60 seconds
- You are in staging and cannot install Prometheus + Grafana
- You need heap and endpoint data together without annotating your code
- You want to compare heap behavior before and after a change across restarts
- You are demonstrating JVM internals knowledge in a portfolio or interview

**Use JFR when:**
- You need the lowest possible overhead (built-in JVM, C++ implementation)
- You are profiling native GraalVM applications
- You need CPU profiling and method-level hotspot analysis
- You are on Java 11+ with access to JDK Mission Control

**Use async-profiler when:**
- You need CPU profiling at the method level (flame graphs)
- You need allocation profiling by call site
- You are on any JVM including non-HotSpot implementations

**Use Micrometer + Prometheus when:**
- You need long-term metric storage (weeks, months)
- You already have Grafana infrastructure
- You need custom business metrics alongside JVM metrics
- You want to define your own alerting rules

**Use Datadog or Dynatrace when:**
- You need production-grade APM with security, compliance, and support
- You need distributed tracing across microservices
- You have the budget

---

## 8. What This Agent Is Not

Being explicit about scope prevents misuse.

---

### 8.1 Not a Production APM Tool

The agent has no authentication on its HTTP API. In production, this is a
security vulnerability — internal application structure is exposed to anyone
who can reach port 7070. It was explicitly designed for local development and
staging environments where network access is controlled.

### 8.2 Not a CPU Profiler

The agent does not produce flame graphs, call trees, or method-level CPU usage
breakdowns. It measures heap and latency. For CPU profiling, use async-profiler.

### 8.3 Not a Distributed Tracer

The agent tracks per-endpoint latency within a single JVM. It does not trace
requests across multiple services (service A calls service B calls service C).
For distributed tracing, use OpenTelemetry.

### 8.4 Not a Thread Profiler

The agent does not track thread states, detect deadlocks, or show thread CPU
consumption. These features were scoped to v2.

### 8.5 Not a Heap Dump Analyzer

The agent gives directional heap estimates. It cannot tell you exactly which
objects are causing a leak, which call site allocated them, or which reference
is keeping them alive. For that, use `jmap -dump:format=b` and Eclipse MAT.

### 8.6 Not Compatible With High-Frequency Trading or Ultra-Low Latency Systems

Applications where every microsecond matters should not run any agent. Even the
2% CPU overhead and the occasional 50µs sampling operation are unacceptable in
systems with latency SLAs under 1ms.

---

## 9. Decision Guide — When to Use and When Not To

A simple checklist to decide if this agent is the right tool.

---

### Use This Agent If:

- ✅ Your application is Spring Boot or Quarkus (JVM mode)
- ✅ You are running Java 11 or higher
- ✅ You want heap and endpoint data without writing code
- ✅ You are in local development or a firewalled staging environment
- ✅ Your application handles under 2000 req/s at peak
- ✅ You want data to survive across restarts (persistence enabled)
- ✅ You want to receive alerts when memory leaks are detected

### Do Not Use This Agent If:

- ❌ You are compiling to GraalVM native binary
- ❌ You are using Spring WebFlux and need endpoint tracking
- ❌ Port 7070 would be publicly accessible without a firewall
- ❌ You need CPU method-level profiling (use async-profiler instead)
- ❌ You need distributed tracing (use OpenTelemetry instead)
- ❌ You are running a high-frequency trading or sub-millisecond latency system
- ❌ Your application has more than 800 Spring beans (bean scan will be slow)
- ❌ You need production-grade APM with compliance and SLA guarantees

### Reduce Sampling Interval If:

- ⚠️ Your application handles more than 500 req/s (or enable adaptive sampling)
- ⚠️ `/profiler/status` shows `droppedSamples > 0`
- ⚠️ Agent CPU overhead is measurable in your benchmark environment

### Disable Persistence If:

- ⚠️ You are running on a read-only filesystem
- ⚠️ Disk space is critically constrained (SQLite can grow to 500MB over 7 days)
- ⚠️ Your filesystem has very high write latency (network storage, some Docker volumes)

---

*This document covers the JVM Profiler Agent v1.0.0.*
*For implementation details, see the Phase 1–6 implementation guides.*
*For API reference, see the Developer Guide.*
