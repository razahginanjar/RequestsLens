package agent.benchmark;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentOverheadBenchmarkTest {

    @Test
    void parsesSelfMonitoringStatusSnapshot() {
        String json = """
            {
              "selfMonitoringStatus": "warn",
              "selfMonitoringIssues": ["sampling-delays", "trace-drops"],
              "selfMonitoringIssueCount": 2,
              "totalDroppedSamples": 7,
              "totalInternalErrors": 1,
              "aggregationCycles": 5,
              "lastAggregationDurationMs": 12,
              "profilerHttpRequests": 9
            }
            """;

        AgentOverheadBenchmark.StatusSnapshot snapshot =
            AgentOverheadBenchmark.StatusSnapshot.fromJson(json);

        assertEquals("warn", snapshot.status());
        assertEquals(2, snapshot.issueCount());
        assertEquals("sampling-delays,trace-drops", snapshot.issues());
        assertEquals(7, snapshot.totalDroppedSamples());
        assertEquals(1, snapshot.totalInternalErrors());
        assertEquals(5, snapshot.aggregationCycles());
        assertEquals(12, snapshot.lastAggregationDurationMs());
        assertEquals(9, snapshot.profilerHttpRequests());
    }

    @Test
    void unavailableStatusSnapshotCarriesNote() {
        AgentOverheadBenchmark.StatusSnapshot snapshot =
            AgentOverheadBenchmark.StatusSnapshot.unavailable("http-503");

        assertEquals("unavailable", snapshot.status());
        assertEquals("http-503", snapshot.note());
        assertEquals(0, snapshot.totalDroppedSamples());
    }

    @Test
    void csvQuotesIssueNamesWithCommas() {
        List<AgentOverheadBenchmark.BenchmarkResult> results = List.of(
            new AgentOverheadBenchmark.BenchmarkResult(
                "baseline", false, 10, 2, 10, 100.0, 1.0, 1.0, 1.0, 1.0,
                AgentOverheadBenchmark.StatusSnapshot.unavailable("baseline"),
                Path.of("baseline.log")),
            new AgentOverheadBenchmark.BenchmarkResult(
                "agent", true, 10, 2, 10, 90.0, 1.0, 1.0, 1.0, 1.0,
                new AgentOverheadBenchmark.StatusSnapshot(
                    "warn", 2, "sampling-delays,trace-drops", 1, 0, 3, 4, 5, ""),
                Path.of("agent.log"))
        );

        String csv = AgentOverheadBenchmark.toCsv(results);

        assertTrue(csv.contains("\"sampling-delays,trace-drops\""));
    }
}
