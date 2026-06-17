package agent.profiling.asyncprofiler;

import agent.model.FlameNode;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AsyncProfilerControllerTest {

    @Test
    void parseCollapsedBuildsFlameTreeAndTopStacks() {
        String collapsed = String.join(System.lineSeparator(),
            "demo.Controller.handle;demo.Service.work 3",
            "demo.Controller.handle;demo.Repository.query 2");

        AsyncProfilerController.CollapsedSnapshot snapshot =
            AsyncProfilerController.parseCollapsed(collapsed, 100);

        assertEquals(2, snapshot.stackCount());
        assertEquals(0, snapshot.skippedLines());
        assertFalse(snapshot.truncated());
        assertEquals(5L, snapshot.root().samples);
        FlameNode controller = snapshot.root().children.get("demo.Controller.handle");
        assertNotNull(controller);
        assertEquals(5L, controller.samples);
        assertEquals(3L, controller.children.get("demo.Service.work").samples);
        assertEquals(2L, controller.children.get("demo.Repository.query").samples);
        assertEquals("demo.Controller.handle;demo.Service.work",
            snapshot.stacks().get(0).stack());
    }

    @Test
    void parseCollapsedSkipsAgentFramesAndLimitsInput() {
        String collapsed = String.join(System.lineSeparator(),
            "agent.http.Server.handle;demo.Controller.handle 9",
            "bad-line",
            "demo.Controller.handle;demo.Service.work 4",
            "demo.Controller.handle;demo.Service.other 2");

        AsyncProfilerController.CollapsedSnapshot snapshot =
            AsyncProfilerController.parseCollapsed(collapsed, 1);

        assertTrue(snapshot.truncated());
        assertEquals(1, snapshot.stackCount());
        assertEquals(2, snapshot.skippedLines());
        assertEquals(4L, snapshot.root().samples);
        assertFalse(snapshot.root().children.containsKey("agent.http.Server.handle"));
    }
}
