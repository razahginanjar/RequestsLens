package agent.analysis;

import agent.model.HeapSnapshot;
import agent.model.LeakWarning;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link LeakDetector} (Phase 4 spec, Step 14.2).
 */
class LeakDetectorTest {

    private final LeakDetector detector = new LeakDetector(60_000L);

    @Test
    void returnsEmptyWhenNoData() {
        assertTrue(detector.detect(List.of(), false).isEmpty());
    }

    @Test
    void returnsEmptyWhenGcOccurred() {
        long now = System.currentTimeMillis();
        List<HeapSnapshot> snapshots = List.of(
            heap(now - 30000, 100_000_000L),
            heap(now,          150_000_000L)
        );
        // hadRecentGc = true — growth is expected, detection suppressed.
        assertTrue(detector.detect(snapshots, true).isEmpty());
    }

    @Test
    void detectsWarnLevelGrowth() {
        long now = System.currentTimeMillis();
        List<HeapSnapshot> snapshots = List.of(
            heap(now - 30000, 100_000_000L),
            heap(now,          115_000_000L)   // +15%
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
            heap(now,          130_000_000L)   // +30%
        );

        Optional<LeakWarning> warning = detector.detect(snapshots, false);
        assertTrue(warning.isPresent());
        assertEquals("CRITICAL", warning.get().severity());
    }

    @Test
    void returnsEmptyWhenHeapShrinks() {
        long now = System.currentTimeMillis();
        List<HeapSnapshot> snapshots = List.of(
            heap(now - 30000, 150_000_000L),
            heap(now,          100_000_000L)   // shrank — not a leak
        );
        assertTrue(detector.detect(snapshots, false).isEmpty());
    }

    @Test
    void returnsEmptyWhenGrowthBelowWarnThreshold() {
        long now = System.currentTimeMillis();
        List<HeapSnapshot> snapshots = List.of(
            heap(now - 30000, 100_000_000L),
            heap(now,          105_000_000L)   // +5% — below WARN
        );
        assertTrue(detector.detect(snapshots, false).isEmpty());
    }

    private HeapSnapshot heap(long ts, long usedBytes) {
        return new HeapSnapshot(ts, usedBytes, usedBytes * 2, Long.MAX_VALUE, Map.of());
    }
}
