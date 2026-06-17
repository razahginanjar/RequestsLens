package agent.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A compact JVM event captured from the in-process JFR RecordingStream.
 */
public record JfrEvent(
    long timestampMs,
    String eventType,
    String category,
    String name,
    long durationMs,
    long durationNs,
    String threadName,
    String message,
    Map<String, Object> attributes
) {
    public JfrEvent {
        eventType = nullToUnknown(eventType);
        category = nullToUnknown(category);
        name = nullToUnknown(name);
        threadName = nullToUnknown(threadName);
        message = message == null ? "" : message;
        attributes = attributes == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    private static String nullToUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
