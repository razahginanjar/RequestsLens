package agent.collector.cpu;

import agent.buffer.RingBuffer;
import agent.core.AgentConfig;
import agent.core.AgentSelfMetrics;
import agent.core.CollectorRegistry;
import agent.model.CpuSnapshot;
import agent.persistence.PersistenceWriter;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Periodically samples process/system CPU and the CPU consumed by profiler
 * daemon threads.
 *
 * <p>This is deliberately separate from request tracing: process/system CPU is
 * sampled once per configured interval, while per-request CPU is captured in the
 * DispatcherServlet advice via per-thread CPU counters.
 */
public final class CpuSampler {

    private static final Logger log = Logger.getLogger(CpuSampler.class.getName());

    private static final long INITIAL_DELAY_MS = 1000L;

    private final CollectorRegistry registry;
    private final RingBuffer<CpuSnapshot> buffer;
    private final AgentSelfMetrics selfMetrics;
    private final AgentConfig config;
    private final OperatingSystemMXBean osBean;
    private final com.sun.management.OperatingSystemMXBean sunOsBean;
    private final ThreadMXBean threadBean;

    private volatile long lastAgentThreadCpuNs = -1L;
    private volatile long lastWallNs = -1L;

    public CpuSampler(CollectorRegistry registry, AgentConfig config) {
        this.registry = registry;
        this.buffer = registry.cpuBuffer();
        this.selfMetrics = registry.selfMetrics();
        this.config = config;
        this.osBean = ManagementFactory.getOperatingSystemMXBean();
        this.sunOsBean = osBean instanceof com.sun.management.OperatingSystemMXBean bean
            ? bean
            : null;
        this.threadBean = ManagementFactory.getThreadMXBean();
        enableThreadCpuTime();
    }

    /**
     * Starts the CPU sampling daemon. Returns immediately.
     */
    public void start() {
        Thread samplerThread = new Thread(() -> {
            log.info("CpuSampler started - interval="
                + config.getCpuSamplingIntervalMs() + "ms");

            try {
                Thread.sleep(INITIAL_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            while (!Thread.currentThread().isInterrupted()) {
                try {
                    sample();
                    Thread.sleep(config.getCpuSamplingIntervalMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.info("CpuSampler interrupted - stopping");
                    break;
                } catch (Throwable t) {
                    log.warning("CpuSampler error: " + t.getMessage());
                }
            }
        }, "profiler-cpu-sampler");

        samplerThread.setDaemon(true);
        samplerThread.setPriority(Thread.MIN_PRIORITY);
        samplerThread.start();
    }

    /**
     * Takes one CPU snapshot and publishes it to live and persisted outputs.
     */
    void sample() {
        CpuSnapshot snapshot = snapshotNow();
        boolean written = buffer.write(snapshot);
        if (!written) {
            selfMetrics.incrementDroppedCpuSamples();
        }
        registry.setLatestCpuSnapshot(snapshot);
        selfMetrics.setLastCpuSampleTs(snapshot.timestampMs());

        PersistenceWriter writer = registry.getPersistenceWriter();
        if (writer != null) {
            writer.enqueueCpu(snapshot);
        }
    }

    private CpuSnapshot snapshotNow() {
        long nowMs = System.currentTimeMillis();
        int processors = Math.max(1, osBean.getAvailableProcessors());

        double processCpuLoad = -1.0;
        double systemCpuLoad = -1.0;
        long processCpuTimeMs = -1L;
        if (sunOsBean != null) {
            processCpuLoad = safeLoadPercent(sunOsBean.getProcessCpuLoad());
            systemCpuLoad = safeLoadPercent(sunOsBean.getCpuLoad());
            long processCpuTimeNs = sunOsBean.getProcessCpuTime();
            if (processCpuTimeNs >= 0L) {
                processCpuTimeMs = TimeUnit.NANOSECONDS.toMillis(processCpuTimeNs);
            }
        }

        long agentThreadCpuNs = sumProfilerThreadCpuNs();
        long wallNs = System.nanoTime();
        boolean agentThreadCpuSupported = agentThreadCpuNs >= 0L;
        long agentThreadCpuTimeMs = agentThreadCpuSupported
            ? TimeUnit.NANOSECONDS.toMillis(agentThreadCpuNs)
            : -1L;
        double agentThreadCpuLoad = agentThreadCpuSupported
            ? agentThreadLoadPercent(agentThreadCpuNs, wallNs, processors)
            : -1.0;

        lastAgentThreadCpuNs = agentThreadCpuNs;
        lastWallNs = wallNs;

        return new CpuSnapshot(
            nowMs,
            processCpuLoad,
            systemCpuLoad,
            processCpuTimeMs,
            agentThreadCpuTimeMs,
            agentThreadCpuLoad,
            processors,
            processCpuLoad >= 0.0 || processCpuTimeMs >= 0L,
            systemCpuLoad >= 0.0,
            agentThreadCpuSupported
        );
    }

    private double agentThreadLoadPercent(long currentAgentCpuNs,
                                          long currentWallNs,
                                          int processors) {
        if (lastAgentThreadCpuNs < 0L || lastWallNs < 0L) {
            return 0.0;
        }

        long deltaCpuNs = Math.max(0L, currentAgentCpuNs - lastAgentThreadCpuNs);
        long deltaWallNs = Math.max(1L, currentWallNs - lastWallNs);
        double percent = (double) deltaCpuNs / (deltaWallNs * processors) * 100.0;
        return roundPercent(Math.min(100.0, Math.max(0.0, percent)));
    }

    private long sumProfilerThreadCpuNs() {
        if (!threadCpuSupported()) {
            return -1L;
        }

        long[] ids = threadBean.getAllThreadIds();
        ThreadInfo[] infos = threadBean.getThreadInfo(ids, 0);
        long total = 0L;
        for (int i = 0; i < ids.length; i++) {
            ThreadInfo info = infos[i];
            if (info == null || !isProfilerThread(info.getThreadName())) {
                continue;
            }
            long cpuNs = threadBean.getThreadCpuTime(ids[i]);
            if (cpuNs >= 0L) {
                total += cpuNs;
            }
        }
        return total;
    }

    private static boolean isProfilerThread(String name) {
        return name != null && name.startsWith("profiler-");
    }

    private void enableThreadCpuTime() {
        try {
            if (threadBean.isThreadCpuTimeSupported()
                    && !threadBean.isThreadCpuTimeEnabled()) {
                threadBean.setThreadCpuTimeEnabled(true);
            }
        } catch (Throwable ignore) {
            // Optional JVM capability; unavailable counters degrade to -1.
        }
    }

    private boolean threadCpuSupported() {
        try {
            return threadBean.isThreadCpuTimeSupported()
                && threadBean.isThreadCpuTimeEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    private static double safeLoadPercent(double load) {
        if (Double.isNaN(load) || load < 0.0) {
            return -1.0;
        }
        return roundPercent(Math.min(100.0, load * 100.0));
    }

    private static double roundPercent(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
