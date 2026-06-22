package agent.collector.jfr;

import agent.buffer.RingBuffer;
import agent.core.AgentConfig;
import agent.model.JfrEvent;

import jdk.jfr.EventSettings;
import jdk.jfr.FlightRecorder;
import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingStream;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Logger;

/**
 * Self-contained JFR integration.
 *
 * <p>The recorder runs an in-process {@link RecordingStream} on one daemon
 * thread and stores selected JVM events in a bounded ring buffer. It is opt-in
 * and best-effort: unsupported event types or stream failures are reported via
 * counters/status fields but must never disrupt the target application.
 */
public final class JfrEventRecorder {

    private static final Logger log = Logger.getLogger(JfrEventRecorder.class.getName());
    private static final int MAX_MESSAGE_LENGTH = 512;
    private static final int MAX_ATTRIBUTE_LENGTH = 300;

    private static final Map<String, String> CATEGORIES = Map.ofEntries(
        Map.entry("jdk.GarbageCollection", "gc"),
        Map.entry("jdk.GCPhasePause", "gc"),
        Map.entry("jdk.ThreadPark", "thread"),
        Map.entry("jdk.ThreadSleep", "thread"),
        Map.entry("jdk.JavaMonitorEnter", "lock"),
        Map.entry("jdk.JavaMonitorWait", "lock"),
        Map.entry("jdk.FileRead", "io"),
        Map.entry("jdk.FileWrite", "io"),
        Map.entry("jdk.SocketRead", "io"),
        Map.entry("jdk.SocketWrite", "io"),
        Map.entry("jdk.ExceptionStatistics", "exception"),
        Map.entry("jdk.CPULoad", "cpu")
    );

    private static final String[] COMMON_FIELDS = {
        "gcId", "name", "cause", "sumOfPauses", "longestPause",
        "parkedClass", "timeout", "until",
        "monitorClass", "previousOwner", "notifier", "timedOut", "address",
        "time",
        "path", "bytesRead", "bytesWritten", "endOfFile", "endOfStream",
        "host", "port",
        "throwables", "errors",
        "jvmUser", "jvmSystem", "machineTotal"
    };

    private final AgentConfig config;
    private final RingBuffer<JfrEvent> buffer;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final LongAdder captured = new LongAdder();
    private final LongAdder dropped = new LongAdder();
    private final LongAdder errors = new LongAdder();
    private final CopyOnWriteArrayList<String> unsupportedEvents = new CopyOnWriteArrayList<>();

    private volatile boolean running;
    private volatile RecordingStream stream;

    public JfrEventRecorder(AgentConfig config, RingBuffer<JfrEvent> buffer) {
        this.config = config;
        this.buffer = buffer;
    }

    public void start() {
        if (!started.compareAndSet(false, true)) return;
        if (!isJfrAvailable()) {
            log.warning("JFR is not available in this JVM; profiler.jfr.enabled is ignored.");
            return;
        }
        Thread thread = new Thread(this::runStream, "requestlens-jfr-events");
        thread.setDaemon(true);
        thread.start();
    }

    public boolean isRunning() {
        return running;
    }

    public long capturedCount() {
        return captured.sum();
    }

    public long droppedCount() {
        return dropped.sum();
    }

    public long errorCount() {
        return errors.sum();
    }

    public List<String> unsupportedEvents() {
        return List.copyOf(unsupportedEvents);
    }

    public static boolean isJfrAvailable() {
        try {
            return FlightRecorder.isAvailable();
        } catch (Throwable failure) {
            log.fine("JFR availability check failed: " + failure.getClass().getSimpleName()
                + ": " + failure.getMessage());
            return false;
        }
    }

    private void runStream() {
        try (RecordingStream recordingStream = new RecordingStream()) {
            this.stream = recordingStream;
            configure(recordingStream);
            running = true;
            recordingStream.start();
        } catch (Throwable t) {
            errors.increment();
            log.warning("JFR event stream stopped: " + t.getMessage());
        } finally {
            running = false;
            stream = null;
        }
    }

    private void configure(RecordingStream recordingStream) {
        Duration threshold = Duration.ofMillis(config.getJfrThresholdMs());
        enableEvent(recordingStream, "jdk.GarbageCollection", null);
        enableEvent(recordingStream, "jdk.GCPhasePause", threshold);
        enableEvent(recordingStream, "jdk.ThreadPark", threshold);
        enableEvent(recordingStream, "jdk.ThreadSleep", threshold);
        enableEvent(recordingStream, "jdk.JavaMonitorEnter", threshold);
        enableEvent(recordingStream, "jdk.JavaMonitorWait", threshold);
        enableEvent(recordingStream, "jdk.FileRead", threshold);
        enableEvent(recordingStream, "jdk.FileWrite", threshold);
        enableEvent(recordingStream, "jdk.SocketRead", threshold);
        enableEvent(recordingStream, "jdk.SocketWrite", threshold);
        enablePeriodicEvent(recordingStream, "jdk.ExceptionStatistics", Duration.ofSeconds(10));
        enablePeriodicEvent(recordingStream, "jdk.CPULoad", Duration.ofSeconds(5));
    }

    private void enableEvent(RecordingStream recordingStream, String eventName,
                             Duration threshold) {
        try {
            EventSettings settings = recordingStream.enable(eventName);
            if (threshold != null) {
                settings.withThreshold(threshold);
            }
            recordingStream.onEvent(eventName, this::record);
        } catch (Throwable t) {
            unsupportedEvents.add(eventName);
            log.fine("JFR event not enabled: " + eventName + " (" + t.getMessage() + ")");
        }
    }

    private void enablePeriodicEvent(RecordingStream recordingStream, String eventName,
                                     Duration period) {
        try {
            recordingStream.enable(eventName).withPeriod(period);
            recordingStream.onEvent(eventName, this::record);
        } catch (Throwable t) {
            unsupportedEvents.add(eventName);
            log.fine("JFR periodic event not enabled: " + eventName + " (" + t.getMessage() + ")");
        }
    }

    private void record(RecordedEvent event) {
        if (event == null) return;
        try {
            String eventType = event.getEventType().getName();
            String category = CATEGORIES.getOrDefault(eventType, "jvm");
            Map<String, Object> attributes = attributes(event, errors);
            long durationNs = durationNs(event, errors);
            JfrEvent jfrEvent = new JfrEvent(
                timestampMs(event, errors),
                eventType,
                category,
                eventName(event, attributes),
                durationNs / 1_000_000L,
                durationNs,
                threadName(event, errors),
                message(eventType, category, attributes, durationNs),
                attributes
            );
            captured.increment();
            if (!buffer.write(jfrEvent)) {
                dropped.increment();
            }
        } catch (Throwable failure) {
            errors.increment();
        }
    }

    private static Map<String, Object> attributes(RecordedEvent event, LongAdder errors) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        for (String field : COMMON_FIELDS) {
            putIfPresent(event, attributes, field, errors);
        }
        return attributes;
    }

    private static void putIfPresent(RecordedEvent event, Map<String, Object> attributes,
                                     String field, LongAdder errors) {
        try {
            if (!event.hasField(field)) return;
            Object value = simplify(event.getValue(field));
            if (value != null) {
                attributes.put(field, value);
            }
        } catch (Throwable failure) {
            errors.increment();
            // Some event fields are JVM/vendor specific. Skip fields we cannot read.
        }
    }

    private static Object simplify(Object value) {
        if (value == null) return null;
        if (value instanceof Number || value instanceof Boolean || value instanceof String) {
            return value;
        }
        if (value instanceof RecordedClass recordedClass) {
            return recordedClass.getName();
        }
        if (value instanceof RecordedThread recordedThread) {
            String name = recordedThread.getJavaName();
            return name == null || name.isBlank()
                ? "thread-" + recordedThread.getJavaThreadId()
                : name;
        }
        if (value instanceof Duration duration) {
            return duration.toMillis();
        }
        if (value instanceof Instant instant) {
            return instant.toEpochMilli();
        }
        return truncate(String.valueOf(value), MAX_ATTRIBUTE_LENGTH);
    }

    private static long timestampMs(RecordedEvent event, LongAdder errors) {
        try {
            Instant start = event.getStartTime();
            if (start != null) return start.toEpochMilli();
        } catch (Throwable failure) {
            errors.increment();
        }
        return System.currentTimeMillis();
    }

    private static long durationNs(RecordedEvent event, LongAdder errors) {
        try {
            Duration duration = event.getDuration();
            return duration == null ? 0L : Math.max(0L, duration.toNanos());
        } catch (Throwable failure) {
            errors.increment();
            return 0L;
        }
    }

    private static String threadName(RecordedEvent event, LongAdder errors) {
        try {
            RecordedThread thread = event.getThread();
            if (thread != null && thread.getJavaName() != null
                    && !thread.getJavaName().isBlank()) {
                return thread.getJavaName();
            }
        } catch (Throwable failure) {
            errors.increment();
        }
        return "JVM";
    }

    private static String eventName(RecordedEvent event, Map<String, Object> attributes) {
        Object name = attributes.get("name");
        if (name instanceof String value && !value.isBlank()) {
            return value;
        }
        String label = event.getEventType().getLabel();
        if (label != null && !label.isBlank()) {
            return label;
        }
        String type = event.getEventType().getName();
        int dot = type.lastIndexOf('.');
        return dot >= 0 ? type.substring(dot + 1) : type;
    }

    private static String message(String eventType, String category,
                                  Map<String, Object> attributes,
                                  long durationNs) {
        List<String> parts = new ArrayList<>();
        parts.add(shortEventName(eventType));
        if (attributes.containsKey("name")) {
            parts.add("name=" + attributes.get("name"));
        }
        if (attributes.containsKey("cause")) {
            parts.add("cause=" + attributes.get("cause"));
        }
        if (attributes.containsKey("path")) {
            parts.add("path=" + attributes.get("path"));
        }
        if (attributes.containsKey("host")) {
            parts.add("host=" + attributes.get("host"));
        }
        Object bytes = attributes.get("bytesRead");
        if (bytes == null) bytes = attributes.get("bytesWritten");
        if (bytes != null) {
            parts.add("bytes=" + bytes);
        }
        if ("exception".equals(category) && attributes.containsKey("throwables")) {
            parts.add("throwables=" + attributes.get("throwables"));
        }
        if ("cpu".equals(category)) {
            parts.add("jvm=" + percent(attributes.get("jvmUser"))
                + "/" + percent(attributes.get("jvmSystem")));
            parts.add("machine=" + percent(attributes.get("machineTotal")));
        }
        if (durationNs > 0L) {
            parts.add("duration=" + durationText(durationNs));
        }
        return truncate(String.join(" ", parts), MAX_MESSAGE_LENGTH);
    }

    private static String shortEventName(String eventType) {
        int dot = eventType.lastIndexOf('.');
        return dot >= 0 ? eventType.substring(dot + 1) : eventType;
    }

    private static String percent(Object value) {
        if (value instanceof Number number) {
            return String.format(Locale.ROOT, "%.1f%%", number.doubleValue() * 100.0);
        }
        return "-";
    }

    private static String durationText(long durationNs) {
        if (durationNs < 1_000_000L) {
            return (durationNs / 1_000L) + "us";
        }
        return String.format(Locale.ROOT, "%.2fms", durationNs / 1_000_000.0);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) return "";
        if (value.length() <= maxLength) return value;
        return value.substring(0, Math.max(0, maxLength - 3)) + "...";
    }
}
