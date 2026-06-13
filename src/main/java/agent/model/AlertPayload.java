package agent.model;

import java.util.Map;

/**
 * The JSON payload sent to webhook endpoints when an alert fires.
 *
 * <p>Designed to be generic enough for any HTTP receiver (Slack, PagerDuty,
 * a custom endpoint). Jackson serializes this record directly to JSON.
 */
public record AlertPayload(
    /** Which agent instance generated this alert (host:port). */
    String instanceId,

    /** The type of alert: e.g. LEAK_WARNING, GC_OVERHEAD. */
    String alertType,

    /** Severity: WARN, CRITICAL, or RESOLVED. */
    String severity,

    /** Human-readable description of what triggered the alert. */
    String message,

    /** When the alert was generated — epoch milliseconds. */
    long timestampMs,

    /** Additional context — heap growth %, GC overhead %, etc. */
    Map<String, Object> metadata
) {}
