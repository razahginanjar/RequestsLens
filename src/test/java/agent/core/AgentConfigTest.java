package agent.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AgentConfigTest {

    @Test
    void loadsDefaultsWhenNoArgsProvided() {
        AgentConfig config = AgentConfig.load(null);
        assertEquals(7070, config.getHttpPort());
        assertEquals("127.0.0.1", config.getHttpHost());
        assertEquals(10L,  config.getBaseIntervalMs());
        assertEquals(1000L, config.getCpuSamplingIntervalMs());
        assertNotNull(config.getInstanceId());
        assertFalse(config.isAuthEnabled());
        assertFalse(config.isCorsEnabled());
        assertTrue(config.isLocalOnlyHttpBind());
        assertFalse(config.isLineProfilingConfigured());
        assertFalse(config.isLineProfilingActive());
        assertEquals("", config.getLinePackages());
        assertEquals(5L, config.getLineSampleIntervalMs());
        assertEquals(1000, config.getLineMaxSamplesPerTrace());
        assertEquals(300, config.getLineMaxLinesPerTrace());
        assertEquals(262_144, config.getLineMaxTracePayloadBytes());
        assertFalse(config.isLineAllocationProfilingActive());
    }

    @Test
    void parsesPortFromArgString() {
        AgentConfig config = AgentConfig.load("port=9090");
        assertEquals(9090, config.getHttpPort());
    }

    @Test
    void parsesMultipleArgsFromArgString() {
        AgentConfig config = AgentConfig.load("port=8888,interval=20,cpu.interval=750");
        assertEquals(8888, config.getHttpPort());
        assertEquals(20L,  config.getBaseIntervalMs());
        assertEquals(750L, config.getCpuSamplingIntervalMs());
    }

    @Test
    void parsesHttpSafetyArgsFromArgString() {
        AgentConfig config = AgentConfig.load("host=0.0.0.0,auth.token=1234567890abcdef,"
            + "cors.enabled=true,cors.origins=http://localhost:3000");

        assertEquals("0.0.0.0", config.getHttpHost());
        assertFalse(config.isLocalOnlyHttpBind());
        assertTrue(config.isAuthEnabled());
        assertEquals("1234567890abcdef", config.getAuthToken());
        assertTrue(config.isCorsEnabled());
        assertEquals("http://localhost:3000", config.getCorsAllowedOrigins());
    }

    @Test
    void clampsIntervalBelowMinimum() {
        AgentConfig config = AgentConfig.load("interval=1");
        // 1ms is below the 5ms minimum — should be clamped to 5
        assertEquals(5L, config.getBaseIntervalMs());
    }

    @Test
    void clampsCpuSamplingIntervalBelowMinimum() {
        AgentConfig config = AgentConfig.load("cpu.interval=10");
        assertEquals(250L, config.getCpuSamplingIntervalMs());
    }

    @Test
    void handlesInvalidPortGracefully() {
        AgentConfig config = AgentConfig.load("port=notanumber");
        // Invalid port falls back to default
        assertEquals(7070, config.getHttpPort());
    }

    @Test
    void handlesNullArgsGracefully() {
        assertDoesNotThrow(() -> AgentConfig.load(null));
    }

    @Test
    void handlesEmptyArgsGracefully() {
        assertDoesNotThrow(() -> AgentConfig.load(""));
    }

    @Test
    void parsesLineProfilingSafetyArgsFromArgString() {
        AgentConfig config = AgentConfig.load("line.enabled=true,line.packages=demo,com.example.*,"
            + "line.interval=7,line.max.samples=250,line.max.lines=80,"
            + "line.max.payload.bytes=12345,line.alloc.enabled=true");

        assertTrue(config.isLineProfilingConfigured());
        assertTrue(config.isLineProfilingActive());
        assertEquals("demo,com.example", config.getLinePackages());
        assertEquals(7L, config.getLineSampleIntervalMs());
        assertEquals(250, config.getLineMaxSamplesPerTrace());
        assertEquals(80, config.getLineMaxLinesPerTrace());
        assertEquals(12_345, config.getLineMaxTracePayloadBytes());
        assertTrue(config.isLineAllocationProfilingActive());
        assertTrue(config.isLineProfilingTargetClass("demo.DemoApplication"));
        assertTrue(config.isLineProfilingTargetClass("com.example.orders.OrderService"));
        assertFalse(config.isLineProfilingTargetClass("demolition.NotTarget"));
    }

    @Test
    void commaContinuationOnlyAppliesToListArgs() {
        AgentConfig config = AgentConfig.load("port=9090,ignored,line.enabled=true,"
            + "line.packages=demo,com.example");

        assertEquals(9090, config.getHttpPort());
        assertEquals("demo,com.example", config.getLinePackages());
    }

    @Test
    void lineProfilingStaysInactiveWithoutPackageScope() {
        AgentConfig config = AgentConfig.load("line.enabled=true");

        assertTrue(config.isLineProfilingConfigured());
        assertFalse(config.isLineProfilingActive());
        assertFalse(config.isLineProfilingTargetClass("demo.DemoApplication"));
    }

    @Test
    void lineProfilingRejectsDependencyAndAgentClasses() {
        AgentConfig config = AgentConfig.load("line.enabled=true,"
            + "line.packages=demo,org.springframework,agent");

        assertTrue(config.isLineProfilingTargetClass("demo.DemoApplication"));
        assertFalse(config.isLineProfilingTargetClass("org.springframework.web.servlet.DispatcherServlet"));
        assertFalse(config.isLineProfilingTargetClass("agent.core.AgentMain"));
        assertFalse(config.isLineProfilingTargetClass("java.util.ArrayList"));
    }

    @Test
    void lineProfilingCapsAreValidated() {
        AgentConfig config = AgentConfig.load("line.enabled=true,line.packages=demo,"
            + "line.interval=0,line.max.samples=999999,line.max.lines=999999,"
            + "line.max.payload.bytes=999999999");

        assertEquals(5L, config.getLineSampleIntervalMs());
        assertEquals(100_000, config.getLineMaxSamplesPerTrace());
        assertEquals(10_000, config.getLineMaxLinesPerTrace());
        assertEquals(4 * 1024 * 1024, config.getLineMaxTracePayloadBytes());
    }
}
