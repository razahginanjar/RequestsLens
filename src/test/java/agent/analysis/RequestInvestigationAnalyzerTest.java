package agent.analysis;

import agent.model.FlameNode;
import agent.model.JfrEvent;
import agent.model.LiveLogEvent;
import agent.model.MethodSpan;
import agent.model.RequestTrace;
import agent.profiling.asyncprofiler.AsyncProfilerController;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RequestInvestigationAnalyzerTest {

    @Test
    void correlatesTraceWithJfrLogsAndNativeProfile() {
        RequestTrace trace = trace("trace-a", "/external", 10_100L);
        RequestTrace peer = trace("trace-peer", "/external", 10_000L);
        JfrEvent gc = new JfrEvent(10_040L, "jdk.GarbageCollection",
            "gc", "G1 Young", 14L, 14_000_000L, "GC Thread", "young gc",
            Map.of());
        LiveLogEvent error = new LiveLogEvent(10_060L, "app-log", "logback",
            "ERROR", "demo.ExternalController", "http-nio-1",
            "remote call failed", "java.io.IOException");
        FlameNode root = new FlameNode("root");
        root.samples = 100L;
        AsyncProfilerController.CollapsedSnapshot snapshot =
            new AsyncProfilerController.CollapsedSnapshot(root, List.of(
                new AsyncProfilerController.CollapsedStack(
                    "demo.ExternalController.handle;demo.Repository.load", 100L,
                    List.of("demo.ExternalController.handle",
                        "demo.Repository.load"))), 1, 0, false);
        AsyncProfilerController.Status status = asyncStatus(9_900L, 10_200L,
            100L);

        RequestInvestigationAnalyzer.RequestInvestigation investigation =
            RequestInvestigationAnalyzer.investigate(trace, List.of(peer, trace),
                List.of(gc), List.of(error), status, snapshot,
                500, 10, 5, 10_250L);

        assertTrue(investigation.available());
        assertEquals("trace-a", investigation.traceId());
        assertEquals("external", investigation.dominantSignal());
        assertTrue(investigation.summary().contains("JFR has 1 event"));
        assertEquals(1L, investigation.jfr().eventCount());
        assertEquals(1, investigation.logs().errorCount());
        assertTrue(investigation.asyncProfiler().overlapsWindow());
        assertTrue(investigation.asyncProfiler().stacks().get(0).matchedTraceFrame());
        assertTrue(investigation.findings().stream()
            .anyMatch(f -> "jfr-gc".equals(f.category())));
        assertTrue(investigation.findings().stream()
            .anyMatch(f -> "logs-error".equals(f.category())));
        assertFalse(investigation.hotspots().isEmpty());
        assertFalse(investigation.timeline().isEmpty());
    }

    @Test
    void reportsUnavailableWhenTraceIsMissing() {
        RequestInvestigationAnalyzer.RequestInvestigation investigation =
            RequestInvestigationAnalyzer.investigate(null, List.of(), List.of(),
                List.of(), null, null, 500, 10, 5, 1L);

        assertFalse(investigation.available());
        assertEquals("unavailable", investigation.dominantSignal());
        assertEquals("none", investigation.confidence());
    }

    private static RequestTrace trace(String id, String path, long timestampMs) {
        MethodSpan root = new MethodSpan();
        root.className = "HTTP";
        root.methodName = "GET " + path;
        root.spanKind = "request";
        root.wallNs = 100_000_000L;
        root.cpuNs = 20_000_000L;
        root.allocBytes = 128_000L;
        root.selfWallNs = 10_000_000L;
        root.selfCpuNs = 5_000_000L;
        root.selfAllocBytes = 4_000L;

        MethodSpan controller = span("demo.ExternalController", "handle",
            "method", 25_000_000L, 10_000_000L, 14_000_000L,
            8_000_000L, 32_000L, 12_000L);
        MethodSpan sql = span("demo.Repository", "load", "sql",
            70_000_000L, 70_000_000L, 3_000_000L, 3_000_000L,
            8_000L, 8_000L);
        sql.externalOperation = "SELECT";
        sql.externalResource = "select item by id";
        root.children.add(controller);
        root.children.add(sql);

        return new RequestTrace(id, "GET", path, timestampMs, root.wallNs,
            root.cpuNs, root.allocBytes, 2, 0, false, false, false, root);
    }

    private static MethodSpan span(String className, String methodName,
                                   String kind, long wallNs, long selfWallNs,
                                   long cpuNs, long selfCpuNs,
                                   long allocBytes, long selfAllocBytes) {
        MethodSpan span = new MethodSpan();
        span.className = className;
        span.methodName = methodName;
        span.spanKind = kind;
        span.wallNs = wallNs;
        span.selfWallNs = selfWallNs;
        span.cpuNs = cpuNs;
        span.selfCpuNs = selfCpuNs;
        span.allocBytes = allocBytes;
        span.selfAllocBytes = selfAllocBytes;
        return span;
    }

    private static AsyncProfilerController.Status asyncStatus(long startedAtMs,
                                                              long stoppedAtMs,
                                                              long samples) {
        return new AsyncProfilerController.Status(true, true, true, true,
            false, "test", "linux/x64", "cpu", 10_000_000L, 30,
            5000, "", startedAtMs, stoppedAtMs, 0L, 1L, 1L, 0L,
            samples, 1, samples, false, 0, "stopped", "");
    }
}
