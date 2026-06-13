package agent.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AgentConfigTest {

    @Test
    void loadsDefaultsWhenNoArgsProvided() {
        AgentConfig config = AgentConfig.load(null);
        assertEquals(7070, config.getHttpPort());
        assertEquals(10L,  config.getBaseIntervalMs());
        assertNotNull(config.getInstanceId());
    }

    @Test
    void parsesPortFromArgString() {
        AgentConfig config = AgentConfig.load("port=9090");
        assertEquals(9090, config.getHttpPort());
    }

    @Test
    void parsesMultipleArgsFromArgString() {
        AgentConfig config = AgentConfig.load("port=8888,interval=20");
        assertEquals(8888, config.getHttpPort());
        assertEquals(20L,  config.getBaseIntervalMs());
    }

    @Test
    void clampsIntervalBelowMinimum() {
        AgentConfig config = AgentConfig.load("interval=1");
        // 1ms is below the 5ms minimum — should be clamped to 5
        assertEquals(5L, config.getBaseIntervalMs());
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