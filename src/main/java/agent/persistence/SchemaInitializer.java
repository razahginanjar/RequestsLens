package agent.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;

/**
 * Creates the SQLite schema on agent startup.
 *
 * <h2>Idempotency</h2>
 * All CREATE TABLE / CREATE INDEX statements use "IF NOT EXISTS". Running
 * SchemaInitializer multiple times (e.g. across restarts) is therefore safe —
 * it only creates objects that do not already exist.
 *
 * <h2>WAL mode</h2>
 * We enable WAL (Write-Ahead Logging) so the HTTP API thread can read while
 * the persistence daemon writes, without either blocking the other.
 *
 * <h2>Why we toggle auto-commit around the pragmas</h2>
 * SQLite refuses {@code PRAGMA journal_mode=WAL} when it runs inside an open
 * transaction ("cannot change into WAL mode from within a transaction"). The
 * DatabaseConnectionPool opens its connection with auto-commit OFF (needed for
 * batched writes), and the JDBC driver starts a transaction on the first
 * statement. So we briefly restore auto-commit while applying the pragmas and
 * DDL, then put the connection back exactly as we found it. This makes the
 * initializer robust regardless of the connection's incoming auto-commit state.
 */
public final class SchemaInitializer {

    private static final Logger log =
        Logger.getLogger(SchemaInitializer.class.getName());

    /**
     * Creates all tables/indexes and configures SQLite pragmas.
     *
     * @param connection an open JDBC connection to the SQLite database
     * @throws SQLException if schema creation fails — this is a fatal error
     */
    public void initialize(Connection connection) throws SQLException {
        // Remember the caller's auto-commit setting so we can restore it.
        final boolean previousAutoCommit = connection.getAutoCommit();

        // Pragmas + DDL run with auto-commit ON so journal_mode=WAL is not
        // executed inside a transaction (see class javadoc).
        connection.setAutoCommit(true);

        try (Statement stmt = connection.createStatement()) {

            // ── Enable WAL mode ───────────────────────────────────────────
            // Applies for the lifetime of the database file. On an in-memory
            // database SQLite simply reports "memory" and ignores this — no error.
            stmt.execute("PRAGMA journal_mode=WAL");

            // ── Performance pragma ────────────────────────────────────────
            // synchronous=NORMAL flushes to disk periodically rather than on
            // every write. Slightly less durable than FULL but much faster,
            // which is acceptable for best-effort profiling history.
            stmt.execute("PRAGMA synchronous=NORMAL");

            // ── heap_samples table ────────────────────────────────────────
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS heap_samples (
                    id           INTEGER PRIMARY KEY AUTOINCREMENT,
                    instance_id  TEXT    NOT NULL,
                    ts_ms        INTEGER NOT NULL,
                    used_bytes   INTEGER NOT NULL,
                    committed    INTEGER NOT NULL,
                    max_bytes    INTEGER NOT NULL
                )
                """);

            // Index on ts_ms is critical — every history query filters by time range.
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_heap_ts
                ON heap_samples(ts_ms)
                """);

            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_heap_instance_ts
                ON heap_samples(instance_id, ts_ms)
                """);

            // ── gc_events table ───────────────────────────────────────────
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS gc_events (
                    id           INTEGER PRIMARY KEY AUTOINCREMENT,
                    instance_id  TEXT    NOT NULL,
                    ts_ms        INTEGER NOT NULL,
                    gc_name      TEXT    NOT NULL,
                    gc_cause     TEXT    NOT NULL,
                    duration_ms  INTEGER NOT NULL,
                    heap_before  INTEGER NOT NULL,
                    heap_after   INTEGER NOT NULL
                )
                """);

            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_gc_ts
                ON gc_events(ts_ms)
                """);

            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_gc_instance_ts
                ON gc_events(instance_id, ts_ms)
                """);

            // -- cpu_samples table ------------------------------------------------
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS cpu_samples (
                    id                              INTEGER PRIMARY KEY AUTOINCREMENT,
                    instance_id                     TEXT    NOT NULL,
                    ts_ms                           INTEGER NOT NULL,
                    process_cpu_load_percent        REAL    NOT NULL,
                    system_cpu_load_percent         REAL    NOT NULL,
                    process_cpu_time_ms             INTEGER NOT NULL,
                    agent_thread_cpu_time_ms        INTEGER NOT NULL,
                    agent_thread_cpu_load_percent   REAL    NOT NULL,
                    available_processors            INTEGER NOT NULL,
                    process_cpu_supported           INTEGER NOT NULL,
                    system_cpu_supported            INTEGER NOT NULL,
                    agent_thread_cpu_supported      INTEGER NOT NULL
                )
                """);

            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_cpu_ts
                ON cpu_samples(ts_ms)
                """);

            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_cpu_instance_ts
                ON cpu_samples(instance_id, ts_ms)
                """);

            // ── leak_warnings table ───────────────────────────────────────
            // Populated in Phase 4 (alerting); the table is created now so the
            // schema is stable and purgeOldRecords() has a table to clean.
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS leak_warnings (
                    id              INTEGER PRIMARY KEY AUTOINCREMENT,
                    instance_id     TEXT    NOT NULL,
                    detected_at_ms  INTEGER NOT NULL,
                    growth_bytes    INTEGER NOT NULL,
                    growth_percent  REAL    NOT NULL,
                    severity        TEXT    NOT NULL
                )
                """);

        } finally {
            // Restore the caller's transaction mode (the write path expects
            // auto-commit OFF). Done in finally so an exception mid-DDL still
            // leaves the connection in its original state.
            connection.setAutoCommit(previousAutoCommit);
        }

        log.info("SQLite schema initialized — WAL mode enabled");
    }
}
