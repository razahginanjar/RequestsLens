package agent.collector.logging;

import agent.buffer.RingBuffer;
import agent.model.LiveLogEvent;

import java.lang.reflect.Method;
import java.text.MessageFormat;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.LogRecord;

/**
 * Shared sink for opt-in live target-log capture.
 *
 * <p>Advice and JUL handlers call this class from logging hot paths. Keep the
 * implementation allocation-conscious, never log from here, and catch all
 * reflection failures so target logging is never disrupted.
 */
public final class LogCaptureSupport {

    private static final int MAX_MESSAGE_LENGTH = 4096;
    private static final int MAX_THROWABLE_LENGTH = 2048;

    private static volatile boolean enabled;
    private static volatile RingBuffer<LiveLogEvent> buffer;
    private static final LongAdder captured = new LongAdder();
    private static final LongAdder dropped = new LongAdder();

    private LogCaptureSupport() {
    }

    public static void configure(boolean enabled, RingBuffer<LiveLogEvent> buffer) {
        LogCaptureSupport.enabled = enabled;
        LogCaptureSupport.buffer = buffer;
        captured.reset();
        dropped.reset();
    }

    public static boolean isEnabled() {
        return enabled && buffer != null;
    }

    public static long capturedCount() {
        return captured.sum();
    }

    public static long droppedCount() {
        return dropped.sum();
    }

    static void record(String source, long timestampMs, String level, String loggerName,
                       String threadName, String message, String throwable) {
        RingBuffer<LiveLogEvent> sink = buffer;
        if (!enabled || sink == null) return;
        if (isInternalLogger(loggerName)) return;
        try {
            LiveLogEvent event = new LiveLogEvent(
                timestampMs <= 0L ? System.currentTimeMillis() : timestampMs,
                "app-log",
                nullToUnknown(source),
                nullToUnknown(level),
                nullToUnknown(loggerName),
                threadName == null || threadName.isBlank()
                    ? Thread.currentThread().getName()
                    : threadName,
                truncate(message, MAX_MESSAGE_LENGTH),
                truncate(throwable, MAX_THROWABLE_LENGTH)
            );
            captured.increment();
            if (!sink.write(event)) {
                dropped.increment();
            }
        } catch (Throwable failure) {
            dropped.increment();
        }
    }

    public static void recordJul(LogRecord record) {
        if (record == null) return;
        String throwable = throwableSummary(record.getThrown());
        record("jul", record.getMillis(), String.valueOf(record.getLevel()),
            record.getLoggerName(), Thread.currentThread().getName(),
            formatJulMessage(record), throwable);
    }

    public static void recordLogbackEvent(Object logger, Object event) {
        if (event == null) return;
        record("logback",
            longMethod(event, "getTimeStamp", System.currentTimeMillis()),
            stringMethod(event, "getLevel", "INFO"),
            firstNonBlank(stringMethod(event, "getLoggerName", null),
                stringMethod(logger, "getName", "unknown")),
            stringMethod(event, "getThreadName", Thread.currentThread().getName()),
            stringMethod(event, "getFormattedMessage", stringMethod(event, "getMessage", "")),
            throwableProxySummary(objectMethod(event, "getThrowableProxy")));
    }

    public static void recordLog4j2Event(Object logger, Object[] args) {
        if (args == null) return;
        Object level = null;
        Object message = null;
        Throwable throwable = null;
        for (Object arg : args) {
            if (arg == null) continue;
            String className = arg.getClass().getName();
            if (level == null && "org.apache.logging.log4j.Level".equals(className)) {
                level = arg;
            } else if (message == null
                    && className.startsWith("org.apache.logging.log4j.message.")) {
                message = arg;
            } else if (throwable == null && arg instanceof Throwable t) {
                throwable = t;
            }
        }
        record("log4j2", System.currentTimeMillis(), String.valueOf(level),
            stringMethod(logger, "getName", "unknown"), Thread.currentThread().getName(),
            log4jMessage(message), throwableSummary(throwable));
    }

    private static boolean isInternalLogger(String loggerName) {
        if (loggerName == null) return false;
        return loggerName.startsWith("agent.")
            || loggerName.startsWith("agent.shaded.")
            || loggerName.startsWith("io.javalin.")
            || loggerName.startsWith("org.eclipse.jetty.");
    }

    private static String formatJulMessage(LogRecord record) {
        String message = record.getMessage();
        Object[] params = record.getParameters();
        if (message == null || params == null || params.length == 0) {
            return message == null ? "" : message;
        }
        try {
            return MessageFormat.format(message, params);
        } catch (IllegalArgumentException e) {
            return message;
        }
    }

    private static String log4jMessage(Object message) {
        if (message == null) return "";
        String formatted = stringMethod(message, "getFormattedMessage", null);
        return formatted == null ? String.valueOf(message) : formatted;
    }

    private static String throwableSummary(Throwable throwable) {
        if (throwable == null) return "";
        String message = throwable.getMessage();
        return throwable.getClass().getName()
            + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private static String throwableProxySummary(Object proxy) {
        if (proxy == null) return "";
        String className = stringMethod(proxy, "getClassName", proxy.getClass().getName());
        String message = stringMethod(proxy, "getMessage", "");
        return className + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private static Object objectMethod(Object target, String name) {
        if (target == null) return null;
        try {
            Method method = target.getClass().getMethod(name);
            return method.invoke(target);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
        }
    }

    private static String stringMethod(Object target, String name, String def) {
        Object value = objectMethod(target, name);
        return value == null ? def : String.valueOf(value);
    }

    private static long longMethod(Object target, String name, long def) {
        Object value = objectMethod(target, name);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return def;
    }

    private static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private static String nullToUnknown(String value) {
        return value == null || value.isBlank() || "null".equals(value) ? "unknown" : value;
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        if (value.length() <= max) return value;
        return value.substring(0, Math.max(0, max - 12)) + "...truncated";
    }
}
