package agent.analysis;

import agent.model.MethodSpan;
import agent.model.RequestTrace;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TraceInsightAnalyzerTest {

    @Test
    void explainsExternalDominatedTrace() {
        MethodSpan root = root(100_000_000L, 12_000_000L, 0L);
        MethodSpan sql = span("demo.Repository", "load", "sql",
            70_000_000L, 70_000_000L, 3_000_000L, 3_000_000L, 0L, 0L);
        sql.externalOperation = "SELECT";
        sql.externalResource = "select ? from item";
        MethodSpan service = span("demo.Service", "handle", "method",
            20_000_000L, 20_000_000L, 8_000_000L, 8_000_000L, 16_384L, 16_384L);
        root.children.add(sql);
        root.children.add(service);

        TraceInsightAnalyzer.TraceExplanation explanation =
            TraceInsightAnalyzer.explain(trace("a", "/items/{id}", root));

        assertEquals("external", explanation.dominantSignal());
        assertTrue(explanation.summary().contains("External dependency"));
        assertNotNull(explanation.topExternalSpan());
        assertEquals("sql", explanation.topExternalSpan().spanKind());
        assertTrue(explanation.issues().stream()
            .anyMatch(issue -> "external".equals(issue.category())));
    }

    @Test
    void comparesAgainstRecentSameRoutePeers() {
        RequestTrace current = trace("current", "/slow",
            root(150_000_000L, 50_000_000L, 300_000L));
        RequestTrace peerA = trace("peer-a", "/slow",
            root(100_000_000L, 40_000_000L, 200_000L));
        RequestTrace peerB = trace("peer-b", "/slow",
            root(110_000_000L, 44_000_000L, 220_000L));
        RequestTrace otherRoute = trace("other", "/cpu",
            root(999_000_000L, 999_000_000L, 999_000L));

        TraceInsightAnalyzer.TraceComparison comparison =
            TraceInsightAnalyzer.compare(current, List.of(peerA, peerB, otherRoute));

        assertEquals(2, comparison.peerCount());
        assertEquals("slower", comparison.position());
        assertEquals(105_000_000L, comparison.baselineWallNs());
        assertTrue(comparison.wallDeltaPercent() > 40.0);
    }

    @Test
    void reportsNoBaselineWhenNoPeerMatches() {
        RequestTrace current = trace("current", "/slow",
            root(150_000_000L, 50_000_000L, 300_000L));

        TraceInsightAnalyzer.TraceComparison comparison =
            TraceInsightAnalyzer.compare(current, List.of());

        assertEquals(0, comparison.peerCount());
        assertEquals("no-baseline", comparison.position());
        assertTrue(comparison.summary().contains("No comparable"));
    }

    private static RequestTrace trace(String id, String path, MethodSpan root) {
        return new RequestTrace(id, "GET", path, 1L, root.wallNs, root.cpuNs,
            root.allocBytes, root.children.size(), 0, false, false, false, root);
    }

    private static MethodSpan root(long wallNs, long cpuNs, long allocBytes) {
        MethodSpan root = new MethodSpan();
        root.className = "HTTP";
        root.methodName = "GET /test";
        root.spanKind = "request";
        root.wallNs = wallNs;
        root.cpuNs = cpuNs;
        root.allocBytes = allocBytes;
        root.selfWallNs = Math.max(0L, wallNs);
        root.selfCpuNs = Math.max(0L, cpuNs);
        root.selfAllocBytes = Math.max(0L, allocBytes);
        return root;
    }

    private static MethodSpan span(String className, String methodName, String kind,
                                   long wallNs, long selfWallNs,
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
}
