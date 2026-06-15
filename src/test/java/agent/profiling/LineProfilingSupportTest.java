package agent.profiling;

import agent.core.AgentConfig;
import agent.model.LineHotspot;
import agent.model.MethodSpan;
import agent.model.RequestTrace;
import demo.LineHotspotTestTarget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class LineProfilingSupportTest {

    @AfterEach
    void tearDown() {
        LineProfilingSupport.resetForTests();
    }

    @Test
    void enrichesTraceWithRequestScopedLineHotspots() throws Exception {
        AgentConfig config = AgentConfig.load("line.enabled=true,line.packages=demo,"
            + "line.interval=1,line.max.samples=25,line.max.lines=10");
        LineProfilingSupport.configure(config);

        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean running = new AtomicBoolean(true);
        Thread target = new Thread(new LineHotspotTestTarget(started, running), "line-hotspot-test");
        target.start();
        assertTrue(started.await(5, TimeUnit.SECONDS));

        try {
            LineProfilingSupport.requestStart("abc", target);
            for (int i = 0; i < 8; i++) {
                LineProfilingSupport.sampleActiveRequests();
            }
            LineProfilingSupport.requestComplete("abc");

            RequestTrace enriched = LineProfilingSupport.enrich(emptyTrace("abc"));

            assertTrue(enriched.lineSampleCount() > 0);
            assertFalse(enriched.lineHotspots().isEmpty());
            LineHotspot hottest = enriched.lineHotspots().get(0);
            assertEquals("demo.LineHotspotTestTarget", hottest.className());
            assertEquals("run", hottest.methodName());
            assertTrue(hottest.lineNumber() > 0);
            assertTrue(hottest.estimatedWallNs() > 0);
            assertEquals(0, LineProfilingSupport.activeSessionCount());
            assertEquals(0, LineProfilingSupport.completedSessionCount());
        } finally {
            running.set(false);
            target.join(5_000L);
        }
    }

    @Test
    void dropsSamplesAfterPerTraceCap() throws Exception {
        AgentConfig config = AgentConfig.load("line.enabled=true,line.packages=demo,"
            + "line.interval=1,line.max.samples=1,line.max.lines=10");
        LineProfilingSupport.configure(config);

        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean running = new AtomicBoolean(true);
        Thread target = new Thread(new LineHotspotTestTarget(started, running), "line-hotspot-cap-test");
        target.start();
        assertTrue(started.await(5, TimeUnit.SECONDS));

        try {
            LineProfilingSupport.requestStart("capped", target);
            for (int i = 0; i < 5; i++) {
                LineProfilingSupport.sampleActiveRequests();
            }
            LineProfilingSupport.requestComplete("capped");

            RequestTrace enriched = LineProfilingSupport.enrich(emptyTrace("capped"));

            assertEquals(1, enriched.lineSampleCount());
            assertTrue(enriched.droppedLineSamples() > 0);
            assertTrue(enriched.lineHotspotsTruncated());
        } finally {
            running.set(false);
            target.join(5_000L);
        }
    }

    @Test
    void staysInactiveWhenLineProfilingDisabled() {
        AgentConfig config = AgentConfig.load("line.enabled=false,line.packages=demo");
        LineProfilingSupport.configure(config);

        LineProfilingSupport.requestStart("off", Thread.currentThread());
        LineProfilingSupport.sampleActiveRequests();
        LineProfilingSupport.requestComplete("off");

        RequestTrace enriched = LineProfilingSupport.enrich(emptyTrace("off"));

        assertEquals(0, enriched.lineSampleCount());
        assertTrue(enriched.lineHotspots().isEmpty());
    }

    private static RequestTrace emptyTrace(String id) {
        MethodSpan root = new MethodSpan();
        root.className = "HTTP";
        root.methodName = "GET /test";
        return new RequestTrace(id, "GET", "/test", System.currentTimeMillis(),
            1L, 1L, 0L, 0, 0, false, false, false, root);
    }
}
