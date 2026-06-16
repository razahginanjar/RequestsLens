package agent.http;

import agent.model.FlameNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProfilerHttpServerFlamegraphTest {

    @Test
    @SuppressWarnings("unchecked")
    void boundedFlamegraphAggregatesLowSignalAndExcessChildren() {
        FlameNode root = node("root", 100);
        root.children.put("demo.Hot.work", node("demo.Hot.work", 70));
        root.children.put("demo.Warm.work", node("demo.Warm.work", 20));
        root.children.put("demo.Cold.a", node("demo.Cold.a", 2));
        root.children.put("demo.Cold.b", node("demo.Cold.b", 1));

        Map<String, Object> response = ProfilerHttpServer.flamegraphResponse(root,
            new ProfilerHttpServer.FlamegraphOptions(5.0, 6, 1));

        assertEquals("root", response.get("frame"));
        assertEquals(100L, response.get("samples"));
        assertEquals(true, response.get("bounded"));
        assertEquals(3, response.get("hiddenFrames"));
        assertEquals(23L, response.get("hiddenSamples"));
        Map<String, Object> children = (Map<String, Object>) response.get("children");
        assertEquals(2, children.size());
        assertTrue(children.containsKey("demo.Hot.work"));

        Map<String, Object> other = (Map<String, Object>) children.get("(other-0)");
        assertEquals("Other frames", other.get("frame"));
        assertEquals(true, other.get("synthetic"));
        assertEquals(3, other.get("hiddenFrameCount"));
        assertEquals("children", other.get("hiddenReason"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void boundedFlamegraphStopsAtConfiguredDepth() {
        FlameNode root = node("root", 100);
        FlameNode controller = node("demo.Controller.handle", 100);
        FlameNode service = node("demo.Service.work", 90);
        FlameNode repo = node("demo.Repository.load", 80);
        root.children.put(controller.frame, controller);
        controller.children.put(service.frame, service);
        service.children.put(repo.frame, repo);

        Map<String, Object> response = ProfilerHttpServer.flamegraphResponse(root,
            new ProfilerHttpServer.FlamegraphOptions(0.0, 1, 10));

        Map<String, Object> children = (Map<String, Object>) response.get("children");
        Map<String, Object> controllerMap =
            (Map<String, Object>) children.get("demo.Controller.handle");
        Map<String, Object> controllerChildren =
            (Map<String, Object>) controllerMap.get("children");

        assertTrue(controllerChildren.containsKey("(other-1)"));
        Map<String, Object> other = (Map<String, Object>) controllerChildren.get("(other-1)");
        assertEquals("depth", other.get("hiddenReason"));
        assertEquals(2, other.get("hiddenFrameCount"));
    }

    private static FlameNode node(String frame, long samples) {
        FlameNode node = new FlameNode(frame);
        node.samples = samples;
        return node;
    }
}
