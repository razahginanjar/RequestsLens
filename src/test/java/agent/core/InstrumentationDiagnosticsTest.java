package agent.core;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InstrumentationDiagnosticsTest {

    @Test
    void recordsTransformationAndLineMetadataDiagnostics() {
        InstrumentationDiagnostics diagnostics = new InstrumentationDiagnostics();
        diagnostics.enableLineNumberDiagnostics();
        diagnostics.recordTraceClassDiscovered("com.user.app.A", false);
        diagnostics.recordTraceClassDiscovered("com.user.app.B", true);
        diagnostics.recordTraceTransformation("com.user.app.A", false, 3);
        diagnostics.recordTraceTransformation("com.user.app.B", true, 2);
        diagnostics.recordFrameworkTransformation("org.springframework.web.servlet.DispatcherServlet");
        diagnostics.recordLineNumberClass("com.user.app.A");
        diagnostics.recordError("com.user.app.B", new IllegalStateException("bad frame"));

        InstrumentationDiagnostics.Snapshot snapshot = diagnostics.snapshot();

        assertEquals(2, snapshot.discoveredTraceClasses());
        assertEquals(1, snapshot.alreadyLoadedTraceClasses());
        assertEquals(2, snapshot.transformedTraceClasses());
        assertEquals(1, snapshot.transformedFrameworkClasses());
        assertEquals(5, snapshot.transformedTraceMethods());
        assertTrue(snapshot.lineNumberDiagnosticsEnabled());
        assertEquals(1, snapshot.lineNumberClasses());
        assertEquals(1, snapshot.classesWithoutLineNumbers());
        assertEquals("com.user.app.B", snapshot.classesWithoutLineNumberExamples().get(0));
        assertEquals(1, snapshot.recentErrors().size());
        assertTrue(snapshot.recentErrors().get(0).message().contains("bad frame"));
    }

    @Test
    void redactedMapKeepsCountsButHidesClassNames() {
        InstrumentationDiagnostics diagnostics = new InstrumentationDiagnostics();
        diagnostics.enableLineNumberDiagnostics();
        diagnostics.recordTraceTransformation("com.user.app.A", false, 1);
        diagnostics.recordError("com.user.app.A", new RuntimeException("boom"));

        Map<String, Object> redacted = diagnostics.snapshot().toMap(false);

        assertEquals(1, redacted.get("transformedTraceClasses"));
        assertEquals(1, redacted.get("classesWithoutLineNumbers"));
        assertEquals(java.util.List.of(), redacted.get("classesWithoutLineNumberExamples"));
        assertEquals(java.util.List.of(), redacted.get("recentTransformedTraceClasses"));
        assertEquals(java.util.List.of(), redacted.get("recentErrors"));
    }

    @Test
    void missingLineNumberCountIsNotCappedByExamples() {
        InstrumentationDiagnostics diagnostics = new InstrumentationDiagnostics();
        diagnostics.enableLineNumberDiagnostics();
        for (int i = 0; i < 25; i++) {
            diagnostics.recordTraceTransformation("com.user.app.Generated" + i, false, 1);
        }

        InstrumentationDiagnostics.Snapshot snapshot = diagnostics.snapshot();

        assertEquals(25, snapshot.classesWithoutLineNumbers());
        assertEquals(20, snapshot.classesWithoutLineNumberExamples().size());
    }
}
