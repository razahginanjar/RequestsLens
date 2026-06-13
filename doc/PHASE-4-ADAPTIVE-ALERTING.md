# Phase 4 — Adaptive Sampling & Alerting
## Full Implementation Guide

> **Goal by end of this phase:** The agent automatically reduces its sampling
> frequency when the target application exceeds 500 req/s, protecting throughput
> under load. Leak warnings and GC overhead breaches are pushed to a configured
> webhook URL as HTTP POST payloads — enabling Slack, PagerDuty, or any HTTP receiver.
>
> **Estimated time:** 3–4 days
> **Branch:** `phase/4-adaptive-alerting`
> **Prerequisite:** Phase 3 complete and merged to `develop`

---

## Before You Start

### What Is Adaptive Sampling?

At the default 10ms interval, the HeapSampler runs 100 times per second.
Under light load this costs less than 2% CPU. But at 600 req/s, each request
triggers the DispatcherServlet advice, and the HeapSampler still fires 100 times
a second on top of that. The combined overhead can exceed 5%.

Adaptive sampling detects when the application is under high load (measured
by RPS) and tells the HeapSampler to slow down — for example, from 10ms to 50ms
interval. This reduces sampler overhead by 5x while the application handles peak
traffic. When traffic drops, the sampler returns to its normal rate.

### What Is a Webhook?

A webhook is an HTTP POST request sent by our agent to a URL you configure.
The receiver — Slack, PagerDuty, a custom endpoint — reads the JSON body and
does whatever it wants with it (sends a Slack message, pages an on-call engineer,
writes to a log).

Our webhook dispatcher runs on a separate daemon thread so it never blocks the
collection hot path, even if the receiver is slow or unreachable.

### Alert Lifecycle — Key Concept

Alerts should fire **once per state change**, not once per evaluation cycle.
If the heap is leaking, you want one alert, not one every 5 seconds.

The rule:
- Fire alert when condition first becomes true
- Fire RESOLVED when condition first becomes false again
- Suppress duplicates within 60 seconds

---

## Step 1 — New Models

### 1.1 SamplingState

`src/main/java/agent/model/SamplingState.java`

```java
package agent.model;

/**
 * The two states of the adaptive sampling controller.
 *
 * NORMAL   — sampling at the configured base interval (e.g. 10ms)
 * THROTTLED — sampling at a reduced rate (base × multiplier, e.g. 50ms)
 */
public enum SamplingState {
    NORMAL,
    THROTTLED
}
```

### 1.2 AlertPayload

`src/main/java/agent/model/AlertPayload.java`

```java
package agent.model;

import java.util.Map;

/**
 * The JSON payload sent to webhook endpoints when an alert fires.
 *
 * Designed to be compatible with Slack's "incoming webhook" format
 * if the receiver wraps it, and generic enough for any HTTP receiver.
 */
public record AlertPayload(
    /** Which agent instance generated this alert */
    String instanceId,

    /** The type of alert: LEAK_WARNING, GC_OVERHEAD, LEAK_RESOLVED, GC_RESOLVED */
    String alertType,

    /** WARN, CRITICAL, or RESOLVED */
    String severity,

    /** Human-readable description of what triggered the alert */
    String message,

    /** When the alert was generated — epoch milliseconds */
    long timestampMs,

    /** Additional context — heap growth %, GC overhead %, etc. */
    Map<String, Object> metadata
) {}
```

### 1.3 LeakWarning

`src/main/java/agent/model/LeakWarning.java`

```java
package agent.model;

/**
 * A detected memory leak pattern — heap growing without GC relief.
 */
public record LeakWarning(
    long   detectedAtMs,
    long   heapGrowthBytes,
    long   windowMs,
    double growthPercent,

    /** "WARN" (>10% growth) or "CRITICAL" (>25% growth) */
    String severity
) {}
```

---

## Step 2 — SamplingStateHolder

A thread-safe holder for the current sampling state and effective interval.
Written by `AdaptiveSamplingController`, read by `HeapSampler`.

`src/main/java/agent/sampling/SamplingStateHolder.java`

```java
package agent.sampling;

import agent.model.SamplingState;

/**
 * Shared mutable state for the adaptive sampler.
 *
 * <h2>Why volatile?</h2>
 * Two threads access these fields:
 *   Writer: AdaptiveSamplingController (aggregation daemon)
 *   Reader: HeapSampler (sampler daemon)
 *
 * volatile guarantees that the reader always sees the latest value
 * written by the writer, without needing synchronization.
 * This is safe here because there is exactly one writer and one reader,
 * and neither reads-then-writes (no compound operations).
 */
public final class SamplingStateHolder {

    private volatile SamplingState state             = SamplingState.NORMAL;
    private volatile long          effectiveIntervalMs;

    public SamplingStateHolder(long baseIntervalMs) {
        this.effectiveIntervalMs = baseIntervalMs;
    }

    // ── Writer methods (called by AdaptiveSamplingController) ─────────────
    public void setState(SamplingState s, long intervalMs) {
        this.state              = s;
        this.effectiveIntervalMs = intervalMs;
    }

    // ── Reader methods (called by HeapSampler) ────────────────────────────
    public SamplingState getState()              { return state; }
    public long          getEffectiveIntervalMs(){ return effectiveIntervalMs; }
}
```

---

## Step 3 — AdaptiveSamplingController

`src/main/java/agent/sampling/AdaptiveSamplingController.java`

```java
package agent.sampling;

import agent.core.AgentConfig;
import agent.model.SamplingState;
import java.util.logging.Logger;

/**
 * Controls the HeapSampler's interval based on observed request load.
 *
 * <h2>State machine</h2>
 *
 *   NORMAL ──────────────────────────────────────────► THROTTLED
 *          (RPS > threshold for 3 consecutive cycles)
 *
 *   THROTTLED ──────────────────────────────────────► NORMAL
 *             (RPS < threshold for 10 consecutive cycles)
 *
 * <h2>Why asymmetric thresholds?</h2>
 * Throttling quickly (3 cycles) protects the app as soon as load spikes.
 * Recovering slowly (10 cycles) prevents oscillation — we don't want the
 * sampler switching between NORMAL and THROTTLED every few seconds if RPS
 * is hovering near the threshold.
 *
 * <h2>Thread safety</h2>
 * This class is called from the aggregation daemon thread only.
 * All state changes are written to SamplingStateHolder which uses volatile.
 */
public final class AdaptiveSamplingController {

    private static final Logger log =
        Logger.getLogger(AdaptiveSamplingController.class.getName());

    // Thresholds for state transitions
    private static final int CYCLES_TO_THROTTLE = 3;
    private static final int CYCLES_TO_RECOVER  = 10;

    private final AgentConfig         config;
    private final SamplingStateHolder stateHolder;

    // Cycle counters — track how many consecutive cycles we have been in a given condition
    private int highLoadCycles  = 0;
    private int normalLoadCycles = 0;

    public AdaptiveSamplingController(AgentConfig config,
                                      SamplingStateHolder stateHolder) {
        this.config      = config;
        this.stateHolder = stateHolder;
    }

    /**
     * Evaluate current RPS and update sampling state if needed.
     * Called by the aggregation daemon after each aggregation cycle.
     *
     * @param currentRps current requests per second across all endpoints
     */
    public void evaluate(double currentRps) {
        if (!config.isAdaptiveSamplingEnabled()) return;

        double rpsThreshold = config.getMaxRps();

        if (currentRps > rpsThreshold) {
            highLoadCycles++;
            normalLoadCycles = 0;

            // Transition to THROTTLED after sustained high load
            if (highLoadCycles >= CYCLES_TO_THROTTLE
                    && stateHolder.getState() == SamplingState.NORMAL) {
                applyState(SamplingState.THROTTLED);
                log.info("AdaptiveSampler: THROTTLED — currentRps=" + currentRps
                    + " threshold=" + rpsThreshold
                    + " effectiveInterval="
                    + (config.getBaseIntervalMs() * config.getThrottleMultiplier()) + "ms");
            }

        } else {
            normalLoadCycles++;
            highLoadCycles = 0;

            // Transition back to NORMAL after sustained low load
            if (normalLoadCycles >= CYCLES_TO_RECOVER
                    && stateHolder.getState() == SamplingState.THROTTLED) {
                applyState(SamplingState.NORMAL);
                log.info("AdaptiveSampler: NORMAL — currentRps=" + currentRps
                    + " effectiveInterval=" + config.getBaseIntervalMs() + "ms");
            }
        }
    }

    private void applyState(SamplingState newState) {
        long intervalMs = newState == SamplingState.THROTTLED
            ? config.getBaseIntervalMs() * config.getThrottleMultiplier()
            : config.getBaseIntervalMs();
        stateHolder.setState(newState, intervalMs);
    }
}
```

---

## Step 4 — Update AgentConfig for Adaptive Sampling

Add these fields to `AgentConfig`:

```java
// Fields
private final boolean adaptiveSamplingEnabled;
private final double  maxRps;
private final long    throttleMultiplier;

// Parsing in load()
boolean adaptiveEnabled = Boolean.parseBoolean(
    props.getProperty("profiler.sampling.adaptive.enabled", "true"));
double maxRps = parseDouble(props,
    "profiler.sampling.adaptive.max.rps", 500.0);
long throttleMultiplier = parseLong(props,
    "profiler.sampling.adaptive.multiplier", 5L);

// Getters
public boolean isAdaptiveSamplingEnabled() { return adaptiveSamplingEnabled; }
public double  getMaxRps()                 { return maxRps; }
public long    getThrottleMultiplier()     { return throttleMultiplier; }
```

Add `parseDouble` helper:

```java
private static double parseDouble(Properties p, String key, double def) {
    try { return Double.parseDouble(p.getProperty(key, String.valueOf(def))); }
    catch (NumberFormatException e) {
        log.warning("Invalid value for " + key + " — using default " + def);
        return def;
    }
}
```

---

## Step 5 — Update HeapSampler for Adaptive Interval

The HeapSampler currently uses a fixed `scheduleAtFixedRate`. We change it to
a self-rescheduling loop that reads the effective interval from `SamplingStateHolder`
before each sleep.

Replace the `start()` method in `HeapSampler`:

```java
// Add field
private final SamplingStateHolder samplingState;

// Updated constructor
public HeapSampler(RingBuffer<HeapSnapshot> buffer,
                   AgentSelfMetrics selfMetrics,
                   AgentConfig config,
                   SamplingStateHolder samplingState) {
    this.buffer        = buffer;
    this.selfMetrics   = selfMetrics;
    this.config        = config;
    this.samplingState = samplingState;
    this.memoryBean    = ManagementFactory.getMemoryMXBean();
    this.poolBeans     = ManagementFactory.getMemoryPoolMXBeans();
}

public void start() {
    Thread samplerThread = new Thread(() -> {
        log.info("HeapSampler started — base interval="
            + config.getBaseIntervalMs() + "ms");

        while (!Thread.currentThread().isInterrupted()) {
            try {
                sample();

                // Read the CURRENT effective interval — may have changed
                // since the last sample due to adaptive controller
                long intervalMs = samplingState.getEffectiveIntervalMs();
                Thread.sleep(intervalMs);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("HeapSampler interrupted — stopping");
                break;
            } catch (Exception e) {
                // Never let an exception kill the sampler thread
                log.warning("HeapSampler error: " + e.getMessage());
            }
        }
    }, "profiler-heap-sampler");

    samplerThread.setDaemon(true);
    samplerThread.setPriority(Thread.MIN_PRIORITY);
    samplerThread.start();
}
```

---

## Step 6 — Update AggregationDaemon

Add the adaptive controller call to the aggregation cycle:

```java
// Add field
private final AdaptiveSamplingController adaptiveController;

// Updated constructor
public AggregationDaemon(CollectorRegistry registry,
                         EndpointAggregator endpointAggregator,
                         BeanMemoryMapper beanMapper,
                         AdaptiveSamplingController adaptiveController) {
    this.registry            = registry;
    this.endpointAggregator  = endpointAggregator;
    this.beanMapper          = beanMapper;
    this.adaptiveController  = adaptiveController;
}

private void aggregate() {
    try {
        List<EndpointStats> stats = endpointAggregator.aggregate();

        double totalRps = stats.stream()
            .mapToDouble(EndpointStats::currentRps).sum();
        registry.setCurrentRps(totalRps);

        // ── NEW: Evaluate adaptive sampling ──────────────────────────────
        adaptiveController.evaluate(totalRps);

        // ... rest of aggregation (bean ranking, persistence) ...

    } catch (Exception e) {
        log.warning("Aggregation cycle failed: " + e.getMessage());
    }
}
```

---

## Step 7 — Add SamplingStateHolder to CollectorRegistry

```java
// Add field
private final SamplingStateHolder samplingStateHolder;

// In constructor — needs base interval, so pass config or interval
public CollectorRegistry(long baseIntervalMs) {
    // ... existing fields ...
    this.samplingStateHolder = new SamplingStateHolder(baseIntervalMs);
}

// Getter
public SamplingStateHolder getSamplingStateHolder() {
    return samplingStateHolder;
}
```

Update `AgentMain` to pass config to `CollectorRegistry`:

```java
CollectorRegistry registry = new CollectorRegistry(config.getBaseIntervalMs());
```

Update the `HeapSampler` construction in `AgentMain`:

```java
new HeapSampler(
    registry.heapBuffer(),
    registry.selfMetrics(),
    config,
    registry.getSamplingStateHolder()
).start();
```

Create and wire `AdaptiveSamplingController` in `AgentMain`:

```java
AdaptiveSamplingController adaptiveController =
    new AdaptiveSamplingController(config, registry.getSamplingStateHolder());

new AggregationDaemon(registry, endpointAggregator, beanMapper,
    adaptiveController).start();
```

---

## Step 8 — Update /profiler/status

Add sampling state to the status response:

```java
app.get("/profiler/status", ctx -> {
    SamplingStateHolder state = registry.getSamplingStateHolder();
    var selfSnap = registry.selfMetrics()
        .snapshot(config.getInstanceId(), config.getBaseIntervalMs());

    Map<String, Object> status = new LinkedHashMap<>();
    status.put("instanceId",          config.getInstanceId());
    status.put("uptimeMs",            selfSnap.uptimeMs());
    status.put("samplingState",       state.getState().name());
    status.put("effectiveIntervalMs", state.getEffectiveIntervalMs());
    status.put("baseIntervalMs",      config.getBaseIntervalMs());
    status.put("currentRps",          registry.getCurrentRps());
    status.put("rpsThreshold",        config.getMaxRps());
    status.put("agentHeapUsedBytes",  selfSnap.agentHeapUsedBytes());
    status.put("droppedSamples",      selfSnap.droppedSamples());
    status.put("samplingDelays",      selfSnap.samplingDelays());
    status.put("lastSampleTimestampMs", selfSnap.lastSampleTimestampMs());

    ctx.json(status);
});
```

---

## Step 9 — Leak Detector

`src/main/java/agent/analysis/LeakDetector.java`

```java
package agent.analysis;

import agent.buffer.RingBuffer;
import agent.model.HeapSnapshot;
import agent.model.LeakWarning;

import java.util.List;
import java.util.Optional;

/**
 * Detects sustained heap growth patterns that indicate a memory leak.
 *
 * <h2>Algorithm</h2>
 * 1. Take the oldest and newest heap snapshots within the configured window.
 * 2. Check if any GC event occurred in that window.
 *    If yes: heap reduction is expected — skip leak detection this cycle.
 * 3. Compute growth percentage: (newest - oldest) / oldest × 100.
 * 4. If growth > warn threshold (10%) → emit WARN.
 * 5. If growth > critical threshold (25%) → emit CRITICAL.
 *
 * <h2>Why skip if GC occurred?</h2>
 * GC reduces heap size — after a GC the heap is expected to jump up again
 * as the application allocates new objects. This is normal, not a leak.
 * We only call something a leak if heap grows WITHOUT GC cleaning it up.
 *
 * <h2>Runs on aggregation daemon</h2>
 * Never runs on the sampling or request thread.
 */
public final class LeakDetector {

    private static final double WARN_THRESHOLD_PERCENT     = 10.0;
    private static final double CRITICAL_THRESHOLD_PERCENT = 25.0;

    private final long windowMs;

    public LeakDetector(long windowMs) {
        this.windowMs = windowMs;
    }

    /**
     * Analyzes recent heap snapshots and returns a warning if a leak is detected.
     *
     * @param recentSnapshots recent heap snapshots (should cover at least windowMs)
     * @param hadRecentGc     true if any GC event occurred within windowMs
     * @return Optional containing a LeakWarning if a leak is detected, empty otherwise
     */
    public Optional<LeakWarning> detect(List<HeapSnapshot> recentSnapshots,
                                         boolean hadRecentGc) {
        // Not enough data to make a judgment
        if (recentSnapshots.size() < 2) return Optional.empty();

        // If GC occurred recently, heap may legitimately be growing — skip
        if (hadRecentGc) return Optional.empty();

        // Find the oldest and newest snapshots within the window
        long now       = System.currentTimeMillis();
        long windowStart = now - windowMs;

        HeapSnapshot oldest = null;
        HeapSnapshot newest = null;

        for (HeapSnapshot snap : recentSnapshots) {
            if (snap.timestampMs() < windowStart) continue;

            if (oldest == null || snap.timestampMs() < oldest.timestampMs()) {
                oldest = snap;
            }
            if (newest == null || snap.timestampMs() > newest.timestampMs()) {
                newest = snap;
            }
        }

        if (oldest == null || newest == null || oldest == newest) {
            return Optional.empty();
        }

        // Compute growth
        long   growth        = newest.usedBytes() - oldest.usedBytes();
        double growthPercent = (double) growth / oldest.usedBytes() * 100.0;

        // No growth or negative growth (heap shrank) — no leak
        if (growthPercent <= 0) return Optional.empty();

        // Determine severity
        String severity;
        if (growthPercent >= CRITICAL_THRESHOLD_PERCENT) {
            severity = "CRITICAL";
        } else if (growthPercent >= WARN_THRESHOLD_PERCENT) {
            severity = "WARN";
        } else {
            return Optional.empty();  // Below warn threshold — no warning
        }

        return Optional.of(new LeakWarning(
            now,
            growth,
            windowMs,
            Math.round(growthPercent * 100.0) / 100.0,
            severity
        ));
    }
}
```

---

## Step 10 — AlertEvaluator

`src/main/java/agent/alert/AlertEvaluator.java`

```java
package agent.alert;

import agent.core.AgentConfig;
import agent.model.GcEvent;
import agent.model.HeapSnapshot;
import agent.model.LeakWarning;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Evaluates alert rules after each aggregation cycle and dispatches
 * webhook payloads when alert state changes.
 *
 * <h2>One payload per state change</h2>
 * We track the "last alert state" per rule. A webhook is sent only when
 * the state changes (none→alert or alert→resolved). This prevents flooding
 * the webhook receiver with one message every 5 seconds.
 *
 * <h2>Duplicate suppression</h2>
 * Even on state change, we suppress re-firing the same alert if it was
 * sent within the last 60 seconds. This handles edge cases where the
 * condition rapidly toggles true/false.
 */
public final class AlertEvaluator {

    private static final Logger log =
        Logger.getLogger(AlertEvaluator.class.getName());

    private static final long SUPPRESSION_WINDOW_MS = 60_000L;

    private final AgentConfig         config;
    private final WebhookDispatcher   dispatcher;
    private final agent.analysis.LeakDetector leakDetector;

    // Tracks last known state per rule key
    // true = alert is active, false = alert is resolved
    private final Map<String, Boolean> alertState       = new HashMap<>();
    private final Map<String, Long>    lastFiredAtMs    = new HashMap<>();

    public AlertEvaluator(AgentConfig config,
                          WebhookDispatcher dispatcher,
                          agent.analysis.LeakDetector leakDetector) {
        this.config       = config;
        this.dispatcher   = dispatcher;
        this.leakDetector = leakDetector;
    }

    /**
     * Evaluate all alert rules against the current metrics snapshot.
     * Called by the aggregation daemon after every aggregation cycle.
     *
     * @param recentHeap  recent heap snapshots
     * @param recentGc    recent GC events
     * @param gcOverheadPercent current GC overhead percentage
     */
    public void evaluate(List<HeapSnapshot> recentHeap,
                         List<GcEvent>      recentGc,
                         double             gcOverheadPercent) {

        // Check if any GC occurred in the leak detection window
        long windowStart    = System.currentTimeMillis() - 60_000L;
        boolean hadRecentGc = recentGc.stream()
            .anyMatch(e -> e.timestampMs() > windowStart);

        // ── Rule 1: Leak detection ────────────────────────────────────────
        Optional<LeakWarning> warning = leakDetector.detect(recentHeap, hadRecentGc);
        evaluateRule("LEAK_WARNING", warning.isPresent(), () -> {
            LeakWarning w = warning.get();
            return buildPayload("LEAK_WARNING", w.severity(),
                "Heap grew " + w.growthPercent() + "% in "
                    + (w.windowMs() / 1000) + "s with no GC relief",
                Map.of(
                    "growthPercent", w.growthPercent(),
                    "growthBytes",   w.heapGrowthBytes(),
                    "severity",      w.severity()
                ));
        }, () -> buildPayload("LEAK_WARNING", "RESOLVED",
            "Heap growth stabilized — leak condition cleared", Map.of()));

        // ── Rule 2: GC overhead ───────────────────────────────────────────
        boolean gcOverheadBreached =
            gcOverheadPercent > config.getGcOverheadThreshold();

        evaluateRule("GC_OVERHEAD", gcOverheadBreached, () ->
            buildPayload("GC_OVERHEAD", "WARN",
                "GC overhead at " + gcOverheadPercent + "% — "
                    + "application spending too much time in GC",
                Map.of("gcOverheadPercent", gcOverheadPercent,
                       "threshold", config.getGcOverheadThreshold())),
            () -> buildPayload("GC_OVERHEAD", "RESOLVED",
                "GC overhead returned below threshold", Map.of()));
    }

    /**
     * Generic rule evaluation — fires alert or resolved payload based on
     * current condition vs last known state.
     *
     * @param ruleKey        unique identifier for this rule
     * @param conditionTrue  whether the alert condition is currently true
     * @param alertPayload   supplier for the alert payload (only called when firing)
     * @param resolvedPayload supplier for the resolved payload
     */
    private void evaluateRule(String ruleKey, boolean conditionTrue,
                               java.util.function.Supplier<agent.model.AlertPayload> alertPayload,
                               java.util.function.Supplier<agent.model.AlertPayload> resolvedPayload) {

        boolean wasActive = alertState.getOrDefault(ruleKey, false);

        if (conditionTrue && !wasActive) {
            // Condition newly true — fire alert
            if (!isSuppressed(ruleKey)) {
                alertState.put(ruleKey, true);
                lastFiredAtMs.put(ruleKey, System.currentTimeMillis());
                dispatcher.dispatch(alertPayload.get());
                log.info("Alert fired: " + ruleKey);
            }

        } else if (!conditionTrue && wasActive) {
            // Condition cleared — fire resolved
            alertState.put(ruleKey, false);
            lastFiredAtMs.put(ruleKey, System.currentTimeMillis());
            dispatcher.dispatch(resolvedPayload.get());
            log.info("Alert resolved: " + ruleKey);
        }
        // If state unchanged — nothing to do
    }

    private boolean isSuppressed(String ruleKey) {
        Long lastFired = lastFiredAtMs.get(ruleKey);
        if (lastFired == null) return false;
        return System.currentTimeMillis() - lastFired < SUPPRESSION_WINDOW_MS;
    }

    private agent.model.AlertPayload buildPayload(String alertType, String severity,
                                                   String message,
                                                   Map<String, Object> metadata) {
        return new agent.model.AlertPayload(
            config.getInstanceId(),
            alertType,
            severity,
            message,
            System.currentTimeMillis(),
            metadata
        );
    }
}
```

Add GC overhead threshold to `AgentConfig`:

```java
private final double gcOverheadThreshold;

// In load():
double gcOverheadThreshold = parseDouble(props,
    "profiler.alert.gc.overhead.threshold", 15.0);

// Getter:
public double getGcOverheadThreshold() { return gcOverheadThreshold; }
```

---

## Step 11 — WebhookDispatcher

`src/main/java/agent/alert/WebhookDispatcher.java`

```java
package agent.alert;

import agent.model.AlertPayload;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * Sends alert payloads to a configured webhook URL via HTTP POST.
 *
 * <h2>Async dispatch</h2>
 * All HTTP POSTs happen on a dedicated 2-thread daemon pool.
 * dispatch() returns immediately — the caller (AlertEvaluator on the
 * aggregation daemon) is never blocked by network I/O.
 *
 * <h2>Retry policy</h2>
 * 3 retries with exponential back-off: 1s, 2s, 4s.
 * After 3 failures, the alert is logged and dropped.
 * Failures are counted in self-metrics.
 *
 * <h2>Disabled state</h2>
 * If profiler.alert.webhook.url is not configured, dispatch() is a no-op.
 */
public final class WebhookDispatcher {

    private static final Logger log =
        Logger.getLogger(WebhookDispatcher.class.getName());

    private final String         webhookUrl;
    private final ObjectMapper   json;
    private final ExecutorService pool;
    private final HttpClient      httpClient;

    // Self-metrics counter
    private final java.util.concurrent.atomic.LongAdder failureCount =
        new java.util.concurrent.atomic.LongAdder();

    public WebhookDispatcher(String webhookUrl) {
        this.webhookUrl = webhookUrl;
        this.json       = new ObjectMapper();

        // 2 daemon threads — enough for bursts of alerts
        this.pool = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "profiler-webhook-dispatcher");
            t.setDaemon(true);
            return t;
        });

        // HttpClient with timeout — don't hang forever on a slow receiver
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    }

    /**
     * Dispatches an alert payload asynchronously.
     * Returns immediately. Never blocks.
     *
     * @param payload the alert to send — ignored if webhook URL is not configured
     */
    public void dispatch(AlertPayload payload) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            // Webhook not configured — log locally and return
            log.info("Alert (no webhook configured): " + payload.alertType()
                + " [" + payload.severity() + "] " + payload.message());
            return;
        }

        // Submit to thread pool — returns immediately
        pool.submit(() -> sendWithRetry(payload, 3));
    }

    /**
     * Attempts to send the payload, retrying up to maxRetries times
     * with exponential back-off.
     */
    private void sendWithRetry(AlertPayload payload, int maxRetries) {
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                String body = json.writeValueAsString(payload);

                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

                HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    // Success — log only for non-RESOLVED alerts (resolved are noisy)
                    if (!"RESOLVED".equals(payload.severity())) {
                        log.info("Webhook delivered: " + payload.alertType()
                            + " [" + payload.severity() + "] → HTTP "
                            + response.statusCode());
                    }
                    return;  // Done

                } else {
                    // Non-2xx response — treat as failure and retry
                    log.warning("Webhook returned HTTP " + response.statusCode()
                        + " (attempt " + attempt + "/" + maxRetries + ")");
                }

            } catch (Exception e) {
                log.warning("Webhook POST failed (attempt " + attempt
                    + "/" + maxRetries + "): " + e.getMessage());
            }

            // Back-off before retry: 1s, 2s, 4s
            if (attempt < maxRetries) {
                try {
                    Thread.sleep((long) Math.pow(2, attempt - 1) * 1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        // All retries exhausted
        log.warning("Webhook delivery permanently failed after "
            + maxRetries + " attempts: " + payload.alertType()
            + " [" + payload.severity() + "]");
        failureCount.increment();
    }

    public long getFailureCount() { return failureCount.sum(); }
}
```

---

## Step 12 — Wire Alerting into AggregationDaemon

Add alert evaluation to the aggregation cycle:

```java
// Add fields
private final AlertEvaluator alertEvaluator;

// Updated constructor
public AggregationDaemon(CollectorRegistry registry,
                         EndpointAggregator endpointAggregator,
                         BeanMemoryMapper beanMapper,
                         AdaptiveSamplingController adaptiveController,
                         AlertEvaluator alertEvaluator) {
    // ... existing ...
    this.alertEvaluator = alertEvaluator;
}

private void aggregate() {
    try {
        // Existing aggregation logic ...

        // ── NEW: Evaluate alert rules ─────────────────────────────────
        List<HeapSnapshot> heapSnapshot = registry.heapBuffer().snapshot();
        List<GcEvent>      gcSnapshot   = registry.gcBuffer().snapshot();

        // Compute GC overhead = total pause time / elapsed time × 100
        long totalPauseMs = gcSnapshot.stream()
            .mapToLong(GcEvent::durationMs).sum();
        double gcOverhead = gcSnapshot.isEmpty() ? 0.0
            : (double) totalPauseMs / (INTERVAL_SECONDS * 1000) * 100;

        alertEvaluator.evaluate(heapSnapshot, gcSnapshot, gcOverhead);

    } catch (Exception e) {
        log.warning("Aggregation cycle failed: " + e.getMessage());
    }
}
```

Wire everything in `AgentMain`:

```java
// Create alerting components
String webhookUrl = config.getWebhookUrl();  // add this to AgentConfig
WebhookDispatcher webhookDispatcher = new WebhookDispatcher(webhookUrl);

LeakDetector leakDetector =
    new LeakDetector(config.getLeakDetectionWindowMs());  // add to AgentConfig

AlertEvaluator alertEvaluator = new AlertEvaluator(
    config, webhookDispatcher, leakDetector);

// Update AggregationDaemon construction
new AggregationDaemon(registry, endpointAggregator, beanMapper,
    adaptiveController, alertEvaluator).start();
```

Add to `AgentConfig`:

```java
private final String webhookUrl;
private final long   leakDetectionWindowMs;

// In load():
String webhookUrl = props.getProperty("profiler.alert.webhook.url", "");
long leakWindowMs = parseLong(props, "profiler.leak.detection.window.ms", 60_000L);

// Getters:
public String getWebhookUrl()             { return webhookUrl; }
public long   getLeakDetectionWindowMs()  { return leakDetectionWindowMs; }
```

Also add `GET /profiler/leaks` route:

```java
app.get("/profiler/leaks", ctx -> {
    // For Phase 4, show the current leak state from in-memory only
    // Phase 5 will add persistence-backed history
    List<LeakWarning> warnings = registry.getActiveLeakWarnings();
    ctx.json(Map.of(
        "activeWarnings", warnings.size(),
        "warnings",       warnings
    ));
});
```

Add to `CollectorRegistry`:

```java
private final java.util.concurrent.CopyOnWriteArrayList<LeakWarning> activeLeakWarnings =
    new java.util.concurrent.CopyOnWriteArrayList<>();

public void setActiveLeakWarnings(List<LeakWarning> warnings) {
    activeLeakWarnings.clear();
    activeLeakWarnings.addAll(warnings);
}
public List<LeakWarning> getActiveLeakWarnings() {
    return Collections.unmodifiableList(activeLeakWarnings);
}
```

---

## Step 13 — Build and Test

```bash
mvn package -DskipTests
```

Start demo app with agent. Generate high load using a simple load script:

```bash
# Generate ~600 requests in a burst to trigger adaptive throttling
for i in {1..600}; do
    curl -s http://localhost:8080/hello > /dev/null &
done
wait

# Check status — should show THROTTLED
curl -s http://localhost:7070/profiler/status | python3 -m json.tool
```

Expected output when throttled:
```json
{
  "samplingState": "THROTTLED",
  "effectiveIntervalMs": 50,
  "baseIntervalMs": 10,
  "currentRps": 612.3,
  "rpsThreshold": 500.0
}
```

Wait 60 seconds after load stops — status should show NORMAL again.

**Test webhook delivery:**

Run a simple webhook receiver in another terminal:

```bash
# Python one-liner webhook receiver
python3 -c "
import http.server, json
class H(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers['Content-Length']))
        print('WEBHOOK:', json.dumps(json.loads(body), indent=2))
        self.send_response(200)
        self.end_headers()
    def log_message(self, *a): pass
http.server.HTTPServer(('', 9999), H).serve_forever()
"
```

Configure agent with webhook URL and trigger a leak:

```bash
java \
  -javaagent:target/jvm-profiler-agent.jar=alert.webhook.url=http://localhost:9999 \
  -jar demo-app/target/demo-app.jar
```

Hit `/allocate` in a loop to grow heap:

```bash
for i in {1..200}; do curl -s http://localhost:8080/allocate > /dev/null; done
```

Within 90 seconds you should see the webhook receiver print:
```json
WEBHOOK: {
  "instanceId": "your-host:7070",
  "alertType": "LEAK_WARNING",
  "severity": "WARN",
  "message": "Heap grew 11.4% in 60s with no GC relief",
  "timestampMs": 1748000000000,
  "metadata": { "growthPercent": 11.4, "growthBytes": 52428800 }
}
```

---

## Step 14 — Unit Tests

### 14.1 AdaptiveSamplingController Tests

`src/test/java/agent/sampling/AdaptiveSamplingControllerTest.java`

```java
package agent.sampling;

import agent.core.AgentConfig;
import agent.model.SamplingState;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdaptiveSamplingControllerTest {

    private AgentConfig mockConfig() {
        AgentConfig config = mock(AgentConfig.class);
        when(config.isAdaptiveSamplingEnabled()).thenReturn(true);
        when(config.getMaxRps()).thenReturn(500.0);
        when(config.getBaseIntervalMs()).thenReturn(10L);
        when(config.getThrottleMultiplier()).thenReturn(5L);
        return config;
    }

    @Test
    void startsInNormalState() {
        SamplingStateHolder holder = new SamplingStateHolder(10L);
        assertEquals(SamplingState.NORMAL, holder.getState());
        assertEquals(10L, holder.getEffectiveIntervalMs());
    }

    @Test
    void throttlesAfter3HighLoadCycles() {
        AgentConfig config = mockConfig();
        SamplingStateHolder holder = new SamplingStateHolder(10L);
        AdaptiveSamplingController ctrl =
            new AdaptiveSamplingController(config, holder);

        // 2 high-load cycles — still NORMAL
        ctrl.evaluate(600.0);
        ctrl.evaluate(600.0);
        assertEquals(SamplingState.NORMAL, holder.getState());

        // 3rd high-load cycle — switches to THROTTLED
        ctrl.evaluate(600.0);
        assertEquals(SamplingState.THROTTLED, holder.getState());
        assertEquals(50L, holder.getEffectiveIntervalMs()); // 10 × 5
    }

    @Test
    void recoversAfter10NormalCycles() {
        AgentConfig config = mockConfig();
        SamplingStateHolder holder = new SamplingStateHolder(10L);
        AdaptiveSamplingController ctrl =
            new AdaptiveSamplingController(config, holder);

        // Throttle first
        for (int i = 0; i < 3; i++) ctrl.evaluate(600.0);
        assertEquals(SamplingState.THROTTLED, holder.getState());

        // 9 normal cycles — still THROTTLED
        for (int i = 0; i < 9; i++) ctrl.evaluate(100.0);
        assertEquals(SamplingState.THROTTLED, holder.getState());

        // 10th normal cycle — back to NORMAL
        ctrl.evaluate(100.0);
        assertEquals(SamplingState.NORMAL, holder.getState());
        assertEquals(10L, holder.getEffectiveIntervalMs());
    }

    @Test
    void doesNothingWhenAdaptiveDisabled() {
        AgentConfig config = mockConfig();
        when(config.isAdaptiveSamplingEnabled()).thenReturn(false);
        SamplingStateHolder holder = new SamplingStateHolder(10L);
        AdaptiveSamplingController ctrl =
            new AdaptiveSamplingController(config, holder);

        // Even with 1000 RPS, state should stay NORMAL
        for (int i = 0; i < 10; i++) ctrl.evaluate(1000.0);
        assertEquals(SamplingState.NORMAL, holder.getState());
    }
}
```

### 14.2 LeakDetector Tests

```java
package agent.analysis;

import agent.model.HeapSnapshot;
import agent.model.LeakWarning;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class LeakDetectorTest {

    private final LeakDetector detector = new LeakDetector(60_000L);

    @Test
    void returnsEmptyWhenNoData() {
        assertTrue(detector.detect(List.of(), false).isEmpty());
    }

    @Test
    void returnsEmptyWhenGcOccurred() {
        List<HeapSnapshot> snapshots = List.of(
            heap(System.currentTimeMillis() - 30000, 100_000_000L),
            heap(System.currentTimeMillis(),          150_000_000L)
        );
        // hadRecentGc = true — should suppress detection
        assertTrue(detector.detect(snapshots, true).isEmpty());
    }

    @Test
    void detectsWarnLevelGrowth() {
        long now = System.currentTimeMillis();
        List<HeapSnapshot> snapshots = List.of(
            heap(now - 30000, 100_000_000L),
            heap(now,          115_000_000L)  // 15% growth
        );

        Optional<LeakWarning> warning = detector.detect(snapshots, false);
        assertTrue(warning.isPresent());
        assertEquals("WARN", warning.get().severity());
        assertTrue(warning.get().growthPercent() > 10.0);
    }

    @Test
    void detectsCriticalLevelGrowth() {
        long now = System.currentTimeMillis();
        List<HeapSnapshot> snapshots = List.of(
            heap(now - 30000, 100_000_000L),
            heap(now,          130_000_000L)  // 30% growth
        );

        Optional<LeakWarning> warning = detector.detect(snapshots, false);
        assertTrue(warning.isPresent());
        assertEquals("CRITICAL", warning.get().severity());
    }

    @Test
    void returnEmptyWhenHeapShrinks() {
        long now = System.currentTimeMillis();
        List<HeapSnapshot> snapshots = List.of(
            heap(now - 30000, 150_000_000L),
            heap(now,          100_000_000L)  // heap shrank — no leak
        );

        assertTrue(detector.detect(snapshots, false).isEmpty());
    }

    private HeapSnapshot heap(long ts, long usedBytes) {
        return new HeapSnapshot(ts, usedBytes, usedBytes * 2, Long.MAX_VALUE, Map.of());
    }
}
```

### 14.3 Run All Tests

```bash
mvn test
```

---

## Step 15 — Phase 4 Checklist

- [ ] Under 600 req/s: `/profiler/status` shows `samplingState: THROTTLED` within 30s
- [ ] After load stops: `samplingState` returns to `NORMAL` within 60s
- [ ] `effectiveIntervalMs` = `baseIntervalMs × throttleMultiplier` when throttled
- [ ] Deliberate heap growth triggers WARN webhook within 90s
- [ ] RESOLVED webhook fires when growth stops
- [ ] Agent with no webhook configured: alert logged locally, no errors
- [ ] Webhook receiver returning 500: retried 3 times, then logged, agent continues
- [ ] All unit tests pass

```bash
git checkout develop
git merge --no-ff phase/4-adaptive-alerting \
  -m "Merge Phase 4: Adaptive Sampling & Alerting"
git tag phase-4-complete
git push origin develop --tags
git checkout -b phase/5-multiinstance-dashboard
```

---

## Troubleshooting Phase 4

**Problem:** `samplingState` stays NORMAL even at 600+ req/s
**Cause:** RPS measurement may not be updating. Check that `EndpointAggregator.aggregate()`
is being called and `registry.setCurrentRps()` is updated.
**Fix:** Call `/profiler/status` repeatedly while load is running and watch
`currentRps`. If it shows 0, the endpoint buffer is not being drained.

---

**Problem:** Webhook delivers one alert, then stops even though leak continues
**Cause:** Expected — the alert fires once per state change. One alert = one POST.
**Fix:** This is correct behavior. If you want repeated alerts, reduce
`SUPPRESSION_WINDOW_MS` in `AlertEvaluator`.

---

**Problem:** `java.net.http.HttpClient` class not found
**Cause:** `HttpClient` requires Java 11+. If you are compiling with Java 8 target
in pom.xml, it will not compile.
**Fix:** Verify `<maven.compiler.source>17</maven.compiler.source>` in pom.xml.

---

*End of Phase 4.*
*Next: [Phase 5 — Multi-Instance & Full Dashboard](./PHASE-5-MULTIINSTANCE-DASHBOARD.md)*
