package agent.profiling;

import agent.model.FlameNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the Phase 6 profiling helpers that can be exercised without a
 * running application: {@link ThreadMetrics} and {@link StackSampler}.
 */
class ThreadMetricsTest {

    @Test
    void initIsSafeAndCountersDegradeGracefully() {
        assertDoesNotThrow(ThreadMetrics::init);
        // Whether or not the JVM supports these, the accessors must never throw
        // and must return non-negative values.
        assertTrue(ThreadMetrics.cpuNs() >= 0L);
        assertTrue(ThreadMetrics.allocBytes() >= 0L);
    }

    @Test
    void cpuTimeIsMonotonicWhenSupported() {
        ThreadMetrics.init();
        if (!ThreadMetrics.cpuSupported()) return;   // skip where unsupported
        long a = ThreadMetrics.cpuNs();
        long sum = 0;
        for (int i = 0; i < 1_000_000; i++) sum += i;   // burn a little CPU
        long b = ThreadMetrics.cpuNs();
        assertTrue(b >= a, "CPU time should not go backwards (sum=" + sum + ")");
    }

    @Test
    void freshStackSamplerSnapshotIsEmptyRoot() {
        StackSampler sampler = new StackSampler(20);
        FlameNode root = sampler.snapshot();
        assertNotNull(root);
        assertEquals("root", root.frame);
        assertEquals(0, root.samples);
        assertTrue(root.children.isEmpty());
    }
}
