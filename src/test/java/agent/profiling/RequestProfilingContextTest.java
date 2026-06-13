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
}
