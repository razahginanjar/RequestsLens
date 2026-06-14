package agent.profiling;

import agent.buffer.RingBuffer;
import agent.core.AgentSelfMetrics;
import agent.model.RequestTrace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceSupportTest {

    @BeforeEach
    void setUp() {
        resetTraceSupport();
    }

    @AfterEach
    void cleanUp() {
        resetTraceSupport();
    }

    @Test
    void incrementsDropCounterWhenTraceBufferOverwritesOldestTrace() {
        RingBuffer<RequestTrace> buffer = new RingBuffer<>(1);
        AgentSelfMetrics metrics = new AgentSelfMetrics();
        TraceSupport.enabled = true;
        TraceSupport.sampleRate = 1;
        TraceSupport.maxDepth = 40;
        TraceSupport.maxSpans = 5000;
        TraceSupport.traceBuffer = buffer;
        TraceSupport.selfMetrics = metrics;

        assertTrue(TraceSupport.requestEnter());
        TraceSupport.requestExit("GET", "/first");

        assertTrue(TraceSupport.requestEnter());
        TraceSupport.requestExit("GET", "/second");

        assertEquals(1, metrics.snapshot("x", 10).droppedTraces());

        List<RequestTrace> traces = new ArrayList<>();
        buffer.drainTo(traces);
        assertEquals(1, traces.size());
        assertEquals("/second", traces.get(0).path());
    }

    private static void resetTraceSupport() {
        RequestProfilingContext.end();
        TraceSupport.enabled = false;
        TraceSupport.sampleRate = 50;
        TraceSupport.maxDepth = 40;
        TraceSupport.maxSpans = 5000;
        TraceSupport.traceBuffer = null;
        TraceSupport.selfMetrics = null;
    }
}
