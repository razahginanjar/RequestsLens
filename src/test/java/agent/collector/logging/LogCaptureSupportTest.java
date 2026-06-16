package agent.collector.logging;

import agent.buffer.RingBuffer;
import agent.model.LiveLogEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LogCaptureSupportTest {

    @AfterEach
    void reset() {
        LogCaptureSupport.configure(false, null);
    }

    @Test
    void recordsBoundedAppLogsAndCountsDroppedRows() {
        RingBuffer<LiveLogEvent> buffer = new RingBuffer<>(2);
        LogCaptureSupport.configure(true, buffer);

        LogCaptureSupport.record("logback", 100L, "INFO", "demo.App",
            "main", "first", "");
        LogCaptureSupport.record("logback", 200L, "WARN", "demo.App",
            "main", "second", "");
        LogCaptureSupport.record("logback", 300L, "ERROR", "demo.App",
            "main", "third", "boom");

        List<LiveLogEvent> events = buffer.snapshot();
        assertEquals(2, events.size());
        assertEquals("second", events.get(0).message());
        assertEquals("third", events.get(1).message());
        assertEquals(3L, LogCaptureSupport.capturedCount());
        assertEquals(1L, LogCaptureSupport.droppedCount());
    }

    @Test
    void skipsRequestLensInternalLogs() {
        RingBuffer<LiveLogEvent> buffer = new RingBuffer<>(10);
        LogCaptureSupport.configure(true, buffer);

        LogCaptureSupport.record("jul", 100L, "INFO", "agent.core.AgentMain",
            "main", "internal", "");

        assertTrue(buffer.snapshot().isEmpty());
        assertEquals(0L, LogCaptureSupport.capturedCount());
    }
}
