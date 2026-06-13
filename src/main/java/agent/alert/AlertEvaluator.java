package agent.alert;

import agent.analysis.LeakDetector;
import agent.core.AgentConfig;
import agent.model.AlertPayload;
import agent.model.GcEvent;
import agent.model.HeapSnapshot;
import agent.model.LeakWarning;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Evaluates alert rules after each aggregation cycle and dispatches webhook
 * payloads when an alert's state changes.
 *
 * <h2>One payload per state change</h2>
 * We track the last known state per rule. A webhook fires only on a transition
 * (inactive→active or active→resolved), never once per cycle — so a sustained
 * leak produces a single alert, not one every 5 seconds.
 *
 * <h2>Duplicate suppression</h2>
 * Even on a transition, re-firing the same rule is suppressed if it fired
 * within the last {@value #SUPPRESSION_WINDOW_MS} ms, smoothing rapid toggles.
 *
 * <h2>Threading</h2>
 * Called from the aggregation daemon thread only; this class keeps no
 * concurrent state beyond the volatile {@link #latestLeakWarnings} snapshot
 * read by the HTTP thread via {@link #getActiveLeakWarnings()}.
 */
public final class AlertEvaluator {

    private static final Logger log =
        Logger.getLogger(AlertEvaluator.class.getName());

    private static final long SUPPRESSION_WINDOW_MS = 60_000L;

    private static final String RULE_LEAK = "LEAK_WARNING";
    private static final String RULE_GC   = "GC_OVERHEAD";

    private final AgentConfig       config;
    private final WebhookDispatcher dispatcher;
    private final LeakDetector      leakDetector;

    // Last known state per rule key: true = active, false = resolved.
    private final Map<String, Boolean> alertState    = new HashMap<>();
    private final Map<String, Long>    lastFiredAtMs = new HashMap<>();

    /**
     * The leak warnings detected in the most recent cycle, published for the
     * GET /profiler/leaks route. Volatile: written here (daemon thread), read
     * by the HTTP thread.
     */
    private volatile List<LeakWarning> latestLeakWarnings = List.of();

    public AlertEvaluator(AgentConfig config,
                          WebhookDispatcher dispatcher,
                          LeakDetector leakDetector) {
        this.config       = config;
        this.dispatcher   = dispatcher;
        this.leakDetector = leakDetector;
    }

    /**
     * Evaluates all alert rules against the current metrics snapshot.
     *
     * @param recentHeap        recent heap snapshots
     * @param recentGc          recent GC events
     * @param gcOverheadPercent current GC overhead percentage
     */
    public void evaluate(List<HeapSnapshot> recentHeap,
                         List<GcEvent>      recentGc,
                         double             gcOverheadPercent) {

        // Did a GC occur within the leak-detection window? If so, heap growth
        // is expected and leak detection is skipped for this cycle.
        long windowStart = System.currentTimeMillis() - config.getLeakDetectionWindowMs();
        boolean hadRecentGc = recentGc.stream()
            .anyMatch(e -> e.timestampMs() > windowStart);

        // ── Rule 1: memory leak ───────────────────────────────────────────
        Optional<LeakWarning> warning = leakDetector.detect(recentHeap, hadRecentGc);
        // Publish for the /profiler/leaks route (empty when no leak).
        latestLeakWarnings = warning.map(List::of).orElseGet(List::of);

        evaluateRule(RULE_LEAK, warning.isPresent(),
            () -> {
                LeakWarning w = warning.get();
                return buildPayload(RULE_LEAK, w.severity(),
                    "Heap grew " + w.growthPercent() + "% in "
                        + (w.windowMs() / 1000) + "s with no GC relief",
                    Map.of(
                        "growthPercent", w.growthPercent(),
                        "growthBytes",   w.heapGrowthBytes(),
                        "severity",      w.severity()
                    ));
            },
            () -> buildPayload(RULE_LEAK, "RESOLVED",
                "Heap growth stabilized — leak condition cleared", Map.of()));

        // ── Rule 2: GC overhead ───────────────────────────────────────────
        boolean gcOverheadBreached =
            gcOverheadPercent > config.getGcOverheadThreshold();

        evaluateRule(RULE_GC, gcOverheadBreached,
            () -> buildPayload(RULE_GC, "WARN",
                "GC overhead at " + gcOverheadPercent + "% — application "
                    + "spending too much time in GC",
                Map.of("gcOverheadPercent", gcOverheadPercent,
                       "threshold",         config.getGcOverheadThreshold())),
            () -> buildPayload(RULE_GC, "RESOLVED",
                "GC overhead returned below threshold", Map.of()));
    }

    /** The leak warnings from the most recent cycle (for GET /profiler/leaks). */
    public List<LeakWarning> getActiveLeakWarnings() {
        return latestLeakWarnings;
    }

    /**
     * Fires an alert or resolved payload based on the current condition vs the
     * last known state for {@code ruleKey}.
     */
    private void evaluateRule(String ruleKey, boolean conditionTrue,
                              Supplier<AlertPayload> alertPayload,
                              Supplier<AlertPayload> resolvedPayload) {

        boolean wasActive = alertState.getOrDefault(ruleKey, false);

        if (conditionTrue && !wasActive) {
            // Newly triggered — fire alert (unless still within suppression window).
            if (!isSuppressed(ruleKey)) {
                alertState.put(ruleKey, true);
                lastFiredAtMs.put(ruleKey, System.currentTimeMillis());
                dispatcher.dispatch(alertPayload.get());
                log.info("Alert fired: " + ruleKey);
            }
        } else if (!conditionTrue && wasActive) {
            // Cleared — fire RESOLVED.
            alertState.put(ruleKey, false);
            lastFiredAtMs.put(ruleKey, System.currentTimeMillis());
            dispatcher.dispatch(resolvedPayload.get());
            log.info("Alert resolved: " + ruleKey);
        }
        // Unchanged state — nothing to do.
    }

    private boolean isSuppressed(String ruleKey) {
        Long lastFired = lastFiredAtMs.get(ruleKey);
        if (lastFired == null) return false;
        return System.currentTimeMillis() - lastFired < SUPPRESSION_WINDOW_MS;
    }

    private AlertPayload buildPayload(String alertType, String severity,
                                      String message, Map<String, Object> metadata) {
        return new AlertPayload(
            config.getInstanceId(),
            alertType,
            severity,
            message,
            System.currentTimeMillis(),
            metadata
        );
    }
}
