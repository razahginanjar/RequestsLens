package agent.persistence;

import agent.model.GcEvent;
import agent.model.HeapSnapshot;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * All SQLite read, write and maintenance operations for the profiler.
 *
 * <h2>Prepared statements</h2>
 * Every query uses a {@link PreparedStatement}. This prevents SQL injection
 * (good practice even for internal metrics) and lets the driver reuse query
 * plans.
 *
 * <h2>Batch inserts</h2>
 * Writes use addBatch()/executeBatch() inside a single transaction. Writing a
 * batch of rows in one transaction is far faster than individual INSERTs
 * because the database performs one disk flush for the whole batch.
 *
 * <h2>Query limits</h2>
 * Every SELECT has a LIMIT to bound the response size for very wide time
 * ranges. {@link #MAX_QUERY_ROWS} rows is enough for any reasonable dashboard
 * query while protecting the agent from an accidental out-of-memory response.
 *
 * <h2>Threading</h2>
 * The connection is shared (single-connection design). Writes happen on the
 * persistence daemon thread; reads happen on the HTTP thread. WAL mode makes
 * that safe. This class does no locking of its own.
 */
public final class SqliteRepository {

    private static final Logger log =
        Logger.getLogger(SqliteRepository.class.getName());

    public static final int MAX_QUERY_ROWS = 10_000;

    private final Connection connection;
    private final String     instanceId;

    public SqliteRepository(Connection connection, String instanceId) {
        this.connection = connection;
        this.instanceId = instanceId;
    }

    // ── Write operations ──────────────────────────────────────────────────

    /**
     * Inserts a batch of heap snapshots in a single transaction.
     *
     * @param snapshots the batch to insert — may be empty (no-op)
     */
    public int batchInsertHeap(List<HeapSnapshot> snapshots) {
        if (snapshots.isEmpty()) return 0;

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
                ps.addBatch();           // queue this row
            }

            ps.executeBatch();           // write all rows in one round-trip
            connection.commit();         // flush the transaction to disk

            log.fine(() -> "Persisted " + snapshots.size() + " heap samples");
            return snapshots.size();

        } catch (SQLException e) {
            log.warning("Failed to persist heap samples: " + e.getMessage());
            rollback();
            throw new PersistenceException("Failed to persist heap samples", e);
        }
    }

    /**
     * Inserts a batch of GC events in a single transaction.
     *
     * @param events the batch to insert — may be empty (no-op)
     */
    public int batchInsertGc(List<GcEvent> events) {
        if (events.isEmpty()) return 0;

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

            log.fine(() -> "Persisted " + events.size() + " GC events");
            return events.size();

        } catch (SQLException e) {
            log.warning("Failed to persist GC events: " + e.getMessage());
            rollback();
            throw new PersistenceException("Failed to persist GC events", e);
        }
    }

    // ── Read operations ───────────────────────────────────────────────────

    /**
     * Returns heap samples for this instance within an inclusive time range,
     * ordered oldest-first.
     *
     * @param fromMs start of range, epoch milliseconds (inclusive)
     * @param toMs   end of range, epoch milliseconds (inclusive)
     */
    public List<HeapSnapshot> queryHeap(long fromMs, long toMs) {
        return queryHeapResult(fromMs, toMs).rows();
    }

    /**
     * Returns heap samples plus response-limit metadata for HTTP history APIs.
     */
    public HistoryQueryResult<HeapSnapshot> queryHeapResult(long fromMs, long toMs) {
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
            ps.setInt(4,    MAX_QUERY_ROWS + 1);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new HeapSnapshot(
                        rs.getLong("ts_ms"),
                        rs.getLong("used_bytes"),
                        rs.getLong("committed"),
                        rs.getLong("max_bytes"),
                        // Per-pool breakdown is not persisted in Phase 3 (kept
                        // simple); an empty map keeps the record valid.
                        Map.of()
                    ));
                }
            }
        } catch (SQLException e) {
            log.warning("Failed to query heap history: " + e.getMessage());
            throw new PersistenceException("Failed to query heap history", e);
        }

        boolean limited = results.size() > MAX_QUERY_ROWS;
        if (limited) {
            results = new ArrayList<>(results.subList(0, MAX_QUERY_ROWS));
        }
        return new HistoryQueryResult<>(results, limited, MAX_QUERY_ROWS);
    }

    /**
     * Returns GC events for this instance within an inclusive time range,
     * ordered oldest-first.
     */
    public List<GcEvent> queryGc(long fromMs, long toMs) {
        return queryGcResult(fromMs, toMs).rows();
    }

    /**
     * Returns GC events plus response-limit metadata for HTTP history APIs.
     */
    public HistoryQueryResult<GcEvent> queryGcResult(long fromMs, long toMs) {
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
            ps.setInt(4,    MAX_QUERY_ROWS + 1);

            try (ResultSet rs = ps.executeQuery()) {
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
            }
        } catch (SQLException e) {
            log.warning("Failed to query GC history: " + e.getMessage());
            throw new PersistenceException("Failed to query GC history", e);
        }

        boolean limited = results.size() > MAX_QUERY_ROWS;
        if (limited) {
            results = new ArrayList<>(results.subList(0, MAX_QUERY_ROWS));
        }
        return new HistoryQueryResult<>(results, limited, MAX_QUERY_ROWS);
    }

    // ── Maintenance ───────────────────────────────────────────────────────

    /**
     * Deletes all records older than the retention period across every table.
     * Called once on startup and then every 24 hours by the PersistenceDaemon.
     *
     * <p>Each table is keyed on its own timestamp column: the metric tables use
     * {@code ts_ms}, while {@code leak_warnings} uses {@code detected_at_ms}.
     *
     * @param retentionDays how many days of data to keep
     * @return total rows deleted across all tables
     */
    public int purgeOldRecords(int retentionDays) {
        long cutoffMs = System.currentTimeMillis()
            - ((long) retentionDays * 24 * 60 * 60 * 1000);

        // Preserve a stable order; map each table to its timestamp column.
        Map<String, String> tableToTsColumn = new LinkedHashMap<>();
        tableToTsColumn.put("heap_samples",  "ts_ms");
        tableToTsColumn.put("gc_events",     "ts_ms");
        tableToTsColumn.put("leak_warnings", "detected_at_ms");

        int deleted = 0;
        for (Map.Entry<String, String> entry : tableToTsColumn.entrySet()) {
            String table = entry.getKey();
            String tsCol = entry.getValue();
            String purge = "DELETE FROM " + table + " WHERE " + tsCol + " < ?";

            try (PreparedStatement ps = connection.prepareStatement(purge)) {
                ps.setLong(1, cutoffMs);
                deleted += ps.executeUpdate();
            } catch (SQLException e) {
                log.warning("Failed to purge table " + table + ": " + e.getMessage());
                rollback();
                throw new PersistenceException("Failed to purge table " + table, e);
            }
        }

        try {
            connection.commit();
        } catch (SQLException e) {
            rollback();
            throw new PersistenceException("Failed to commit auto-purge", e);
        }

        if (deleted > 0) {
            log.info("Auto-purge deleted " + deleted + " old records "
                + "(retention: " + retentionDays + " days)");
        }

        return deleted;
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    private void rollback() {
        try {
            connection.rollback();
        } catch (SQLException e) {
            log.warning("Rollback failed: " + e.getMessage());
        }
    }
}
