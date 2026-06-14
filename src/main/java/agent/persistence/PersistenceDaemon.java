package agent.persistence;

import agent.core.AgentSelfMetrics;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Scheduled daemon that drives the {@link PersistenceWriter} flush cycle and
 * the {@link SqliteRepository} auto-purge cycle.
 *
 * <p>Both tasks run on a single low-priority daemon thread so they never keep
 * the JVM alive on shutdown and never steal CPU from the target application.
 */
public final class PersistenceDaemon {

    private static final Logger log =
        Logger.getLogger(PersistenceDaemon.class.getName());

    private static final long FLUSH_INTERVAL_SECONDS = 5L;
    private static final long PURGE_INTERVAL_HOURS   = 24L;

    private final PersistenceWriter writer;
    private final SqliteRepository  repository;
    private final AgentSelfMetrics  selfMetrics;
    private final int               retentionDays;

    public PersistenceDaemon(PersistenceWriter writer,
                             SqliteRepository repository,
                             AgentSelfMetrics selfMetrics,
                             int retentionDays) {
        this.writer        = writer;
        this.repository    = repository;
        this.selfMetrics   = selfMetrics;
        this.retentionDays = retentionDays;
    }

    public void start() {
        ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "profiler-persistence-daemon");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });

        scheduler.scheduleAtFixedRate(
            this::flush,
            FLUSH_INTERVAL_SECONDS,
            FLUSH_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        );

        scheduler.scheduleAtFixedRate(
            this::purge,
            0,
            PURGE_INTERVAL_HOURS,
            TimeUnit.HOURS
        );

        log.info("PersistenceDaemon started; flush=" + FLUSH_INTERVAL_SECONDS
            + "s, retention=" + retentionDays + " days");
    }

    private void flush() {
        try {
            writer.flush();
        } catch (Exception e) {
            // The writer records the failure before throwing.
            log.warning("Persistence flush failed: " + e.getMessage());
        }
    }

    private void purge() {
        try {
            int deleted = repository.purgeOldRecords(retentionDays);
            selfMetrics.recordPersistencePurge(System.currentTimeMillis(), deleted);
        } catch (Exception e) {
            selfMetrics.incrementPersistencePurgeFailures();
            log.warning("Auto-purge failed: " + e.getMessage());
        }
    }
}
