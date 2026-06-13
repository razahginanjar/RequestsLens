package agent.analysis;

import agent.model.HeapSnapshot;
import agent.model.LeakWarning;

import java.util.List;
import java.util.Optional;

/**
 * Detects sustained heap-growth patterns that indicate a memory leak.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>Find the oldest and newest heap snapshots within the configured window.</li>
 *   <li>If any GC event occurred in that window, heap reduction is expected —
 *       skip detection this cycle (a leak is growth WITHOUT GC relief).</li>
 *   <li>Compute growth percentage: (newest − oldest) / oldest × 100.</li>
 *   <li>growth ≥ 25% → CRITICAL; growth ≥ 10% → WARN; otherwise no warning.</li>
 * </ol>
 *
 * <h2>Why skip when GC occurred?</h2>
 * GC reduces heap; right after a GC the heap legitimately climbs again as the
 * app allocates. That is normal, not a leak. We only flag growth that GC did
 * not reclaim.
 *
 * <p>Runs on the aggregation daemon thread — never on a sampling or request thread.
 *
 * <p><b>Note on window size:</b> the detector analyzes whatever snapshots it is
 * given within {@code windowMs}. In this agent the in-memory heap buffer is
 * drained every aggregation cycle for persistence, so the effective window is
 * bounded by the buffer's retention between drains. Real (continuous) leaks are
 * still detected; the window mainly guards against transient-spike false positives.
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
     * @param recentSnapshots recent heap snapshots (ideally covering windowMs)
     * @param hadRecentGc     true if any GC event occurred within the window
     * @return a {@link LeakWarning} if a leak is detected, otherwise empty
     */
    public Optional<LeakWarning> detect(List<HeapSnapshot> recentSnapshots,
                                        boolean hadRecentGc) {
        // Not enough data to judge.
        if (recentSnapshots.size() < 2) return Optional.empty();

        // GC happened — growth may be legitimate. Skip.
        if (hadRecentGc) return Optional.empty();

        long now         = System.currentTimeMillis();
        long windowStart = now - windowMs;

        // Find oldest and newest snapshots within the window.
        HeapSnapshot oldest = null;
        HeapSnapshot newest = null;
        for (HeapSnapshot snap : recentSnapshots) {
            if (snap.timestampMs() < windowStart) continue;   // outside window

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

        // Guard against divide-by-zero on a zero starting heap.
        if (oldest.usedBytes() <= 0) return Optional.empty();

        long   growth        = newest.usedBytes() - oldest.usedBytes();
        double growthPercent = (double) growth / oldest.usedBytes() * 100.0;

        // Heap flat or shrinking — no leak.
        if (growthPercent <= 0) return Optional.empty();

        String severity;
        if (growthPercent >= CRITICAL_THRESHOLD_PERCENT) {
            severity = "CRITICAL";
        } else if (growthPercent >= WARN_THRESHOLD_PERCENT) {
            severity = "WARN";
        } else {
            return Optional.empty();   // below WARN threshold
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
