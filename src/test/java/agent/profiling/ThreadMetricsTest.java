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

    @Test
    void stackSamplerSkipsProfilerControlPlaneStacks() {
        StackSampler sampler = new StackSampler(20);

        boolean recorded = sampler.recordStack(new StackTraceElement[] {
            frame("agent.shaded.jackson.databind.ser.std.MapSerializer", "serializeFieldsUsing"),
            frame("agent.http.ProfilerHttpServer", "lambda$registerRoutes$17"),
            frame("java.lang.Thread", "run")
        });

        assertFalse(recorded);
        assertEquals(0L, sampler.snapshot().samples);
    }

    @Test
    void stackSamplerFiltersAgentAdviceFramesFromTargetStacks() {
        StackSampler sampler = new StackSampler(20);

        boolean recorded = sampler.recordStack(new StackTraceElement[] {
            frame("agent.profiling.RequestProfilingContext", "enterMethod"),
            frame("demo.Service", "handle"),
            frame("java.lang.Thread", "run")
        });

        FlameNode root = sampler.snapshot();
        assertTrue(recorded);
        assertEquals(1L, root.samples);
        assertTrue(containsFrame(root, "demo.Service.handle"));
        assertFalse(containsFrame(root, "agent.profiling.RequestProfilingContext.enterMethod"));
    }

    private static StackTraceElement frame(String className, String methodName) {
        return new StackTraceElement(className, methodName, className + ".java", 1);
    }

    private static boolean containsFrame(FlameNode node, String frame) {
        if (frame.equals(node.frame)) return true;
        for (FlameNode child : node.children.values()) {
            if (containsFrame(child, frame)) return true;
        }
        return false;
    }
}
