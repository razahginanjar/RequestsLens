package agent.profiling;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Background sampler for request-scoped line hotspots.
 */
public final class RequestLineSampler {

    private static final Logger log = Logger.getLogger(RequestLineSampler.class.getName());

    private final long intervalMs;

    public RequestLineSampler(long intervalMs) {
        this.intervalMs = Math.max(1L, intervalMs);
    }

    public void start() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "profiler-request-line-sampler");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        scheduler.scheduleAtFixedRate(LineProfilingSupport::sampleActiveRequests,
            intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        log.info("RequestLineSampler started - interval=" + intervalMs + "ms");
    }
}
