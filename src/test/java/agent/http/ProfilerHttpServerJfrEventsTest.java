package agent.http;

import agent.model.JfrEvent;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProfilerHttpServerJfrEventsTest {

    @Test
    void jfrEventsResponseFiltersByCategory() {
        JfrEvent gc = event(100L, "jdk.GarbageCollection", "gc", "G1", 5L);
        JfrEvent io = event(200L, "jdk.FileRead", "io", "File Read", 12L);

        Map<String, Object> response = ProfilerEventResponses.jfrEventsResponse(
            List.of(gc, io), true, true, true, 500,
            2L, 0L, 0L, List.of(), 20, "io");

        assertEquals("jfr.events", response.get("resource"));
        assertEquals(true, response.get("configured"));
        assertEquals(true, response.get("available"));
        assertEquals(true, response.get("running"));
        assertEquals("io", response.get("category"));
        assertEquals(1, response.get("eventCount"));
        assertEquals(1, response.get("totalMatchedEvents"));

        List<?> events = (List<?>) response.get("events");
        Map<?, ?> row = (Map<?, ?>) events.get(0);
        assertEquals("jdk.FileRead", row.get("eventType"));
        assertEquals("io", row.get("category"));
        assertEquals(12L, row.get("durationMs"));
    }

    @Test
    void jfrEventsResponseReturnsNewestEventsWhenLimited() {
        JfrEvent first = event(100L, "jdk.ThreadSleep", "thread", "Sleep", 1L);
        JfrEvent second = event(300L, "jdk.CPULoad", "cpu", "CPU Load", 0L);

        Map<String, Object> response = ProfilerEventResponses.jfrEventsResponse(
            List.of(first, second), true, true, true, 2,
            2L, 1L, 0L, List.of("jdk.Unknown"), 1, "bad-filter");

        assertEquals("all", response.get("category"));
        assertEquals(1, response.get("eventCount"));
        assertEquals(2, response.get("totalMatchedEvents"));
        assertEquals(true, response.get("limited"));
        assertEquals(1L, response.get("droppedEvents"));
        assertEquals(List.of("jdk.Unknown"), response.get("unsupportedEvents"));

        List<?> events = (List<?>) response.get("events");
        Map<?, ?> row = (Map<?, ?>) events.get(0);
        assertEquals(300L, row.get("timestampMs"));
        assertEquals("jdk.CPULoad", row.get("eventType"));
    }

    private static JfrEvent event(long timestampMs, String eventType, String category,
                                  String name, long durationMs) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("name", name);
        return new JfrEvent(timestampMs, eventType, category, name,
            durationMs, durationMs * 1_000_000L, "main",
            eventType + " duration=" + durationMs + "ms", attributes);
    }
}
