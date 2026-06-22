package agent.http;

import agent.model.GcEvent;
import agent.model.LiveLogEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProfilerHttpServerLogsTest {

    @Test
    @SuppressWarnings("unchecked")
    void logsResponseMergesAppLogsAndGcEventsByTimeWithLimit() {
        LiveLogEvent appA = new LiveLogEvent(100L, "app-log", "logback",
            "INFO", "demo.App", "main", "first", "");
        LiveLogEvent appB = new LiveLogEvent(300L, "app-log", "logback",
            "WARN", "demo.App", "main", "third", "");
        GcEvent gc = new GcEvent(200L, "G1 Young Generation",
            "Allocation Failure", 12L, 1000L, 800L);

        Map<String, Object> response = ProfilerEventResponses.logsResponse(
            List.of(appA, appB), List.of(gc), true, 1000, 2L, 0L, 2, "all");

        assertEquals("logs", response.get("resource"));
        assertEquals(true, response.get("enabled"));
        assertEquals(true, response.get("limited"));
        assertEquals(3, response.get("totalMatchedEvents"));

        List<Map<String, Object>> events =
            (List<Map<String, Object>>) response.get("events");
        assertEquals(2, events.size());
        assertEquals("gc", events.get(0).get("kind"));
        assertEquals("app-log", events.get(1).get("kind"));
        assertEquals("third", events.get(1).get("message"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void logsResponseCanFilterGcOnly() {
        LiveLogEvent app = new LiveLogEvent(100L, "app-log", "logback",
            "INFO", "demo.App", "main", "first", "");
        GcEvent gc = new GcEvent(200L, "G1 Old Generation",
            "System.gc()", 30L, 2000L, 900L);

        Map<String, Object> response = ProfilerEventResponses.logsResponse(
            List.of(app), List.of(gc), false, 1000, 1L, 0L, 10, "gc");

        List<Map<String, Object>> events =
            (List<Map<String, Object>>) response.get("events");
        assertEquals(1, events.size());
        assertEquals("gc", events.get(0).get("kind"));
        assertEquals(30L, events.get(0).get("durationMs"));
    }
}
