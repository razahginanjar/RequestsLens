package agent.alert;

import agent.model.AlertPayload;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Logger;

/**
 * Sends alert payloads to a configured webhook URL via HTTP POST.
 *
 * <h2>Async dispatch</h2>
 * All HTTP POSTs run on a dedicated 2-thread daemon pool. {@link #dispatch}
 * returns immediately — the caller (AlertEvaluator on the aggregation daemon)
 * is never blocked by network I/O, even if the receiver is slow or down.
 *
 * <h2>Retry policy</h2>
 * Up to 3 attempts with exponential back-off (1s, 2s, 4s). After the final
 * failure the alert is logged and dropped, and the failure counter increments.
 *
 * <h2>Disabled state</h2>
 * If no webhook URL is configured, {@link #dispatch} just logs the alert
 * locally — useful for development and for users who only want the HTTP API.
 */
public final class WebhookDispatcher {

    private static final Logger log =
        Logger.getLogger(WebhookDispatcher.class.getName());

    private static final int MAX_RETRIES = 3;

    private final String          webhookUrl;
    private final ObjectMapper    json;
    private final ExecutorService pool;
    private final HttpClient      httpClient;

    /** Counts alerts that failed delivery after all retries (read via getter). */
    private final LongAdder failureCount = new LongAdder();

    public WebhookDispatcher(String webhookUrl) {
        this.webhookUrl = webhookUrl;
        this.json       = new ObjectMapper();

        // 2 daemon threads — enough for bursts of alerts without blocking.
        this.pool = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "profiler-webhook-dispatcher");
            t.setDaemon(true);
            return t;
        });

        // Connect timeout so we never hang forever reaching a dead receiver.
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    }

    /**
     * Dispatches an alert payload asynchronously. Returns immediately; never blocks.
     * If no webhook URL is configured the alert is logged locally instead.
     */
    public void dispatch(AlertPayload payload) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            log.info("Alert (no webhook configured): " + payload.alertType()
                + " [" + payload.severity() + "] " + payload.message());
            return;
        }
        pool.submit(() -> sendWithRetry(payload));
    }

    /** Sends the payload, retrying with exponential back-off. Runs on the pool. */
    private void sendWithRetry(AlertPayload payload) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                String body = json.writeValueAsString(payload);

                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

                HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    // RESOLVED alerts are routine — don't log them as info noise.
                    if (!"RESOLVED".equals(payload.severity())) {
                        log.info("Webhook delivered: " + payload.alertType()
                            + " [" + payload.severity() + "] → HTTP "
                            + response.statusCode());
                    }
                    return;   // success
                }

                log.warning("Webhook returned HTTP " + response.statusCode()
                    + " (attempt " + attempt + "/" + MAX_RETRIES + ")");

            } catch (Exception e) {
                log.warning("Webhook POST failed (attempt " + attempt
                    + "/" + MAX_RETRIES + "): " + e.getMessage());
            }

            // Back-off before the next attempt: 1s, 2s, 4s.
            if (attempt < MAX_RETRIES) {
                try {
                    Thread.sleep((long) Math.pow(2, attempt - 1) * 1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        log.warning("Webhook delivery permanently failed after " + MAX_RETRIES
            + " attempts: " + payload.alertType() + " [" + payload.severity() + "]");
        failureCount.increment();
    }

    /** Number of alerts that failed delivery after all retries. */
    public long getFailureCount() {
        return failureCount.sum();
    }
}
