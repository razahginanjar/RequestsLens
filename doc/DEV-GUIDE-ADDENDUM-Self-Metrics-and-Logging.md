# JVM Profiler Agent — Developer Guide Addendum
## Section 13: Agent Self-Monitoring, Self-Metrics & Logging Rules

> **Who this is for:** Junior developers joining the project. This section explains
> *why* self-monitoring exists, *what* it tracks, *how* to implement each piece,
> and *where* logging is allowed — with enough detail that you can write the code
> without guessing.

---

## 13.1 Why the Agent Needs to Monitor Itself

The agent measures another application's JVM. But the agent itself runs *inside*
the same JVM. That creates a paradox:

> **If the agent is broken or overloaded, how do you know?**

Without self-monitoring you have no answer. A developer might see heap climbing
and blame their Spring app — when in reality the agent's SQLite write queue is
full and dropping samples silently, making the heap chart inaccurate.

Self-monitoring solves three problems:

| Problem | Without Self-Monitoring | With Self-Monitoring |
|---|---|---|
| Dropped samples | Silent — developer gets wrong data | `droppedSamples` counter visible at `/profiler/status` |
| SQLite falling behind | No signal — writes silently slow | `persistenceQueueDepth` visible at `/profiler/status` |
| Webhook failures | Silent — alerts never arrive | `webhookFailures` counter visible |
| Agent's own heap | Unknown — overhead claim unproven | `agentHeapUsedBytes` visible |
| Sampling disrupted by GC | Unknown | `samplingPausedByGcCount` visible |

Self-metrics are also the **proof** behind the overhead claim. When you write in
your README "agent overhead is less than 2% CPU at 10ms sampling interval", the
`agentHeapUsedBytes` and `effectiveIntervalMs` fields in `/profiler/status` are
the live evidence.

---

## 13.2 The Core Rule: Where Self-Metrics Are Allowed

Before writing any code in this project, understand this hierarchy:

```
TIER 1 — HOT PATH (called thousands of times per second)
  HeapSampler.sample()          ← called every 10ms
  GcListener.onNotification()   ← called on every GC event
  DispatcherServlet advice       ← called on every HTTP request

  Rule: ZERO allocation. ZERO logging. ONLY AtomicLong / LongAdder increments.

TIER 2 — WARM PATH (called every few seconds)
  Aggregation daemon loop        ← every aggregation cycle
  PersistenceWriter.flush()      ← every 5 seconds
  AlertEvaluator.evaluate()      ← every aggregation cycle
  AdaptiveSamplingController     ← every aggregation cycle

  Rule: Minimal allocation. Logging ONLY on state change (not on every cycle).
        LongAdder increments fine.

TIER 3 — COLD PATH (called rarely or once)
  Agent startup / shutdown
  Config loading
  Webhook failure after retries
  Adaptive sampler state change (NORMAL ↔ THROTTLED)
  Leak warning detection
  Registry heartbeat failure

  Rule: Normal logging allowed. Normal Java code. No restrictions.
```

**Burn this into your memory:** If the method you are editing is called more than
once per second — treat it as Tier 1. When in doubt, go stricter.

---

## 13.3 AgentSelfMetrics — The Implementation

### 13.3.1 What It Is

`AgentSelfMetrics` is a single class that lives on `CollectorRegistry`. It holds
a small set of counters and gauges tracking the agent's own health. No threads,
no I/O, no allocation on the write path.

### 13.3.2 Why LongAdder and Not AtomicLong?

You might know `AtomicLong` already. Both work, but for high-frequency increment
operations (like counting every dropped sample), `LongAdder` is better:

- `AtomicLong` uses a single memory location. Under high contention, threads spin
  waiting to write to it — this is called CAS (compare-and-swap) contention.
- `LongAdder` internally spreads the count across multiple cells and sums them on
  read. Writes almost never contend. Reads are slightly more expensive but reads
  are rare (only when someone calls `/profiler/status`).

**Use `LongAdder` for counters that are incremented frequently.**
**Use `volatile long` for gauges that are written by one thread and read by others.**

### 13.3.3 The Class

Create this file at:
`src/main/java/agent/core/AgentSelfMetrics.java`

```java
package agent.core;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.concurrent.atomic.LongAdder;

/**
 * Tracks the agent's own health metrics.
 *
 * Design rules:
 *  - All increment methods must be allocation-free (safe to call from Tier 1 paths)
 *  - All read methods (snapshot()) are only called from the HTTP API thread — cost is irrelevant
 *  - No logging in this class — it is called from hot paths
 */
public final class AgentSelfMetrics {

    // ── Counters (written frequently, LongAdder) ──────────────────────────

    /**
     * Number of heap samples dropped because the ring buffer was full.
     * If this is non-zero, increase profiler.buffer.capacity or reduce sampling interval.
     */
    private final LongAdder droppedSamples = new LongAdder();

    /**
     * Number of heap samples dropped by the PersistenceWriter because its
     * BlockingQueue was full (capacity 5000). If this is non-zero, SQLite
     * is not keeping up with the write rate.
     */
    private final LongAdder droppedPersistenceSamples = new LongAdder();

    /**
     * Number of webhook POST attempts that failed after all retries.
     * Each increment = one alert that was never delivered.
     */
    private final LongAdder webhookDeliveryFailures = new LongAdder();

    /**
     * Number of times the HeapSampler's scheduled tick was delayed by more
     * than 50% of the configured interval — indicating GC paused the sampler
     * thread or the system was under CPU pressure.
     */
    private final LongAdder samplingDelays = new LongAdder();

    /**
     * Number of times the registry heartbeat POST failed.
     */
    private final LongAdder registryHeartbeatFailures = new LongAdder();

    // ── Gauges (written by one thread, read by HTTP thread) ───────────────

    /**
     * Current depth of the PersistenceWriter's BlockingQueue.
     * Written by the persistence daemon. Read by HTTP thread.
     * If this is consistently above 4000 (capacity 5000), raise the alarm.
     */
    private volatile int persistenceQueueDepth = 0;

    /**
     * Timestamp (epoch ms) of the last successful heap sample.
     * If this stops updating, the HeapSampler has stalled.
     */
    private volatile long lastSampleTimestampMs = 0;

    /**
     * Agent start time — used to compute uptime.
     */
    private final long startedAtMs = System.currentTimeMillis();

    // ── JMX reference (read-only, safe) ──────────────────────────────────
    private final MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();

    // ── Increment methods (Tier 1 safe — no allocation, no logging) ──────

    public void incrementDroppedSamples()            { droppedSamples.increment(); }
    public void incrementDroppedPersistence()        { droppedPersistenceSamples.increment(); }
    public void incrementWebhookFailures()           { webhookDeliveryFailures.increment(); }
    public void incrementSamplingDelays()            { samplingDelays.increment(); }
    public void incrementRegistryHeartbeatFailures() { registryHeartbeatFailures.increment(); }

    // ── Setter methods (Tier 2 safe) ──────────────────────────────────────

    public void setPersistenceQueueDepth(int depth)  { persistenceQueueDepth = depth; }
    public void setLastSampleTimestampMs(long ts)    { lastSampleTimestampMs = ts; }

    // ── Snapshot (Tier 3 only — called from HTTP thread) ─────────────────

    /**
     * Returns a point-in-time snapshot of all self-metrics.
     * Only call this from the HTTP API handler — never from hot paths.
     */
    public Snapshot snapshot() {
        long heapUsed = memoryMXBean.getHeapMemoryUsage().getUsed();
        long uptimeMs = System.currentTimeMillis() - startedAtMs;

        return new Snapshot(
            droppedSamples.sum(),
            droppedPersistenceSamples.sum(),
            webhookDeliveryFailures.sum(),
            samplingDelays.sum(),
            registryHeartbeatFailures.sum(),
            persistenceQueueDepth,
            lastSampleTimestampMs,
            heapUsed,
            uptimeMs
        );
    }

    /**
     * Immutable snapshot of self-metrics at a point in time.
     * Safe to serialize to JSON.
     */
    public record Snapshot(
        long droppedSamples,
        long droppedPersistenceSamples,
        long webhookDeliveryFailures,
        long samplingDelays,
        long registryHeartbeatFailures,
        int  persistenceQueueDepth,
        long lastSampleTimestampMs,
        long agentHeapUsedBytes,
        long uptimeMs
    ) {}
}
```

### 13.3.4 Register It on CollectorRegistry

Open `CollectorRegistry.java` and add:

```java
// Add this field
private final AgentSelfMetrics selfMetrics = new AgentSelfMetrics();

// Add this getter
public AgentSelfMetrics selfMetrics() { return selfMetrics; }
```

Every component gets `CollectorRegistry` injected. They call
`registry.selfMetrics().incrementDroppedSamples()` wherever needed.
No static access, no global state.

---

## 13.4 Wiring Self-Metrics Into Each Component

This section shows exactly where to add the increment calls in each component.
Follow these precisely — adding them in the wrong place creates the overhead
problem you are trying to measure.

### 13.4.1 HeapSampler — Track Drops and Delays

The sampler runs on a fixed schedule. Before you write the sample, record the
current time. After writing, check how long it actually took to be scheduled
vs when it was supposed to run. If the delay is more than 50% of the interval,
the sampler was paused (by GC or CPU pressure) — increment `samplingDelays`.

```java
public class HeapSampler {

    private final RingBuffer<HeapSnapshot> buffer;
    private final AgentSelfMetrics selfMetrics;
    private final AgentConfig config;
    private volatile long expectedNextTickMs = 0;

    // Called on the sampler daemon thread every N ms
    void sample() {
        long nowMs = System.currentTimeMillis();

        // ── Delay detection ───────────────────────────────────────────────
        // Was this tick significantly late? (first tick skips the check)
        if (expectedNextTickMs > 0) {
            long delayMs = nowMs - expectedNextTickMs;
            long halfInterval = config.getBaseIntervalMs() / 2;
            if (delayMs > halfInterval) {
                // Tier 1 safe — LongAdder.increment() is allocation-free
                selfMetrics.incrementSamplingDelays();
            }
        }
        expectedNextTickMs = nowMs + config.getBaseIntervalMs();

        // ── Build snapshot (no allocation — reuse fields) ─────────────────
        HeapSnapshot snapshot = buildSnapshot(nowMs);   // reads JMX MemoryMXBean

        // ── Write to ring buffer ──────────────────────────────────────────
        boolean written = buffer.write(snapshot);
        if (!written) {
            // Ring buffer was full — sample dropped
            // Tier 1 safe — LongAdder.increment() is allocation-free
            selfMetrics.incrementDroppedSamples();
            // DO NOT log here — this method runs 100x/sec
        }

        // ── Update last-sample timestamp ──────────────────────────────────
        // volatile write — safe from Tier 1
        selfMetrics.setLastSampleTimestampMs(nowMs);
    }
}
```

> **Junior dev note:** You might want to add a log line when a sample is dropped.
> Don't. `sample()` runs 100 times per second. Even one log statement here would
> allocate a String object on every call, trigger GC more frequently, and make
> the heap measurements you are collecting inaccurate. The counter is your signal.
> Check it at `/profiler/status`.

### 13.4.2 RingBuffer — Return a Boolean Instead of void

The current `RingBuffer.write()` is `void`. Change it to return `boolean` so
callers know if the write succeeded or was dropped:

```java
/**
 * Writes a value to the ring buffer.
 *
 * @return true if written successfully, false if the buffer is at capacity
 *         and the oldest value was overwritten (sample dropped).
 *
 * Implementation note: the ring buffer always writes — it overwrites the
 * oldest slot when full. We return false to signal to the caller that a
 * sample was lost, so they can increment the dropped counter.
 */
public boolean write(T value) {
    long slot = writeIndex.getAndIncrement() % slots.length;

    // If we have lapped the read position, a sample is being overwritten
    boolean overwriting = slots[(int) slot] != null;
    slots[(int) slot] = value;

    return !overwriting;  // false = a sample was dropped (overwritten)
}
```

### 13.4.3 PersistenceWriter — Track Queue Depth and Drops

```java
public class PersistenceWriter {

    private final BlockingQueue<HeapSnapshot> writeQueue =
        new LinkedBlockingQueue<>(5000);
    private final AgentSelfMetrics selfMetrics;

    /**
     * Called from the aggregation daemon thread.
     * MUST be non-blocking — offer() returns false immediately if full.
     * Never use put() here — it blocks the calling thread.
     */
    public void enqueue(HeapSnapshot snapshot) {
        boolean accepted = writeQueue.offer(snapshot);  // non-blocking
        if (!accepted) {
            // Queue is full — SQLite is not keeping up
            // Tier 1 safe — LongAdder.increment() is allocation-free
            selfMetrics.incrementDroppedPersistence();
            // DO NOT log here — called frequently
        }
    }

    /**
     * Called by the persistence daemon every 5 seconds.
     * Tier 2 — minimal logging allowed, only on anomaly.
     */
    void flush() {
        List<HeapSnapshot> batch = new ArrayList<>(1000);
        writeQueue.drainTo(batch, 1000);

        // Update queue depth gauge AFTER draining
        // volatile write — safe from Tier 2
        selfMetrics.setPersistenceQueueDepth(writeQueue.size());

        if (!batch.isEmpty()) {
            repository.batchInsertHeap(batch);
            // Logging here is fine — runs every 5 seconds, not a hot path
            log.fine("Persisted " + batch.size() + " heap samples");
        }
    }
}
```

### 13.4.4 WebhookDispatcher — Track Delivery Failures

```java
private void sendWithRetry(AlertPayload payload, int remainingRetries) {
    try {
        httpPost(config.getWebhookUrl(), toJson(payload));
        // Success — no logging needed, alerts are rare events
    } catch (Exception e) {
        if (remainingRetries > 0) {
            long backoffMs = (long) Math.pow(2, 3 - remainingRetries) * 1000;
            sleep(backoffMs);
            sendWithRetry(payload, remainingRetries - 1);
        } else {
            // All retries exhausted — Tier 3, logging allowed
            log.warning("Webhook delivery failed after 3 retries for alert: "
                + payload.alertType() + " — " + e.getMessage());
            // Increment counter AFTER logging (counter is for status API, log is for ops)
            selfMetrics.incrementWebhookFailures();
        }
    }
}
```

### 13.4.5 InstanceRegistryClient — Track Heartbeat Failures

```java
private void sendHeartbeat() {
    try {
        httpPost(registryUrl + "/registry/instances/" + instanceId + "/heartbeat", "{}");
    } catch (Exception e) {
        // Tier 3 — heartbeat failures are rare, logging is fine
        log.warning("Registry heartbeat failed for instance " + instanceId
            + ": " + e.getMessage());
        selfMetrics.incrementRegistryHeartbeatFailures();
    }
}
```

---

## 13.5 Exposing Self-Metrics on the Status Endpoint

The `/profiler/status` endpoint already exists from Phase 1. Expand its response
to include the self-metrics snapshot.

### 13.5.1 Updated Status Route

```java
// In ApiRoutes.java (or wherever your Javalin routes are defined)
app.get("/profiler/status", ctx -> {
    AgentSelfMetrics.Snapshot self = registry.selfMetrics().snapshot();
    SamplingState sampling         = registry.samplingState();

    // Build a combined status map — Jackson serializes it to JSON
    Map<String, Object> status = new LinkedHashMap<>();

    // ── Instance identity ─────────────────────────────────────────────────
    status.put("instanceId",          config.getInstanceId());
    status.put("uptimeMs",            self.uptimeMs());

    // ── Sampling state ────────────────────────────────────────────────────
    status.put("samplingState",       sampling.getState().name());
    status.put("effectiveIntervalMs", sampling.getEffectiveIntervalMs());
    status.put("baseIntervalMs",      config.getBaseIntervalMs());
    status.put("currentRps",          registry.getCurrentRps());
    status.put("rpsThreshold",        config.getMaxRps());

    // ── Self-metrics ──────────────────────────────────────────────────────
    status.put("agentHeapUsedBytes",         self.agentHeapUsedBytes());
    status.put("droppedSamples",             self.droppedSamples());
    status.put("droppedPersistenceSamples",  self.droppedPersistenceSamples());
    status.put("persistenceQueueDepth",      self.persistenceQueueDepth());
    status.put("webhookDeliveryFailures",    self.webhookDeliveryFailures());
    status.put("samplingDelays",             self.samplingDelays());
    status.put("registryHeartbeatFailures",  self.registryHeartbeatFailures());
    status.put("lastSampleTimestampMs",      self.lastSampleTimestampMs());

    ctx.json(status);
});
```

### 13.5.2 What the Response Looks Like

```json
{
  "instanceId":                 "app-1:7070",
  "uptimeMs":                   183421,
  "samplingState":              "NORMAL",
  "effectiveIntervalMs":        10,
  "baseIntervalMs":             10,
  "currentRps":                 47.3,
  "rpsThreshold":               500,
  "agentHeapUsedBytes":         4194304,
  "droppedSamples":             0,
  "droppedPersistenceSamples":  0,
  "persistenceQueueDepth":      12,
  "webhookDeliveryFailures":    0,
  "samplingDelays":             3,
  "registryHeartbeatFailures":  0,
  "lastSampleTimestampMs":      1748000183400
}
```

### 13.5.3 How to Read This Response

| Field | Healthy Value | Warning Sign |
|---|---|---|
| `agentHeapUsedBytes` | < 50MB | > 100MB — agent is leaking or doing too much |
| `droppedSamples` | 0 | Any non-zero — ring buffer too small or interval too low |
| `droppedPersistenceSamples` | 0 | Any non-zero — SQLite write is falling behind |
| `persistenceQueueDepth` | < 500 | > 4000 — persistence is severely behind |
| `webhookDeliveryFailures` | 0 | Any non-zero — webhook receiver is unreachable |
| `samplingDelays` | Low | Growing rapidly — GC pausing the sampler thread |
| `registryHeartbeatFailures` | 0 | Any non-zero — registry instance is unreachable |
| `lastSampleTimestampMs` | Recent (within 2x interval) | Stale — HeapSampler has stalled |

---

## 13.6 Logging Rules — The Complete Specification

### 13.6.1 The Only Allowed Logging Library

Use **`java.util.logging` only**. No SLF4J. No Log4j. No Logback.

Why: SLF4J and Log4j would need to be shaded into the agent JAR to avoid
conflicts with the target application's logging framework. Shading a logging
framework causes subtle bugs because logging configuration (log levels, appenders)
stops working for one of them. `java.util.logging` is built into the JDK —
no dependency, no conflict, no shading needed.

```java
// At the top of every agent class that needs logging — this is it
import java.util.logging.Logger;

public class PersistenceWriter {
    // One logger per class — this is the standard pattern
    private static final Logger log = Logger.getLogger(PersistenceWriter.class.getName());

    void flush() {
        // Fine = DEBUG level
        log.fine("Flushing persistence batch");

        // Warning = WARN level
        log.warning("Persistence queue is above 80% capacity: " + writeQueue.size());

        // Severe = ERROR level
        log.severe("Persistence flush failed completely: " + e.getMessage());
    }
}
```

### 13.6.2 Log Levels and When to Use Each

| Level | Method | When to Use |
|---|---|---|
| FINE | `log.fine()` | Debug information useful during development. Disabled in production by default. |
| INFO | `log.info()` | Normal lifecycle events — agent started, phase changed, config loaded. |
| WARNING | `log.warning()` | Recoverable problems — heartbeat failed but will retry, queue depth high. |
| SEVERE | `log.severe()` | Unrecoverable errors — agent cannot start, config is invalid. |

### 13.6.3 Logging Decision Tree

Before adding any log statement, ask these questions in order:

```
1. Is this code in a Tier 1 hot path?
   (HeapSampler, GcListener callback, Byte Buddy advice)
   → YES: Do NOT log. Use a LongAdder counter instead. Stop here.

2. Is this code in a Tier 2 warm path?
   (Aggregation daemon, PersistenceWriter.flush, AlertEvaluator)
   → YES: Log ONLY on state change or anomaly. Never on every cycle.
          Example: log when queue depth first exceeds 80%, not on every flush.

3. Is this a normal lifecycle event?
   (startup, shutdown, config loaded, phase change)
   → Log at INFO level.

4. Is this a recoverable error?
   (network timeout, retry, transient failure)
   → Log at WARNING level. Include the error message. Do not include a stack trace
      unless it adds information (most IOExceptions do not).

5. Is this an unrecoverable error?
   (cannot bind port, config file corrupt, schema migration failed)
   → Log at SEVERE level. Include the full exception. Then fail fast.
```

### 13.6.4 What Good Log Messages Look Like

Bad log messages (do not write these):
```java
log.info("done");                          // No context
log.info("Error occurred");                // No detail
log.warning("Something went wrong: " + e); // 'Something' is useless
log.fine("sample: " + snapshot);           // toString() allocates on Tier 1
```

Good log messages:
```java
log.info("JVM Profiler Agent started — port=" + config.getPort()
    + " interval=" + config.getBaseIntervalMs() + "ms"
    + " instanceId=" + config.getInstanceId());

log.warning("Webhook delivery failed (attempt 2/3) — url=" + url
    + " error=" + e.getMessage());

log.warning("Persistence queue depth at 82% capacity (" + depth
    + "/5000) — SQLite writes may be falling behind");

log.severe("Failed to initialize SQLite schema at path="
    + config.getPersistencePath() + ": " + e.getMessage());
```

The pattern is: **who failed + what failed + relevant numbers + error message**.
No stack traces unless they add information. No `e.printStackTrace()` — ever.

### 13.6.5 The One Logging Anti-Pattern to Avoid

```java
// WRONG — string concatenation happens even if the log level is disabled
log.fine("HeapSampler tick at " + System.currentTimeMillis()
    + " used=" + snapshot.usedBytes());

// RIGHT — use Supplier lambda so the string is only built if FINE is enabled
log.fine(() -> "HeapSampler tick at " + System.currentTimeMillis()
    + " used=" + snapshot.usedBytes());
```

This matters for `fine()` and `finer()` calls because FINE level is often
disabled. In the wrong version, the string is allocated and concatenated on every
call even though the log is never written. In the right version, the lambda is
only evaluated if the level is enabled.

> This only matters for FINE/FINER levels because those are the ones likely to be
> disabled. INFO, WARNING, and SEVERE are almost always enabled, so the difference
> is negligible for those.

---

## 13.7 Dashboard: Self-Metrics Panel

Add a small status panel to the dashboard that reads `/profiler/status` every
5 seconds and highlights any unhealthy metrics in amber or red.

### 13.7.1 What to Display

```
┌─ Agent Health ─────────────────────────────────────────────────────────┐
│  Instance:      app-1:7070          Uptime: 3m 4s                      │
│  Sampling:      NORMAL @ 10ms       Current RPS: 47.3                  │
│  Agent Heap:    4.0 MB                                                  │
│                                                                         │
│  Dropped Samples:         0    ✓                                        │
│  Dropped Persistence:     0    ✓                                        │
│  Persistence Queue:      12 / 5000  ✓                                   │
│  Webhook Failures:        0    ✓                                        │
│  Sampling Delays:         3    ✓                                        │
│  Heartbeat Failures:      0    ✓                                        │
└─────────────────────────────────────────────────────────────────────────┘
```

### 13.7.2 Color Rules for the Panel

```javascript
function healthColor(field, value) {
  const rules = {
    droppedSamples:            v => v === 0   ? 'green' : 'red',
    droppedPersistenceSamples: v => v === 0   ? 'green' : 'red',
    persistenceQueueDepth:     v => v < 1000  ? 'green'
                                  : v < 4000  ? 'amber' : 'red',
    webhookDeliveryFailures:   v => v === 0   ? 'green' : 'amber',
    samplingDelays:            v => v < 10    ? 'green'
                                  : v < 100   ? 'amber' : 'red',
    registryHeartbeatFailures: v => v === 0   ? 'green' : 'amber',
  };
  return rules[field] ? rules[field](value) : 'green';
}
```

### 13.7.3 Stale Sampler Detection

Add this check in the dashboard JS. If `lastSampleTimestampMs` is more than
3× the effective interval in the past, the sampler has stalled:

```javascript
function isSamplerStale(status) {
  const staleCutoff = status.effectiveIntervalMs * 3;
  const age = Date.now() - status.lastSampleTimestampMs;
  return age > staleCutoff;
}

// If stale, show a red banner: "⚠ Heap sampler appears stalled — last sample was Xs ago"
```

---

## 13.8 Unit Tests for Self-Metrics

Write these tests in `AgentSelfMetricsTest.java`. Each test must run in under
500ms (no Thread.sleep).

### 13.8.1 Counter Correctness

```java
@Test
void droppedSamplesCountsAccurately() {
    AgentSelfMetrics metrics = new AgentSelfMetrics();

    metrics.incrementDroppedSamples();
    metrics.incrementDroppedSamples();
    metrics.incrementDroppedSamples();

    assertEquals(3, metrics.snapshot().droppedSamples());
}

@Test
void allCountersStartAtZero() {
    AgentSelfMetrics metrics = new AgentSelfMetrics();
    AgentSelfMetrics.Snapshot snap = metrics.snapshot();

    assertEquals(0, snap.droppedSamples());
    assertEquals(0, snap.droppedPersistenceSamples());
    assertEquals(0, snap.webhookDeliveryFailures());
    assertEquals(0, snap.samplingDelays());
    assertEquals(0, snap.registryHeartbeatFailures());
    assertEquals(0, snap.persistenceQueueDepth());
}
```

### 13.8.2 Concurrent Safety

```java
@Test
void droppedSamplesIsThreadSafe() throws InterruptedException {
    AgentSelfMetrics metrics = new AgentSelfMetrics();
    int threadCount  = 10;
    int callsPerThread = 1000;

    ExecutorService pool = Executors.newFixedThreadPool(threadCount);
    for (int i = 0; i < threadCount; i++) {
        pool.submit(() -> {
            for (int j = 0; j < callsPerThread; j++) {
                metrics.incrementDroppedSamples();
            }
        });
    }
    pool.shutdown();
    pool.awaitTermination(5, TimeUnit.SECONDS);

    // LongAdder must give exact count under concurrent writes
    assertEquals((long) threadCount * callsPerThread,
        metrics.snapshot().droppedSamples());
}
```

### 13.8.3 Persistence Queue Drop Integration

```java
@Test
void persistenceWriterDropsSilentlyWhenQueueFull() {
    // Create a PersistenceWriter with a tiny queue for testing
    PersistenceWriter writer = new PersistenceWriter(
        mockRepository, selfMetrics, /* queueCapacity= */ 2);

    // Fill the queue
    writer.enqueue(mockSnapshot());
    writer.enqueue(mockSnapshot());

    // This one should be dropped — queue is full
    writer.enqueue(mockSnapshot());

    assertEquals(1, selfMetrics.snapshot().droppedPersistenceSamples());
}
```

### 13.8.4 HeapSampler Delay Detection

```java
@Test
void heapSamplerDetectsLateScheduling() {
    AgentSelfMetrics metrics = new AgentSelfMetrics();
    // Configure a 10ms interval — delay > 5ms should trigger the counter
    HeapSampler sampler = new HeapSampler(mockBuffer, metrics, configWith(10));

    // Simulate a tick that arrived 8ms late
    sampler.simulateTick(expectedTime = now, actualTime = now + 18);

    assertEquals(1, metrics.snapshot().samplingDelays());
}
```

> **Note for junior devs:** The `simulateTick` method is a package-private
> test hook on `HeapSampler` that bypasses the scheduler and calls the sampling
> logic with controlled timestamps. Add it with a comment: `// Test hook — do not
> call from production code`. This is a legitimate testing pattern — it lets you
> test timing logic without using `Thread.sleep`.

---

## 13.9 Where Self-Metrics Fit in the Phase Plan

Self-metrics are a **cross-cutting concern** — they touch multiple phases. Here
is exactly when to implement each piece:

| Phase | Self-Metrics Work |
|---|---|
| Phase 1 | Create `AgentSelfMetrics` class. Wire into `CollectorRegistry`. Add `droppedSamples` to `HeapSampler`. Update `RingBuffer.write()` to return boolean. Update `/profiler/status` to include self-metrics. |
| Phase 2 | No self-metrics work. |
| Phase 3 | Add `droppedPersistenceSamples` and `persistenceQueueDepth` to `PersistenceWriter.flush()`. |
| Phase 4 | Add `webhookDeliveryFailures` to `WebhookDispatcher`. Add `samplingDelays` to `HeapSampler` (delay detection). |
| Phase 5 | Add `registryHeartbeatFailures` to `InstanceRegistryClient`. Add self-metrics panel to dashboard. Add stale sampler detection to dashboard. |
| Phase 6 | Include `agentHeapUsedBytes` in benchmark results. Document self-metrics in README. |

---

## 13.10 Common Mistakes to Avoid

These are real mistakes junior developers make in agent code. Read them before
you write a single line.

---

**Mistake 1: Logging in a hot path**

```java
// WRONG — sample() runs 100x/sec
void sample() {
    HeapSnapshot s = buildSnapshot();
    log.fine("Sampled heap: " + s.usedBytes()); // allocates String every time
    buffer.write(s);
}

// RIGHT — no logging, increment counter on drop only
void sample() {
    HeapSnapshot s = buildSnapshot();
    if (!buffer.write(s)) {
        selfMetrics.incrementDroppedSamples(); // allocation-free
    }
    selfMetrics.setLastSampleTimestampMs(System.currentTimeMillis());
}
```

---

**Mistake 2: Using synchronized in a hot path**

```java
// WRONG — synchronized blocks the thread, causes contention
public synchronized void write(HeapSnapshot s) {
    slots[index % slots.length] = s;
    index++;
}

// RIGHT — AtomicLong provides lock-free ordering
public boolean write(HeapSnapshot s) {
    long slot = writeIndex.getAndIncrement() % slots.length;
    boolean overwriting = slots[(int) slot] != null;
    slots[(int) slot] = s;
    return !overwriting;
}
```

---

**Mistake 3: Blocking the aggregation thread with network I/O**

```java
// WRONG — sendWebhook() blocks the aggregation thread for up to seconds
void evaluateAlerts() {
    if (leakDetected()) {
        sendWebhook(buildPayload()); // blocks here if network is slow
    }
}

// RIGHT — dispatch to a separate thread pool
void evaluateAlerts() {
    if (leakDetected()) {
        webhookDispatcher.dispatch(buildPayload()); // returns immediately
    }
}
```

---

**Mistake 4: Allocating inside Byte Buddy advice**

```java
// WRONG — creates a new HashMap on every HTTP request
@Advice.OnMethodEnter
public static Map<String, Object> onEnter(@Advice.Argument(0) HttpServletRequest req) {
    Map<String, Object> ctx = new HashMap<>();   // allocation on every request
    ctx.put("start", System.nanoTime());
    ctx.put("path", req.getRequestURI());
    return ctx;
}

// RIGHT — use primitives and ThreadLocal for state across enter/exit
@Advice.OnMethodEnter
public static long onEnter() {
    return System.nanoTime();   // primitive, zero allocation
}

@Advice.OnMethodExit
public static void onExit(@Advice.Enter long startNs,
                           @Advice.Argument(0) HttpServletRequest req) {
    long latencyMs = (System.nanoTime() - startNs) / 1_000_000;
    EndpointTracker.record(req.getMethod(), req.getRequestURI(), latencyMs);
}
```

---

**Mistake 5: Using put() instead of offer() on the persistence queue**

```java
// WRONG — blocks the calling thread if queue is full
public void enqueue(HeapSnapshot s) {
    writeQueue.put(s);   // can block indefinitely
}

// RIGHT — offer() returns immediately, never blocks
public void enqueue(HeapSnapshot s) {
    boolean accepted = writeQueue.offer(s);
    if (!accepted) selfMetrics.incrementDroppedPersistence();
}
```

---

*End of Section 13. When in doubt about whether something belongs in a hot path,
ask the question: "How many times per second will this line execute?" If the
answer is more than 10, treat it as Tier 1.*
