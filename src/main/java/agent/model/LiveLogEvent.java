package agent.model;

/**
 * Bounded live log row captured from the target JVM.
 */
public record LiveLogEvent(
    long timestampMs,
    String kind,
    String source,
    String level,
    String loggerName,
    String threadName,
    String message,
    String throwable
) {
}
