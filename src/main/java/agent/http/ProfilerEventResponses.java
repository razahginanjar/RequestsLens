package agent.http;

import agent.model.GcEvent;
import agent.model.JfrEvent;
import agent.model.LiveLogEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ProfilerEventResponses {

    private static final int MAX_LOG_RESPONSE_LIMIT = 1000;
    private static final int MAX_JFR_RESPONSE_LIMIT = 1000;

    private ProfilerEventResponses() {}

    static Map<String, Object> logsResponse(List<LiveLogEvent> appLogs,
                                            List<GcEvent> gcEvents,
                                            boolean appLogCaptureEnabled,
                                            int appLogCapacity,
                                            long capturedAppLogs,
                                            long droppedAppLogs,
                                            int limit,
                                            String kindFilter) {
        List<LiveLogEvent> safeAppLogs = appLogs == null ? List.of() : appLogs;
        List<GcEvent> safeGcEvents = gcEvents == null ? List.of() : gcEvents;
        int boundedLimit = Math.max(1, Math.min(limit, MAX_LOG_RESPONSE_LIMIT));
        boolean includeAppLogs = includeLogKind(kindFilter, "app-log", "app");
        boolean includeGc = includeLogKind(kindFilter, "gc", "jvm");

        List<Map<String, Object>> events = new ArrayList<>();
        if (includeAppLogs) {
            for (LiveLogEvent event : safeAppLogs) {
                events.add(appLogResponse(event));
            }
        }
        if (includeGc) {
            for (GcEvent event : safeGcEvents) {
                events.add(gcLogResponse(event));
            }
        }
        events.sort(Comparator.comparingLong(ProfilerEventResponses::eventTimestamp));
        int totalMatched = events.size();
        boolean limited = totalMatched > boundedLimit;
        if (limited) {
            events = new ArrayList<>(events.subList(totalMatched - boundedLimit, totalMatched));
        }

        Map<String, Object> response = ProfilerHttpServer.apiResponseStatic("logs");
        response.put("enabled", appLogCaptureEnabled);
        response.put("appLogCapacity", appLogCapacity);
        response.put("capturedAppLogs", capturedAppLogs);
        response.put("droppedAppLogs", droppedAppLogs);
        response.put("appLogCount", safeAppLogs.size());
        response.put("gcEventCount", safeGcEvents.size());
        response.put("eventCount", events.size());
        response.put("totalMatchedEvents", totalMatched);
        response.put("limited", limited);
        response.put("limit", boundedLimit);
        response.put("kind", normalizedLogKind(kindFilter));
        response.put("events", events);
        return response;
    }

    static Map<String, Object> jfrEventsResponse(List<JfrEvent> jfrEvents,
                                                 boolean configured,
                                                 boolean available,
                                                 boolean running,
                                                 int capacity,
                                                 long capturedEvents,
                                                 long droppedEvents,
                                                 long errorCount,
                                                 List<String> unsupportedEvents,
                                                 int limit,
                                                 String categoryFilter) {
        List<JfrEvent> safeEvents = jfrEvents == null ? List.of() : jfrEvents;
        List<String> safeUnsupported = unsupportedEvents == null
            ? List.of()
            : unsupportedEvents;
        int boundedLimit = Math.max(1, Math.min(limit, MAX_JFR_RESPONSE_LIMIT));
        String normalizedCategory = normalizedJfrCategory(categoryFilter);

        List<Map<String, Object>> events = new ArrayList<>();
        for (JfrEvent event : safeEvents) {
            if (includeJfrCategory(normalizedCategory, event.category())) {
                events.add(jfrEventResponse(event));
            }
        }
        events.sort(Comparator.comparingLong(ProfilerEventResponses::eventTimestamp));
        int totalMatched = events.size();
        boolean limited = totalMatched > boundedLimit;
        if (limited) {
            events = new ArrayList<>(events.subList(totalMatched - boundedLimit, totalMatched));
        }

        Map<String, Object> response = ProfilerHttpServer.apiResponseStatic("jfr.events");
        response.put("configured", configured);
        response.put("available", available);
        response.put("running", running);
        response.put("enabled", configured && available);
        response.put("capacity", capacity);
        response.put("capturedEvents", capturedEvents);
        response.put("droppedEvents", droppedEvents);
        response.put("errorCount", errorCount);
        response.put("bufferedEventCount", safeEvents.size());
        response.put("eventCount", events.size());
        response.put("totalMatchedEvents", totalMatched);
        response.put("limited", limited);
        response.put("limit", boundedLimit);
        response.put("category", normalizedCategory);
        response.put("categories", List.of("all", "gc", "thread", "lock", "io",
            "exception", "cpu", "jvm"));
        response.put("unsupportedEvents", safeUnsupported);
        response.put("events", events);
        return response;
    }

    private static boolean includeJfrCategory(String normalizedFilter, String category) {
        return "all".equals(normalizedFilter)
            || normalizedFilter.equals(category == null ? "" : category);
    }

    private static String normalizedJfrCategory(String filter) {
        if (filter == null || filter.isBlank()) return "all";
        String normalized = filter.trim().toLowerCase();
        return switch (normalized) {
            case "gc", "thread", "lock", "io", "exception", "cpu", "jvm" -> normalized;
            default -> "all";
        };
    }

    private static Map<String, Object> jfrEventResponse(JfrEvent event) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("timestampMs", event.timestampMs());
        response.put("eventType", event.eventType());
        response.put("category", event.category());
        response.put("name", event.name());
        response.put("durationMs", event.durationMs());
        response.put("durationNs", event.durationNs());
        response.put("threadName", event.threadName());
        response.put("message", event.message());
        response.put("attributes", event.attributes());
        return response;
    }

    private static boolean includeLogKind(String filter, String primary, String alias) {
        String normalized = normalizedLogKind(filter);
        return "all".equals(normalized)
            || primary.equals(normalized)
            || alias.equals(normalized);
    }

    private static String normalizedLogKind(String filter) {
        if (filter == null || filter.isBlank()) return "all";
        String normalized = filter.trim().toLowerCase();
        if ("app".equals(normalized)) return "app-log";
        if ("jvm".equals(normalized)) return "gc";
        if ("app-log".equals(normalized) || "gc".equals(normalized)) return normalized;
        return "all";
    }

    private static Map<String, Object> appLogResponse(LiveLogEvent event) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("timestampMs", event.timestampMs());
        response.put("kind", event.kind());
        response.put("source", event.source());
        response.put("level", event.level());
        response.put("loggerName", event.loggerName());
        response.put("threadName", event.threadName());
        response.put("message", event.message());
        response.put("throwable", event.throwable());
        return response;
    }

    private static Map<String, Object> gcLogResponse(GcEvent event) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("timestampMs", event.timestampMs());
        response.put("kind", "gc");
        response.put("source", "jvm-gc");
        response.put("level", event.durationMs() >= 1000L ? "WARN" : "INFO");
        response.put("loggerName", event.gcName());
        response.put("threadName", "JVM");
        response.put("message", "GC " + event.gcName()
            + " cause=" + event.gcCause()
            + " pause=" + event.durationMs() + "ms"
            + " heap=" + event.heapBeforeBytes() + "->" + event.heapAfterBytes());
        response.put("throwable", "");
        response.put("gcName", event.gcName());
        response.put("gcCause", event.gcCause());
        response.put("durationMs", event.durationMs());
        response.put("heapBeforeBytes", event.heapBeforeBytes());
        response.put("heapAfterBytes", event.heapAfterBytes());
        return response;
    }

    private static long eventTimestamp(Map<String, Object> event) {
        Object value = event.get("timestampMs");
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
