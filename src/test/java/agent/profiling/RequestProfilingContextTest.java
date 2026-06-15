package agent.profiling;

import agent.model.MethodSpan;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RequestProfilingContext} — the per-thread call-tree
 * builder used by the Phase 6 method tracer.
 */
class RequestProfilingContextTest {

    private static MethodSpan newRoot() {
        MethodSpan r = new MethodSpan();
        r.className = "HTTP"; r.methodName = "request";
        return r;
    }

    @AfterEach
    void cleanUp() {
        // Ensure no context leaks to the next test on this thread.
        RequestProfilingContext.end();
        AllocationRecorder.configure(null, true, false, false);
    }

    @Test
    void notTracingByDefault() {
        assertFalse(RequestProfilingContext.isTracing());
        assertFalse(RequestProfilingContext.methodEnter("X", "y"),
            "methodEnter must return false when no request is being traced");
    }

    @Test
    void buildsNestedTree() {
        MethodSpan root = newRoot();
        RequestProfilingContext.begin(root, 40, 5000);
        assertTrue(RequestProfilingContext.isTracing());

        assertTrue(RequestProfilingContext.methodEnter("com.x.A", "a"));
        assertTrue(RequestProfilingContext.methodEnter("com.x.B", "b"));
        RequestProfilingContext.methodExit();   // pop b
        RequestProfilingContext.methodExit();   // pop a

        MethodSpan returned = RequestProfilingContext.end();
        assertSame(root, returned);

        // root -> A -> B
        assertEquals(1, root.children.size());
        MethodSpan a = root.children.get(0);
        assertEquals("com.x.A", a.className);
        assertEquals("a", a.methodName);
        assertEquals(1, a.children.size());
        assertEquals("com.x.B", a.children.get(0).className);
    }

    @Test
    void exposesCurrentTraceId() {
        MethodSpan root = newRoot();
        RequestProfilingContext.begin("trace-123", root, 40, 5000);

        assertEquals("trace-123", RequestProfilingContext.currentTraceId());

        RequestProfilingContext.end();
        assertNull(RequestProfilingContext.currentTraceId());
    }

    @Test
    void siblingsAtSameLevel() {
        MethodSpan root = newRoot();
        RequestProfilingContext.begin(root, 40, 5000);

        assertTrue(RequestProfilingContext.methodEnter("com.x.A", "a"));
        RequestProfilingContext.methodExit();
        assertTrue(RequestProfilingContext.methodEnter("com.x.B", "b"));
        RequestProfilingContext.methodExit();

        RequestProfilingContext.end();
        assertEquals(2, root.children.size(), "A and B should be siblings under root");
    }

    @Test
    void honorsDepthCap() {
        MethodSpan root = newRoot();
        RequestProfilingContext.begin(root, 2, 5000);   // maxDepth = 2

        assertTrue(RequestProfilingContext.methodEnter("com.x.A", "a"));   // stack 2
        assertTrue(RequestProfilingContext.methodEnter("com.x.B", "b"));   // stack 3
        assertFalse(RequestProfilingContext.methodEnter("com.x.C", "c"),
            "a 3rd nested level should exceed maxDepth=2 and be rejected");

        RequestProfilingContext.end();
    }

    @Test
    void honorsSpanCap() {
        MethodSpan root = newRoot();
        RequestProfilingContext.begin(root, 40, 1);     // maxSpans = 1

        assertTrue(RequestProfilingContext.methodEnter("com.x.A", "a"));
        RequestProfilingContext.methodExit();
        assertFalse(RequestProfilingContext.methodEnter("com.x.B", "b"),
            "a 2nd span should exceed maxSpans=1 and be rejected");

        RequestProfilingContext.end();
    }

    @Test
    void suppressesAllocationAttributionInsideDepthLimitedSubtree() {
        MethodSpan root = newRoot();
        RequestProfilingContext.begin(root, 1, 5000);

        assertEquals(RequestProfilingContext.ENTER_SPAN,
            RequestProfilingContext.methodEnterState("com.x.A", "a"));
        MethodSpan a = RequestProfilingContext.currentTopSpan();
        assertNotNull(a);

        int suppressed = RequestProfilingContext.methodEnterState("com.x.B", "b");
        assertEquals(RequestProfilingContext.ENTER_SUPPRESSED, suppressed);
        assertNull(RequestProfilingContext.currentTopSpan(),
            "allocations inside an untracked subtree must not be charged to A");

        RequestProfilingContext.methodExit(suppressed);
        assertSame(a, RequestProfilingContext.currentTopSpan());
        RequestProfilingContext.methodExit();

        RequestProfilingContext.CompletedTrace completed = RequestProfilingContext.finish();
        assertNotNull(completed);
        assertEquals(1, completed.capturedSpans());
        assertEquals(1, completed.droppedSpans());
        assertTrue(completed.depthLimitExceeded());
        assertTrue(completed.truncated());
    }

    @Test
    void reportsSpanLimitTruncation() {
        MethodSpan root = newRoot();
        RequestProfilingContext.begin(root, 40, 1);

        assertEquals(RequestProfilingContext.ENTER_SPAN,
            RequestProfilingContext.methodEnterState("com.x.A", "a"));
        RequestProfilingContext.methodExit();

        int suppressed = RequestProfilingContext.methodEnterState("com.x.B", "b");
        assertEquals(RequestProfilingContext.ENTER_SUPPRESSED, suppressed);
        RequestProfilingContext.methodExit(suppressed);

        RequestProfilingContext.CompletedTrace completed = RequestProfilingContext.finish();
        assertNotNull(completed);
        assertEquals(1, completed.capturedSpans());
        assertEquals(1, completed.droppedSpans());
        assertTrue(completed.spanLimitExceeded());
        assertTrue(completed.truncated());
    }

    @Test
    void recordsDeterministicLineStatsOnActiveMethodSpan() {
        MethodSpan root = newRoot();
        RequestProfilingContext.begin(root, 40, 5000);

        assertEquals(RequestProfilingContext.ENTER_SPAN,
            RequestProfilingContext.methodEnterState("com.x.A", "a"));
        RequestProfilingContext.lineEnter("com.x.A", "a", "A.java", 42);
        RequestProfilingContext.lineEnter("com.x.A", "a", "A.java", 43);
        RequestProfilingContext.methodExit();

        RequestProfilingContext.finish();
        MethodSpan a = root.children.get(0);
        assertEquals(2, a.lineStats.size());
        assertEquals(1L, a.lineStats.get(42).hits);
        assertEquals("A.java", a.lineStats.get(42).fileName);
        assertTrue(a.lineStats.get(42).wallNs >= 0L);
        assertEquals(1L, a.lineStats.get(43).hits);
    }

    @Test
    void recordsDeterministicLineAllocationOnActiveMethodSpan() {
        MethodSpan root = newRoot();
        RequestProfilingContext.begin(root, 40, 5000);

        assertEquals(RequestProfilingContext.ENTER_SPAN,
            RequestProfilingContext.methodEnterState("com.x.A", "a"));
        RequestProfilingContext.lineEnter("com.x.A", "a", "A.java", 42);
        RequestProfilingContext.recordLineAllocation("com.x.A", "a", "A.java",
            42, "byte[]", 128L);
        RequestProfilingContext.methodExit();

        RequestProfilingContext.finish();
        MethodSpan.LineStat stat = root.children.get(0).lineStats.get(42);
        assertNotNull(stat);
        assertEquals(1L, stat.allocationCount);
        assertEquals(128L, stat.allocatedBytes);
        assertEquals(1L, stat.allocByType.get("byte[]").count);
        assertEquals(128L, stat.allocByType.get("byte[]").bytes);
    }

    @Test
    void recordsDeterministicLineSelfTimeExcludingChildMethodTime()
            throws Exception {
        MethodSpan root = newRoot();
        RequestProfilingContext.begin(root, 40, 5000);

        assertEquals(RequestProfilingContext.ENTER_SPAN,
            RequestProfilingContext.methodEnterState("com.x.A", "a"));
        RequestProfilingContext.lineEnter("com.x.A", "a", "A.java", 42);

        assertEquals(RequestProfilingContext.ENTER_SPAN,
            RequestProfilingContext.methodEnterState("com.x.B", "b"));
        RequestProfilingContext.lineEnter("com.x.B", "b", "B.java", 7);
        Thread.sleep(2L);
        RequestProfilingContext.methodExit();

        RequestProfilingContext.lineEnter("com.x.A", "a", "A.java", 43);
        RequestProfilingContext.methodExit();

        RequestProfilingContext.finish();
        MethodSpan a = root.children.get(0);
        MethodSpan.LineStat parentCallLine = a.lineStats.get(42);
        assertNotNull(parentCallLine);
        assertTrue(parentCallLine.wallNs > 0L);
        assertTrue(parentCallLine.selfWallNs >= 0L);
        assertTrue(parentCallLine.selfWallNs <= parentCallLine.wallNs);
        assertTrue(parentCallLine.selfWallNs < parentCallLine.wallNs,
            "parent line self time should subtract traced child method time");
        assertTrue(parentCallLine.selfCpuNs <= parentCallLine.cpuNs);
    }

    @Test
    void allocationRecorderRequiresLineAllocationDetailForDeterministicLineMemory() {
        AllocationRecorder.configure(null, true, false, true);
        MethodSpan root = newRoot();
        RequestProfilingContext.begin(root, 40, 5000);

        assertEquals(RequestProfilingContext.ENTER_SPAN,
            RequestProfilingContext.methodEnterState("com.x.A", "a"));
        RequestProfilingContext.lineEnter("com.x.A", "a", "A.java", 42);
        AllocationRecorder.recordAt(new byte[16], "com.x.A", "a", "A.java", 42);
        RequestProfilingContext.methodExit();

        RequestProfilingContext.finish();
        MethodSpan.LineStat stat = root.children.get(0).lineStats.get(42);
        assertNotNull(stat);
        assertEquals(0L, stat.allocationCount);
        assertEquals(0L, stat.allocatedBytes);
        assertTrue(stat.allocByType.isEmpty());
    }
}
