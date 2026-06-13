package agent.persistence;

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
    private final int               retentionDays;

    public PersistenceDaemon(PersistenceWriter writer,
                             SqliteRepository repository,
                             int retentionDays) {
        this.writer        = writer;
        this.repository    = repository;
        this.retentionDays = retentionDays;
    }

    public void start() {
        ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "profiler-persistence-daemon");
                t.setDaemon(true);                  // must not block JVM shutdown
                t.setPriority(Thread.MIN_PRIORITY); // background work
                return t;
            });

        // Flush every 5 seconds — write buffered samples to SQLite.
        scheduler.scheduleAtFixedRate(
            this::flush,
            FLUSH_INTERVAL_SECONDS,
            FLUSH_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        );

        // Purge every 24 hours — and once immediately at startup so a restarted
        // agent trims anything that aged out while it was down.
        scheduler.scheduleAtFixedRate(
            this::purge,
            0,
            PURGE_INTERVAL_HOURS,
            TimeUnit.HOURS
        );

        log.info("PersistenceDaemon started — flush=" + FLUSH_INTERVAL_SECONDS
            + "s, retention=" + retentionDays + " days");
    }

    private void flush() {
        try {
            writer.flush();
        } catch (Exception e) {
            // Never let a flush failure kill the scheduled task.
            log.warning("Persistence flush failed: " + e.getMessage());
        }
    }

    private void purge() {
        try {
            repository.purgeOldRecords(retentionDays);
        } catch (Exception e) {
            log.warning("Auto-purge failed: " + e.getMessage());
        }
    }
}
