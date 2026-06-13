# Phase 3 — Persistence & History
## Full Implementation Guide

> **Goal by end of this phase:** Heap snapshots, GC events, and leak warnings
> are written to an embedded SQLite database every 5 seconds. After a JVM
> restart, historical data is queryable via new HTTP endpoints and visible
> in the dashboard's history view. Auto-purge keeps the database under control.
>
> **Estimated time:** 4–5 days
> **Branch:** `phase/3-persistence`
> **Prerequisite:** Phase 2 complete and merged to `develop`

---

## Before You Start

### What Is SQLite and Why Use It Here?

SQLite is a self-contained, serverless database engine that stores everything
in a single file. There is no separate database process, no network connection,
no configuration. You add a JAR dependency, point it at a file path, and it works.

This is exactly what we need: the developer should not need to install or run
anything extra to get persistence working. The profiler creates its own
`profiler.db` file in the working directory and manages it entirely.

### Write-Ahead Logging (WAL Mode)

SQLite's default journal mode locks the database file during writes, which blocks
reads. We enable WAL (Write-Ahead Logging) mode where writes go to a separate
WAL file first. This allows reads to happen concurrently with writes — important
because the HTTP API reads while the persistence daemon writes.

Enable WAL once at startup with: `PRAGMA journal_mode=WAL;`

### Why Async Batch Writes?

The persistence daemon wakes up every 5 seconds and writes a batch of samples
to SQLite. Writing a batch of 500 rows in one transaction takes about 5ms —
acceptable for a background task.

Writing every single sample individually and synchronously would serialize the
sampling thread on disk I/O. At 10ms sampling interval that means the sampler
blocks on disk every 10ms. This directly inflates the measurements.

The solution: samples accumulate in a `BlockingQueue`. The persistence daemon
drains the queue and writes in batches. The sampling thread only does a
non-blocking `offer()` — if the queue is full, the sample is silently dropped
and counted in self-metrics.

---

## Step 1 — Add SQLite Dependency

Add to `pom.xml` inside `<dependencies>`:

```xml
<!-- ── SQLite embedded database ────────────────────────────────────── -->
<!--
  xerial sqlite-jdbc bundles the native SQLite library for all major
  platforms (Linux x64, macOS arm64/x64, Windows) inside the JAR.
  No separate native install needed.
  Must be shaded to avoid conflicts if the target app also uses SQLite.
-->
<dependency>
  <groupId>org.xerial</groupId>
  <artifactId>sqlite-jdbc</artifactId>
  <version>3.45.3.0</version>
</dependency>
```

Add the relocation inside the shade plugin's `<relocations>` block:

```xml
<relocation>
  <pattern>org.sqlite</pattern>
  <shadedPattern>agent.shaded.sqlite</shadedPattern>
</relocation>
```

Rebuild and verify:

```bash
mvn package -DskipTests
jar -tf target/jvm-profiler-agent-1.0.0-SNAPSHOT.jar | grep "agent/shaded/sqlite" | head -3
```

You must see `agent/shaded/sqlite/` entries.

---

## Step 2 — SchemaInitializer

This class creates the database tables on startup. It is idempotent — safe to
run every time the agent starts, even if the tables already exist.

`src/main/java/agent/persistence/SchemaInitializer.java`

```java
package agent.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;

/**
 * Creates the SQLite schema on agent startup.
 *
 * <h2>Idempotency</h2>
 * All CREATE TABLE statements use "IF NOT EXISTS". This means running
 * SchemaInitializer multiple times (e.g. across restarts) is safe —
 * it only creates tables that do not already exist.
 *
 * <h2>WAL mode</h2>
 * We enable WAL (Write-Ahead Logging) immediately. This allows the HTTP
 * API thread to read data at the same time as the persistence daemon
 * writes data, without either blocking the other.
 */
public final class SchemaInitializer {

    private static final Logger log =
        Logger.getLogger(SchemaInitializer.class.getName());

    /**
     * Creates all tables and configures SQLite pragmas.
     *
     * @param connection an open JDBC connection to the SQLite database
     * @throws SQLException if schema creation fails — this is a fatal error
     */
    public void initialize(Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement()) {

            // ── Enable WAL mode ───────────────────────────────────────────
            // Must be the first statement — applies for the lifetime of the db file
            stmt.execute("PRAGMA journal_mode=WAL");

            // ── Performance pragmas ───────────────────────────────────────
            // synchronous=NORMAL: flush to disk periodically, not every write.
            // Slightly less durable than FULL but much faster, acceptable here.
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

            // Index on ts_ms is critical — all history queries filter by time range
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_heap_ts
                ON heap_samples(ts_ms)
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

            // ── leak_warnings table ───────────────────────────────────────
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

        }

        log.info("SQLite schema initialized — WAL mode enabled");
    }
}
```

---

## Step 3 — DatabaseConnectionPool

We use a single connection with connection reuse — not a full pool.
SQLite is an embedded database — multiple connections to the same file
from the same process is allowed but adds complexity without benefit here.

`src/main/java/agent/persistence/DatabaseConnectionPool.java`

```java
package agent.persistence;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.logging.Logger;

/**
 * Manages the SQLite JDBC connection.
 *
 * <h2>Single connection design</h2>
 * SQLite is an embedded database. We use a single connection shared between
 * the persistence daemon (writes) and the HTTP thread (reads). SQLite's WAL
 * mode allows concurrent reads and writes on the same connection without
 * blocking.
 *
 * The connection is kept open for the lifetime of the agent. SQLite handles
 * this efficiently — there is no overhead to holding an open connection.
 *
 * <h2>JDBC URL format</h2>
 * jdbc:sqlite:/path/to/file.db  → opens/creates the file at that path
 * jdbc:sqlite::memory:           → in-memory database (used in tests)
 */
public final class DatabaseConnectionPool {

    private static final Logger log =
        Logger.getLogger(DatabaseConnectionPool.class.getName());

    private final Connection connection;

    public DatabaseConnectionPool(String dbPath) throws SQLException {
        // Load the shaded SQLite driver explicitly
        // This is needed because the shade plugin relocated the driver class name
        try {
            Class.forName("agent.shaded.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite JDBC driver not found — shade relocation issue", e);
        }

        String jdbcUrl = "jdbc:sqlite:" + dbPath;
        this.connection = DriverManager.getConnection(jdbcUrl);

        // Disable auto-commit — we manage transactions manually for batch writes
        this.connection.setAutoCommit(false);

        log.info("SQLite connection opened: " + dbPath);
    }

    /** For testing — connect to an in-memory database */
    public static DatabaseConnectionPool inMemory() throws SQLException {
        try {
            Class.forName("agent.shaded.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite JDBC driver not found", e);
        }
        var pool = new DatabaseConnectionPool(null) {};
        return pool;
    }

    public Connection get() {
        return connection;
    }

    public void close() {
        try {
            if (!connection.isClosed()) {
                connection.commit();
                connection.close();
                log.info("SQLite connection closed");
            }
        } catch (SQLException e) {
            log.warning("Error closing SQLite connection: " + e.getMessage());
        }
    }
}
```

> **Note for junior devs:** The `inMemory()` factory method is a test hook.
> Real code calls `new DatabaseConnectionPool(path)`. Tests call
> `DatabaseConnectionPool.inMemory()`. The in-memory database is completely
> isolated — each test gets a fresh empty database with no leftover state.

---

## Step 4 — SqliteRepository

This class contains all SQL queries. One class, all persistence operations,
clearly named methods.

`src/main/java/agent/persistence/SqliteRepository.java`

```java
package agent.persistence;

import agent.model.GcEvent;
import agent.model.HeapSnapshot;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * All SQLite read and write operations for the profiler.
 *
 * <h2>Prepared statements</h2>
 * We use PreparedStatement for all queries. This prevents SQL injection
 * (even though our data is internal metrics, it is good practice) and
 * is more efficient than building SQL strings at runtime.
 *
 * <h2>Batch inserts</h2>
 * For write operations, we use addBatch() / executeBatch() to write
 * multiple rows in a single database transaction. This is 10–50x faster
 * than individual INSERTs because the database writes the entire batch
 * atomically in one disk flush.
 *
 * <h2>Query limits</h2>
 * All SELECT queries have a LIMIT clause to prevent out-of-memory responses
 * for very wide time ranges. The limit is 10,000 rows — enough for any
 * reasonable dashboard query.
 */
public final class SqliteRepository {

    private static final Logger log =
        Logger.getLogger(SqliteRepository.class.getName());

    private static final int MAX_QUERY_ROWS = 10_000;

    private final Connection   connection;
    private final String       instanceId;

    public SqliteRepository(Connection connection, String instanceId) {
        this.connection = connection;
        this.instanceId = instanceId;
    }

    // ── Write operations ──────────────────────────────────────────────────

    /**
     * Inserts a batch of heap snapshots in a single transaction.
     * Much faster than individual inserts.
     *
     * @param snapshots the batch to insert — may be empty (no-op)
     */
    public void batchInsertHeap(List<HeapSnapshot> snapshots) {
        if (snapshots.isEmpty()) return;

        String sql = """
            INSERT INTO heap_samples
              (instance_id, ts_ms, used_bytes, committed, max_bytes)
            VALUES (?, ?, ?, ?, ?)
            """;

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (HeapSnapshot s : snapshots) {
                ps.setString(1, instanceId);
                ps.setLong(2,   s.timestampMs());
                ps.setLong(3,   s.usedBytes());
                ps.setLong(4,   s.committedBytes());
                ps.setLong(5,   s.maxBytes());
                ps.addBatch();  // queue for batch execution
            }

            ps.executeBatch();   // write all rows in one go
            connection.commit(); // flush the transaction

            log.fine(() -> "Persisted " + snapshots.size() + " heap samples");

        } catch (SQLException e) {
            log.warning("Failed to persist heap samples: " + e.getMessage());
            rollback();
        }
    }

    /**
     * Inserts a batch of GC events in a single transaction.
     */
    public void batchInsertGc(List<GcEvent> events) {
        if (events.isEmpty()) return;

        String sql = """
            INSERT INTO gc_events
              (instance_id, ts_ms, gc_name, gc_cause, duration_ms, heap_before, heap_after)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (GcEvent e : events) {
                ps.setString(1, instanceId);
                ps.setLong(2,   e.timestampMs());
                ps.setString(3, e.gcName());
                ps.setString(4, e.gcCause());
                ps.setLong(5,   e.durationMs());
                ps.setLong(6,   e.heapBeforeBytes());
                ps.setLong(7,   e.heapAfterBytes());
                ps.addBatch();
            }

            ps.executeBatch();
            connection.commit();

        } catch (SQLException e) {
            log.warning("Failed to persist GC events: " + e.getMessage());
            rollback();
        }
    }

    // ── Read operations ───────────────────────────────────────────────────

    /**
     * Returns heap samples for this instance within a time range.
     *
     * @param fromMs start of range, epoch milliseconds (inclusive)
     * @param toMs   end of range, epoch milliseconds (inclusive)
     * @return list of HeapSnapshot records, ordered by timestamp ascending
     */
    public List<HeapSnapshot> queryHeap(long fromMs, long toMs) {
        String sql = """
            SELECT ts_ms, used_bytes, committed, max_bytes
            FROM heap_samples
            WHERE instance_id = ?
              AND ts_ms BETWEEN ? AND ?
            ORDER BY ts_ms ASC
            LIMIT ?
            """;

        List<HeapSnapshot> results = new ArrayList<>();

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, instanceId);
            ps.setLong(2,   fromMs);
            ps.setLong(3,   toMs);
            ps.setInt(4,    MAX_QUERY_ROWS);

            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                results.add(new HeapSnapshot(
                    rs.getLong("ts_ms"),
                    rs.getLong("used_bytes"),
                    rs.getLong("committed"),
                    rs.getLong("max_bytes"),
                    Map.of()    // pool usage not persisted in Phase 3 — kept simple
                ));
            }
        } catch (SQLException e) {
            log.warning("Failed to query heap history: " + e.getMessage());
        }

        return results;
    }

    /**
     * Returns GC events within a time range.
     */
    public List<GcEvent> queryGc(long fromMs, long toMs) {
        String sql = """
            SELECT ts_ms, gc_name, gc_cause, duration_ms, heap_before, heap_after
            FROM gc_events
            WHERE instance_id = ?
              AND ts_ms BETWEEN ? AND ?
            ORDER BY ts_ms ASC
            LIMIT ?
            """;

        List<GcEvent> results = new ArrayList<>();

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, instanceId);
            ps.setLong(2,   fromMs);
            ps.setLong(3,   toMs);
            ps.setInt(4,    MAX_QUERY_ROWS);

            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                results.add(new GcEvent(
                    rs.getLong("ts_ms"),
                    rs.getString("gc_name"),
                    rs.getString("gc_cause"),
                    rs.getLong("duration_ms"),
                    rs.getLong("heap_before"),
                    rs.getLong("heap_after")
                ));
            }
        } catch (SQLException e) {
            log.warning("Failed to query GC history: " + e.getMessage());
        }

        return results;
    }

    // ── Maintenance ───────────────────────────────────────────────────────

    /**
     * Deletes all records older than the retention period.
     * Call on startup and every 24 hours.
     *
     * @param retentionDays how many days of data to keep
     * @return total rows deleted across all tables
     */
    public int purgeOldRecords(int retentionDays) {
        long cutoffMs = System.currentTimeMillis()
            - ((long) retentionDays * 24 * 60 * 60 * 1000);

        int deleted = 0;

        String[] tables = { "heap_samples", "gc_events", "leak_warnings" };
        for (String table : tables) {
            String sql = "DELETE FROM " + table
                + " WHERE ts_ms < ? OR detected_at_ms < ?";

            // Use the appropriate column name per table
            String col = table.equals("leak_warnings") ? "detected_at_ms" : "ts_ms";
            String purge = "DELETE FROM " + table + " WHERE " + col + " < ?";

            try (PreparedStatement ps = connection.prepareStatement(purge)) {
                ps.setLong(1, cutoffMs);
                deleted += ps.executeUpdate();
            } catch (SQLException e) {
                log.warning("Failed to purge table " + table + ": " + e.getMessage());
            }
        }

        try { connection.commit(); } catch (SQLException e) { rollback(); }

        if (deleted > 0) {
            log.info("Auto-purge deleted " + deleted + " old records "
                + "(retention: " + retentionDays + " days)");
        }

        return deleted;
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private void rollback() {
        try { connection.rollback(); }
        catch (SQLException e) {
            log.warning("Rollback failed: " + e.getMessage());
        }
    }
}
```

---

## Step 5 — PersistenceWriter

The bridge between the in-memory ring buffers and the SQLite repository.

`src/main/java/agent/persistence/PersistenceWriter.java`

```java
package agent.persistence;

import agent.core.AgentSelfMetrics;
import agent.model.GcEvent;
import agent.model.HeapSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Logger;

/**
 * Buffers metric data and writes it to SQLite in async batches.
 *
 * <h2>Design</h2>
 * Two BlockingQueues act as intermediate buffers between the in-memory
 * ring buffers and SQLite. The sampling thread calls enqueue() — which
 * returns immediately (offer() is non-blocking). The persistence daemon
 * calls flush() every 5 seconds to drain the queues and write to SQLite.
 *
 * <h2>Queue capacity</h2>
 * At 10ms sampling interval = 100 samples/second.
 * The persistence daemon flushes every 5 seconds = 500 samples per flush.
 * We set queue capacity to 5000 — that is 10 flush cycles of buffer.
 * If SQLite write takes longer than 50 seconds to complete one batch,
 * the queue fills and samples are dropped. This is a deliberate trade-off:
 * disk I/O problems must never crash the agent or block the application.
 */
public final class PersistenceWriter {

    private static final Logger log =
        Logger.getLogger(PersistenceWriter.class.getName());

    private static final int QUEUE_CAPACITY = 5_000;

    private final BlockingQueue<HeapSnapshot> heapQueue;
    private final BlockingQueue<GcEvent>      gcQueue;
    private final SqliteRepository            repository;
    private final AgentSelfMetrics            selfMetrics;

    public PersistenceWriter(SqliteRepository repository,
                             AgentSelfMetrics selfMetrics) {
        this.heapQueue   = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
        this.gcQueue     = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
        this.repository  = repository;
        this.selfMetrics = selfMetrics;
    }

    // ── Enqueue methods (Tier 1 safe — non-blocking) ──────────────────────

    /**
     * Enqueues a heap snapshot for persistence.
     * Never blocks. If the queue is full, the snapshot is silently dropped
     * and the self-metrics counter is incremented.
     */
    public void enqueueHeap(HeapSnapshot snapshot) {
        if (!heapQueue.offer(snapshot)) {
            selfMetrics.incrementDroppedPersistence();
            // No logging — Tier 1
        }
    }

    /**
     * Enqueues a GC event for persistence.
     */
    public void enqueueGc(GcEvent event) {
        if (!gcQueue.offer(event)) {
            selfMetrics.incrementDroppedPersistence();
        }
    }

    // ── Flush (called by persistence daemon every 5 seconds) ─────────────

    /**
     * Drains both queues and writes to SQLite in batches.
     * Tier 2 — minimal logging allowed on anomalies.
     */
    public void flush() {
        // Drain heap queue
        List<HeapSnapshot> heapBatch = new ArrayList<>(500);
        heapQueue.drainTo(heapBatch, 1000);

        // Drain GC queue
        List<GcEvent> gcBatch = new ArrayList<>(100);
        gcQueue.drainTo(gcBatch, 500);

        // Update queue depth gauge in self-metrics
        selfMetrics.setPersistenceQueueDepth(
            heapQueue.size() + gcQueue.size());

        // Write to SQLite — may take a few milliseconds
        if (!heapBatch.isEmpty()) {
            repository.batchInsertHeap(heapBatch);
        }
        if (!gcBatch.isEmpty()) {
            repository.batchInsertGc(gcBatch);
        }

        // Log only if queues are getting dangerously full
        int depth = heapQueue.size() + gcQueue.size();
        if (depth > QUEUE_CAPACITY * 0.8) {
            log.warning("Persistence queue at " + depth + "/" + QUEUE_CAPACITY
                + " — SQLite writes may be falling behind");
        }
    }

    public int heapQueueSize() { return heapQueue.size(); }
    public int gcQueueSize()   { return gcQueue.size(); }
}
```

---

## Step 6 — PersistenceDaemon

`src/main/java/agent/persistence/PersistenceDaemon.java`

```java
package agent.persistence;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Scheduled daemon that drives the PersistenceWriter's flush cycle
 * and the auto-purge cycle.
 */
public final class PersistenceDaemon {

    private static final Logger log =
        Logger.getLogger(PersistenceDaemon.class.getName());

    private static final long FLUSH_INTERVAL_SECONDS  = 5L;
    private static final long PURGE_INTERVAL_HOURS    = 24L;

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
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });

        // Flush every 5 seconds — write buffered samples to SQLite
        scheduler.scheduleAtFixedRate(
            this::flush,
            FLUSH_INTERVAL_SECONDS,
            FLUSH_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        );

        // Purge every 24 hours — delete old records
        scheduler.scheduleAtFixedRate(
            this::purge,
            0,               // run once immediately on startup
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
```

---

## Step 7 — PersistenceConfig in AgentConfig

Add persistence configuration options to `AgentConfig.java`:

```java
// Add these fields to AgentConfig
private final boolean persistenceEnabled;
private final String  persistencePath;
private final int     persistenceRetentionDays;

// Add to the load() method — parse from props with defaults
boolean persistenceEnabled = Boolean.parseBoolean(
    props.getProperty("profiler.persistence.enabled", "true"));
String persistencePath = props.getProperty(
    "profiler.persistence.path", "./profiler-data/profiler.db");
int retentionDays = parseInt(props,
    "profiler.persistence.retention.days", 7);

// Add getters
public boolean isPersistenceEnabled()       { return persistenceEnabled; }
public String  getPersistencePath()         { return persistencePath; }
public int     getPersistenceRetentionDays(){ return persistenceRetentionDays; }
```

---

## Step 8 — Wire Persistence Into AgentMain

Update `AgentMain.premain()` to start the persistence subsystem:

```java
// After Phase 2 setup — add Phase 3 persistence
if (config.isPersistenceEnabled()) {
    try {
        // Ensure the directory exists
        java.nio.file.Path dbDir = java.nio.file.Path
            .of(config.getPersistencePath()).getParent();
        if (dbDir != null) {
            java.nio.file.Files.createDirectories(dbDir);
        }

        // Open database connection and initialize schema
        DatabaseConnectionPool pool =
            new DatabaseConnectionPool(config.getPersistencePath());
        new SchemaInitializer().initialize(pool.get());

        // Create repository and writer
        SqliteRepository repository =
            new SqliteRepository(pool.get(), config.getInstanceId());
        PersistenceWriter writer =
            new PersistenceWriter(repository, registry.selfMetrics());

        // Store writer on registry for AggregationDaemon to call
        registry.setPersistenceWriter(writer);

        // Start the persistence daemon
        new PersistenceDaemon(writer, repository,
            config.getPersistenceRetentionDays()).start();

        log.info("Persistence started — db=" + config.getPersistencePath());

    } catch (Exception e) {
        // Persistence failure must not crash the agent
        log.warning("Persistence setup failed — running without persistence: "
            + e.getMessage());
    }
}
```

Add `persistenceWriter` to `CollectorRegistry`:

```java
// In CollectorRegistry
private volatile PersistenceWriter persistenceWriter;

public void setPersistenceWriter(PersistenceWriter w) {
    this.persistenceWriter = w;
}
public PersistenceWriter getPersistenceWriter() {
    return persistenceWriter;
}
```

---

## Step 9 — Wire PersistenceWriter Into AggregationDaemon

Update `AggregationDaemon.aggregate()` to enqueue samples after each
aggregation cycle:

```java
private void aggregate() {
    try {
        // Existing aggregation logic ...
        List<EndpointStats> stats = endpointAggregator.aggregate();
        double totalRps = stats.stream()
            .mapToDouble(EndpointStats::currentRps).sum();
        registry.setCurrentRps(totalRps);
        registry.updateBeanRanking(beanMapper.getTopBeans());

        // ── NEW: Enqueue heap samples for persistence ─────────────────
        PersistenceWriter writer = registry.getPersistenceWriter();
        if (writer != null) {
            // Drain heap samples from the ring buffer and enqueue for persistence
            List<HeapSnapshot> heapBatch = new ArrayList<>();
            registry.heapBuffer().drainTo(heapBatch);
            heapBatch.forEach(writer::enqueueHeap);

            // Drain GC events and enqueue for persistence
            List<GcEvent> gcBatch = new ArrayList<>();
            registry.gcBuffer().drainTo(gcBatch);
            gcBatch.forEach(writer::enqueueGc);
        }

    } catch (Exception e) {
        log.warning("Aggregation cycle failed: " + e.getMessage());
    }
}
```

> **Important note:** After draining the ring buffers for persistence, the
> HTTP API's `/profiler/heap` and `/profiler/gc` routes must be updated to
> serve data from a secondary snapshot buffer or from persistence, not from
> the now-empty ring buffers. The cleanest solution: keep a separate
> `latestHeapSnapshot` volatile field on the registry that the HeapSampler
> updates on every tick, and use that for the live API.

Update `CollectorRegistry` to add a latest-snapshot cache:

```java
private volatile HeapSnapshot latestHeapSnapshot;

public void setLatestHeapSnapshot(HeapSnapshot s) {
    this.latestHeapSnapshot = s;
}
public HeapSnapshot getLatestHeapSnapshot() {
    return latestHeapSnapshot;
}
```

Update `HeapSampler.sample()` to also update the cache:

```java
// At the end of sample()
registry.setLatestHeapSnapshot(snapshot); // add this field to HeapSampler
```

Update the `/profiler/heap` route to use the cache for the `current` field
and the ring buffer snapshot for the time-series (ring buffer is read before
drain, so snapshot() still works for the live view).

---

## Step 10 — History HTTP Routes

Add these routes inside `registerRoutes()` in `ProfilerHttpServer.java`:

```java
// ── GET /profiler/history/heap ────────────────────────────────────────
app.get("/profiler/history/heap", ctx -> {
    SqliteRepository repo = registry.getSqliteRepository();
    if (repo == null) {
        ctx.status(503).result("{\"error\":\"Persistence not enabled\"}");
        return;
    }

    // Parse query parameters — both required
    String fromStr = ctx.queryParam("from");
    String toStr   = ctx.queryParam("to");

    if (fromStr == null || toStr == null) {
        ctx.status(400).json(Map.of(
            "error", "Both 'from' and 'to' query parameters are required",
            "example", "/profiler/history/heap?from=1748000000000&to=1748003600000"
        ));
        return;
    }

    try {
        long fromMs = Long.parseLong(fromStr);
        long toMs   = Long.parseLong(toStr);

        if (toMs <= fromMs) {
            ctx.status(400).json(Map.of("error", "'to' must be greater than 'from'"));
            return;
        }

        List<HeapSnapshot> samples = repo.queryHeap(fromMs, toMs);
        ctx.json(Map.of(
            "fromMs",      fromMs,
            "toMs",        toMs,
            "sampleCount", samples.size(),
            "samples",     samples
        ));

    } catch (NumberFormatException e) {
        ctx.status(400).json(Map.of("error",
            "'from' and 'to' must be epoch milliseconds (long integers)"));
    }
});

// ── GET /profiler/history/gc ──────────────────────────────────────────
app.get("/profiler/history/gc", ctx -> {
    SqliteRepository repo = registry.getSqliteRepository();
    if (repo == null) {
        ctx.status(503).result("{\"error\":\"Persistence not enabled\"}");
        return;
    }

    String fromStr = ctx.queryParam("from");
    String toStr   = ctx.queryParam("to");

    if (fromStr == null || toStr == null) {
        ctx.status(400).json(Map.of("error",
            "Both 'from' and 'to' query parameters are required"));
        return;
    }

    try {
        long fromMs = Long.parseLong(fromStr);
        long toMs   = Long.parseLong(toStr);
        List<GcEvent> events = repo.queryGc(fromMs, toMs);
        ctx.json(Map.of("fromMs", fromMs, "toMs", toMs,
            "eventCount", events.size(), "events", events));
    } catch (NumberFormatException e) {
        ctx.status(400).json(Map.of("error", "Invalid time parameters"));
    }
});
```

Also add `SqliteRepository` to `CollectorRegistry`:

```java
private volatile SqliteRepository sqliteRepository;
public void setSqliteRepository(SqliteRepository r) { this.sqliteRepository = r; }
public SqliteRepository getSqliteRepository()       { return sqliteRepository; }
```

And set it in `AgentMain` after creating the repository:
```java
registry.setSqliteRepository(repository);
```

---

## Step 11 — Build and Test

```bash
mvn package -DskipTests

# Verify SQLite shade
jar -tf target/jvm-profiler-agent-1.0.0-SNAPSHOT.jar | grep "agent/shaded/sqlite" | head -3
```

Start the demo app with the agent:

```bash
java \
  -javaagent:target/jvm-profiler-agent-1.0.0-SNAPSHOT.jar \
  -jar demo-app/target/jvm-profiler-demo-app-1.0.0-SNAPSHOT.jar
```

Check the logs:
```
INFO agent.persistence.SchemaInitializer: SQLite schema initialized — WAL mode enabled
INFO agent.persistence.PersistenceDaemon: PersistenceDaemon started — flush=5s, retention=7 days
```

Wait 10 seconds for a few flush cycles, then query history:

```bash
# Get current time in epoch ms
NOW=$(date +%s000)
TEN_MINUTES_AGO=$((NOW - 600000))

curl -s "http://localhost:7070/profiler/history/heap?from=${TEN_MINUTES_AGO}&to=${NOW}" \
  | python3 -m json.tool
```

**Restart test:**
1. Run the app with the agent for 30 seconds
2. Kill the app (`Ctrl+C`)
3. Check the SQLite file exists: `ls -la profiler-data/profiler.db`
4. Restart the app
5. Query history with a `from` time from before the restart
6. You should see samples from the previous session

---

## Step 12 — Unit Tests

### 12.1 SqliteRepository Tests

`src/test/java/agent/persistence/SqliteRepositoryTest.java`

```java
package agent.persistence;

import agent.model.GcEvent;
import agent.model.HeapSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SqliteRepositoryTest {

    private Connection connection;
    private SqliteRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        // Each test gets a fresh in-memory database
        Class.forName("agent.shaded.sqlite.JDBC");
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        connection.setAutoCommit(false);

        // Initialize schema
        new SchemaInitializer().initialize(connection);

        repo = new SqliteRepository(connection, "test-instance");
    }

    @Test
    void insertsAndQueriesHeapSamples() {
        long now = System.currentTimeMillis();

        List<HeapSnapshot> samples = List.of(
            new HeapSnapshot(now - 2000, 100L, 200L, 500L, Map.of()),
            new HeapSnapshot(now - 1000, 120L, 200L, 500L, Map.of()),
            new HeapSnapshot(now,        130L, 200L, 500L, Map.of())
        );

        repo.batchInsertHeap(samples);

        List<HeapSnapshot> result = repo.queryHeap(now - 3000, now + 1000);
        assertEquals(3, result.size());
        assertEquals(100L, result.get(0).usedBytes());
        assertEquals(130L, result.get(2).usedBytes());
    }

    @Test
    void timeRangeFilterWorks() {
        long now = System.currentTimeMillis();

        repo.batchInsertHeap(List.of(
            new HeapSnapshot(now - 10000, 50L, 100L, 500L, Map.of()),
            new HeapSnapshot(now,         80L, 100L, 500L, Map.of())
        ));

        // Query only the last 5 seconds — should return only the recent sample
        List<HeapSnapshot> result = repo.queryHeap(now - 5000, now + 1000);
        assertEquals(1, result.size());
        assertEquals(80L, result.get(0).usedBytes());
    }

    @Test
    void insertsAndQueriesGcEvents() {
        long now = System.currentTimeMillis();

        repo.batchInsertGc(List.of(
            new GcEvent(now, "G1 Young Generation", "G1 Evacuation Pause",
                50L, 100_000_000L, 80_000_000L)
        ));

        List<GcEvent> result = repo.queryGc(now - 1000, now + 1000);
        assertEquals(1, result.size());
        assertEquals("G1 Young Generation", result.get(0).gcName());
        assertEquals(50L, result.get(0).durationMs());
    }

    @Test
    void purgeDeletesOldRecords() {
        long now = System.currentTimeMillis();
        long eightDaysAgo = now - (8L * 24 * 60 * 60 * 1000);

        // Insert one old sample (8 days ago) and one recent sample
        repo.batchInsertHeap(List.of(
            new HeapSnapshot(eightDaysAgo, 100L, 200L, 500L, Map.of()),
            new HeapSnapshot(now,          100L, 200L, 500L, Map.of())
        ));

        // Purge with 7-day retention — should delete the 8-day-old record
        int deleted = repo.purgeOldRecords(7);
        assertEquals(1, deleted);

        // Only the recent sample should remain
        List<HeapSnapshot> remaining =
            repo.queryHeap(eightDaysAgo, now + 1000);
        assertEquals(1, remaining.size());
    }

    @Test
    void emptyBatchIsNoOp() {
        // Should not throw
        assertDoesNotThrow(() -> repo.batchInsertHeap(List.of()));
        assertDoesNotThrow(() -> repo.batchInsertGc(List.of()));
    }
}
```

### 12.2 PersistenceWriter Tests

```java
package agent.persistence;

import agent.core.AgentSelfMetrics;
import agent.model.HeapSnapshot;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PersistenceWriterTest {

    @Test
    void enqueueIsNonBlockingWhenQueueFull() {
        SqliteRepository mockRepo = Mockito.mock(SqliteRepository.class);
        AgentSelfMetrics metrics  = new AgentSelfMetrics();

        // Use a tiny queue for testing — capacity 2
        // We cannot set capacity via constructor in this impl,
        // so this test verifies behavior via reflection or a test subclass.
        // For simplicity, verify the counter increments on overflow.

        PersistenceWriter writer = new PersistenceWriter(mockRepo, metrics);

        // Flood the writer with 6000 samples to force overflow
        HeapSnapshot sample = new HeapSnapshot(
            System.currentTimeMillis(), 100L, 200L, 500L, Map.of());
        for (int i = 0; i < 6000; i++) {
            writer.enqueueHeap(sample);
        }

        // Some should have been dropped
        assertTrue(metrics.snapshot("x", 10).droppedPersistenceSamples() > 0,
            "Overflow should have incremented droppedPersistenceSamples");
    }

    @Test
    void flushCallsBatchInsert() {
        SqliteRepository mockRepo = Mockito.mock(SqliteRepository.class);
        AgentSelfMetrics metrics  = new AgentSelfMetrics();
        PersistenceWriter writer  = new PersistenceWriter(mockRepo, metrics);

        HeapSnapshot sample = new HeapSnapshot(
            System.currentTimeMillis(), 100L, 200L, 500L, Map.of());
        writer.enqueueHeap(sample);
        writer.flush();

        // batchInsertHeap should have been called with 1 sample
        verify(mockRepo, times(1)).batchInsertHeap(argThat(
            list -> list.size() == 1));
    }
}
```

### 12.3 Run All Tests

```bash
mvn test
```

---

## Step 13 — Phase 3 Checklist

- [ ] `jar -tf agent.jar | grep agent/shaded/sqlite` shows entries
- [ ] `profiler-data/profiler.db` file created when agent starts
- [ ] Log shows `SQLite schema initialized — WAL mode enabled`
- [ ] After 10s: `GET /profiler/history/heap?from=X&to=Y` returns data
- [ ] After JVM restart: history query returns data from previous session
- [ ] Auto-purge log line appears at startup
- [ ] Queue-full test: enqueue 6000 samples, verify `droppedPersistenceSamples > 0`
- [ ] All unit tests pass

```bash
git checkout develop
git merge --no-ff phase/3-persistence -m "Merge Phase 3: Persistence & History"
git tag phase-3-complete
git push origin develop --tags
git checkout -b phase/4-adaptive-alerting
```

---

## Troubleshooting Phase 3

**Problem:** `ClassNotFoundException: agent.shaded.sqlite.JDBC`
**Cause:** SQLite JDBC driver not shaded correctly.
**Fix:** Verify `<relocation>` for `org.sqlite → agent.shaded.sqlite` in pom.xml.

---

**Problem:** `SQLITE_BUSY` error in logs
**Cause:** Two threads are accessing SQLite simultaneously without WAL mode.
**Fix:** Ensure `PRAGMA journal_mode=WAL` is the first statement in `SchemaInitializer`.

---

**Problem:** History query returns empty after restart
**Cause:** Data was written to queue but flush did not complete before JVM exit.
**Fix:** Add a JVM shutdown hook that calls `writer.flush()` before exit. Register
it in `AgentMain` after setting up persistence.

```java
Runtime.getRuntime().addShutdownHook(new Thread(() -> {
    log.info("Agent shutdown — flushing persistence...");
    if (registry.getPersistenceWriter() != null) {
        registry.getPersistenceWriter().flush();
    }
}, "profiler-shutdown"));
```

---

*End of Phase 3.*
*Next: [Phase 4 — Adaptive Sampling & Alerting](./PHASE-4-ADAPTIVE-ALERTING.md)*
