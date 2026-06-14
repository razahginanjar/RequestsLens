package agent.persistence;

import agent.model.GcEvent;
import agent.model.HeapSnapshot;
import agent.model.CpuSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SqliteRepository} (Phase 3 spec, Step 12.1).
 *
 * <p>Each test gets a fresh, isolated in-memory SQLite database so there is no
 * leftover state between tests.
 *
 * <p><b>Driver name:</b> tests run against the ORIGINAL (unshaded)
 * {@code org.sqlite.JDBC} class. Shade relocation to
 * {@code agent.shaded.sqlite.JDBC} only happens in the package phase, which
 * runs after tests. (JDBC 4 would auto-register the driver via ServiceLoader,
 * but we keep the explicit Class.forName as documentation of the dependency.)
 */
class SqliteRepositoryTest {

    private Connection connection;
    private SqliteRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        Class.forName("org.sqlite.JDBC");
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        connection.setAutoCommit(false);

        // Initialize schema (also flips auto-commit for the WAL pragma, then
        // restores it to false — see SchemaInitializer).
        new SchemaInitializer().initialize(connection);

        repo = new SqliteRepository(connection, "test-instance");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }

    @Test
    void insertsAndQueriesHeapSamples() {
        long now = System.currentTimeMillis();

        List<HeapSnapshot> samples = List.of(
            new HeapSnapshot(now - 2000, 100L, 200L, 500L, Map.of()),
            new HeapSnapshot(now - 1000, 120L, 200L, 500L, Map.of()),
            new HeapSnapshot(now,        130L, 200L, 500L, Map.of())
        );

        assertEquals(3, repo.batchInsertHeap(samples));

        List<HeapSnapshot> result = repo.queryHeap(now - 3000, now + 1000);
        assertEquals(3, result.size());
        // Ordered oldest-first.
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

        // Query only the last 5 seconds — should return only the recent sample.
        List<HeapSnapshot> result = repo.queryHeap(now - 5000, now + 1000);
        assertEquals(1, result.size());
        assertEquals(80L, result.get(0).usedBytes());
    }

    @Test
    void insertsAndQueriesGcEvents() {
        long now = System.currentTimeMillis();

        assertEquals(1, repo.batchInsertGc(List.of(
            new GcEvent(now, "G1 Young Generation", "G1 Evacuation Pause",
                50L, 100_000_000L, 80_000_000L)
        )));

        List<GcEvent> result = repo.queryGc(now - 1000, now + 1000);
        assertEquals(1, result.size());
        assertEquals("G1 Young Generation", result.get(0).gcName());
        assertEquals(50L, result.get(0).durationMs());
    }

    @Test
    void insertsAndQueriesCpuSamples() {
        long now = System.currentTimeMillis();

        assertEquals(2, repo.batchInsertCpu(List.of(
            new CpuSnapshot(now - 1000, 12.5, 40.0, 1000L,
                10L, 0.1, 8, true, true, true),
            new CpuSnapshot(now, 18.25, 55.5, 1200L,
                12L, 0.2, 8, true, true, true)
        )));

        List<CpuSnapshot> result = repo.queryCpu(now - 2000, now + 1000);
        assertEquals(2, result.size());
        assertEquals(12.5, result.get(0).processCpuLoadPercent(), 0.01);
        assertEquals(55.5, result.get(1).systemCpuLoadPercent(), 0.01);
        assertTrue(result.get(1).agentThreadCpuSupported());
    }

    @Test
    void purgeDeletesOldRecords() {
        long now = System.currentTimeMillis();
        long eightDaysAgo = now - (8L * 24 * 60 * 60 * 1000);

        // One record from 8 days ago, one from now.
        repo.batchInsertHeap(List.of(
            new HeapSnapshot(eightDaysAgo, 100L, 200L, 500L, Map.of()),
            new HeapSnapshot(now,          100L, 200L, 500L, Map.of())
        ));

        // 7-day retention should delete the 8-day-old record only.
        int deleted = repo.purgeOldRecords(7);
        assertEquals(1, deleted);

        List<HeapSnapshot> remaining = repo.queryHeap(eightDaysAgo, now + 1000);
        assertEquals(1, remaining.size());
    }

    @Test
    void emptyBatchIsNoOp() {
        assertEquals(0, repo.batchInsertHeap(List.of()));
        assertEquals(0, repo.batchInsertGc(List.of()));
        assertEquals(0, repo.batchInsertCpu(List.of()));
    }

    @Test
    void queryHeapResultReportsWhenRowsAreLimited() {
        long now = System.currentTimeMillis();
        List<HeapSnapshot> samples = new ArrayList<>();
        for (int i = 0; i < SqliteRepository.MAX_QUERY_ROWS + 1; i++) {
            samples.add(new HeapSnapshot(now + i, i, 200L, 500L, Map.of()));
        }

        repo.batchInsertHeap(samples);

        HistoryQueryResult<HeapSnapshot> result =
            repo.queryHeapResult(now - 1, now + SqliteRepository.MAX_QUERY_ROWS + 1L);
        assertEquals(SqliteRepository.MAX_QUERY_ROWS, result.rows().size());
        assertTrue(result.limited());
        assertEquals(SqliteRepository.MAX_QUERY_ROWS, result.limit());
    }

    @Test
    void schemaCreatesCompositeHistoryIndexes() throws Exception {
        Set<String> indexes = new HashSet<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT name FROM sqlite_master WHERE type = 'index'")) {
            while (rs.next()) {
                indexes.add(rs.getString("name"));
            }
        }

        assertTrue(indexes.contains("idx_heap_instance_ts"));
        assertTrue(indexes.contains("idx_gc_instance_ts"));
        assertTrue(indexes.contains("idx_cpu_instance_ts"));
    }
}
