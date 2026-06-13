package agent.sampling;

import agent.core.AgentConfig;
import agent.model.SamplingState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AdaptiveSamplingController} (Phase 4 spec, Step 14.1).
 *
 * <p>AgentConfig is a final class; these tests rely on Mockito 5's inline mock
 * maker (the project default) to mock it — the same mechanism PersistenceWriterTest
 * uses to mock the final SqliteRepository.
 */
class AdaptiveSamplingControllerTest {

    /** A config stubbed with the standard adaptive thresholds. */
    private AgentConfig mockConfig() {
        AgentConfig config = mock(AgentConfig.class);
        when(config.isAdaptiveSamplingEnabled()).thenReturn(true);
        when(config.getMaxRps()).thenReturn(500.0);
        when(config.getBaseIntervalMs()).thenReturn(10L);
        when(config.getThrottleMultiplier()).thenReturn(5L);
        return config;
    }

    @Test
    void startsInNormalState() {
        SamplingStateHolder holder = new SamplingStateHolder(10L);
        assertEquals(SamplingState.NORMAL, holder.getState());
        assertEquals(10L, holder.getEffectiveIntervalMs());
    }

    @Test
    void throttlesAfter3HighLoadCycles() {
        SamplingStateHolder holder = new SamplingStateHolder(10L);
        AdaptiveSamplingController ctrl =
            new AdaptiveSamplingController(mockConfig(), holder);

        // 2 high-load cycles — still NORMAL (needs 3 consecutive).
        ctrl.evaluate(600.0);
        ctrl.evaluate(600.0);
        assertEquals(SamplingState.NORMAL, holder.getState());

        // 3rd high-load cycle — switches to THROTTLED (10ms × 5 = 50ms).
        ctrl.evaluate(600.0);
        assertEquals(SamplingState.THROTTLED, holder.getState());
        assertEquals(50L, holder.getEffectiveIntervalMs());
    }

    @Test
    void recoversAfter10NormalCycles() {
        SamplingStateHolder holder = new SamplingStateHolder(10L);
        AdaptiveSamplingController ctrl =
            new AdaptiveSamplingController(mockConfig(), holder);

        // Throttle first.
        for (int i = 0; i < 3; i++) ctrl.evaluate(600.0);
        assertEquals(SamplingState.THROTTLED, holder.getState());

        // 9 normal cycles — still THROTTLED (needs 10 consecutive).
        for (int i = 0; i < 9; i++) ctrl.evaluate(100.0);
        assertEquals(SamplingState.THROTTLED, holder.getState());

        // 10th normal cycle — back to NORMAL at the base interval.
        ctrl.evaluate(100.0);
        assertEquals(SamplingState.NORMAL, holder.getState());
        assertEquals(10L, holder.getEffectiveIntervalMs());
    }

    @Test
    void doesNothingWhenAdaptiveDisabled() {
        AgentConfig config = mockConfig();
        when(config.isAdaptiveSamplingEnabled()).thenReturn(false);
        SamplingStateHolder holder = new SamplingStateHolder(10L);
        AdaptiveSamplingController ctrl =
            new AdaptiveSamplingController(config, holder);

        // Even sustained 1000 RPS must not change the state when disabled.
        for (int i = 0; i < 10; i++) ctrl.evaluate(1000.0);
        assertEquals(SamplingState.NORMAL, holder.getState());
    }

    @Test
    void intermittentSpikesDoNotThrottle() {
        SamplingStateHolder holder = new SamplingStateHolder(10L);
        AdaptiveSamplingController ctrl =
            new AdaptiveSamplingController(mockConfig(), holder);

        // High/low alternating — the consecutive counter keeps resetting,
        // so we never reach 3 consecutive high cycles.
        ctrl.evaluate(600.0);
        ctrl.evaluate(100.0);
        ctrl.evaluate(600.0);
        ctrl.evaluate(100.0);
        ctrl.evaluate(600.0);
        assertEquals(SamplingState.NORMAL, holder.getState());
    }
}
