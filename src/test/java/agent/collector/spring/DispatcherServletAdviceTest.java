package agent.collector.spring;

import agent.buffer.RingBuffer;
import agent.core.AgentSelfMetrics;
import agent.model.EndpointSample;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DispatcherServletAdviceTest {

    private static final String BEST_MATCHING_PATTERN_ATTRIBUTE =
        "org.springframework.web.servlet.HandlerMapping.bestMatchingPattern";

    @AfterEach
    void cleanUp() {
        DispatcherServletAdvice.endpointBuffer = null;
        DispatcherServletAdvice.selfMetrics = null;
    }

    @Test
    void usesSpringMatchedPatternWhenAvailable() {
        RingBuffer<EndpointSample> buffer = new RingBuffer<>(10);
        DispatcherServletAdvice.endpointBuffer = buffer;

        DispatcherServletAdvice.onExit(
            new FakeRequest("GET", "/items/42", "/items/{id}"),
            entered());

        List<EndpointSample> samples = new ArrayList<>();
        buffer.drainTo(samples);
        assertEquals(1, samples.size());
        assertEquals("/items/{id}", samples.get(0).path());
    }

    @Test
    void fallsBackToRawUriWhenNoMatchedPatternExists() {
        RingBuffer<EndpointSample> buffer = new RingBuffer<>(10);
        DispatcherServletAdvice.endpointBuffer = buffer;

        DispatcherServletAdvice.onExit(
            new FakeRequest("GET", "/health", null),
            entered());

        List<EndpointSample> samples = new ArrayList<>();
        buffer.drainTo(samples);
        assertEquals(1, samples.size());
        assertEquals("/health", samples.get(0).path());
    }

    @Test
    void incrementsDropCounterWhenEndpointBufferOverwritesOldestSample() {
        RingBuffer<EndpointSample> buffer = new RingBuffer<>(1);
        AgentSelfMetrics metrics = new AgentSelfMetrics();
        DispatcherServletAdvice.endpointBuffer = buffer;
        DispatcherServletAdvice.selfMetrics = metrics;

        DispatcherServletAdvice.onExit(
            new FakeRequest("GET", "/first", null),
            entered());
        DispatcherServletAdvice.onExit(
            new FakeRequest("GET", "/second", null),
            entered());

        assertEquals(1, metrics.snapshot("x", 10).droppedEndpointSamples());
        List<EndpointSample> samples = new ArrayList<>();
        buffer.drainTo(samples);
        assertEquals(1, samples.size());
        assertEquals("/second", samples.get(0).path());
    }

    private static long[] entered() {
        return new long[] { System.nanoTime() - 1_000_000L, 0L, 0L, 0L };
    }

    public static final class FakeRequest {
        private final String method;
        private final String uri;
        private final String pattern;

        FakeRequest(String method, String uri, String pattern) {
            this.method = method;
            this.uri = uri;
            this.pattern = pattern;
        }

        public String getMethod() {
            return method;
        }

        public String getRequestURI() {
            return uri;
        }

        public Object getAttribute(String name) {
            return BEST_MATCHING_PATTERN_ATTRIBUTE.equals(name) ? pattern : null;
        }
    }
}
