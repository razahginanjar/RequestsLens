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
}
