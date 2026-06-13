package agent.sampling;

import agent.core.AgentConfig;
import agent.model.SamplingState;

import java.util.logging.Logger;

/**
 * Controls the HeapSampler's interval based on observed request load (RPS).
 *
 * <h2>State machine</h2>
 * <pre>
 *   NORMAL ───────────────────────────────────────────► THROTTLED
 *          (RPS &gt; threshold for 3 consecutive cycles)
 *
 *   THROTTLED ────────────────────────────────────────► NORMAL
 *             (RPS &lt; threshold for 10 consecutive cycles)
 * </pre>
 *
 * <h2>Why asymmetric thresholds?</h2>
 * Throttling quickly (3 cycles) protects the application as soon as load
 * spikes. Recovering slowly (10 cycles) prevents oscillation — we don't want
 * the sampler flapping between NORMAL and THROTTLED when RPS hovers near the
 * threshold.
 *
 * <h2>Thread safety</h2>
 * Called from the aggregation daemon thread only. All state transitions are
 * published to {@link SamplingStateHolder}, whose fields are volatile.
 */
public final class AdaptiveSamplingController {

    private static final Logger log =
        Logger.getLogger(AdaptiveSamplingController.class.getName());

    // Consecutive-cycle thresholds for the state transitions.
    private static final int CYCLES_TO_THROTTLE = 3;
    private static final int CYCLES_TO_RECOVER  = 10;

    private final AgentConfig         config;
    private final SamplingStateHolder stateHolder;

    // How many consecutive cycles we have observed each load condition.
    private int highLoadCycles   = 0;
    private int normalLoadCycles = 0;

    public AdaptiveSamplingController(AgentConfig config,
                                      SamplingStateHolder stateHolder) {
        this.config      = config;
        this.stateHolder = stateHolder;
    }

    /**
     * Evaluates the current RPS and updates the sampling state if a transition
     * threshold has been reached. Called once per aggregation cycle.
     *
     * @param currentRps current requests per second across all endpoints
     */
    public void evaluate(double currentRps) {
        if (!config.isAdaptiveSamplingEnabled()) return;

        double rpsThreshold = config.getMaxRps();

        if (currentRps > rpsThreshold) {
            // High load — count up toward throttling, reset the recovery counter.
            highLoadCycles++;
            normalLoadCycles = 0;

            if (highLoadCycles >= CYCLES_TO_THROTTLE
                    && stateHolder.getState() == SamplingState.NORMAL) {
                applyState(SamplingState.THROTTLED);
                log.info("AdaptiveSampler: THROTTLED — currentRps=" + currentRps
                    + " threshold=" + rpsThreshold
                    + " effectiveInterval="
                    + (config.getBaseIntervalMs() * config.getThrottleMultiplier()) + "ms");
            }

        } else {
            // Normal load — count up toward recovery, reset the throttle counter.
            normalLoadCycles++;
            highLoadCycles = 0;

            if (normalLoadCycles >= CYCLES_TO_RECOVER
                    && stateHolder.getState() == SamplingState.THROTTLED) {
                applyState(SamplingState.NORMAL);
                log.info("AdaptiveSampler: NORMAL — currentRps=" + currentRps
                    + " effectiveInterval=" + config.getBaseIntervalMs() + "ms");
            }
        }
    }

    /** Computes the effective interval for the new state and publishes it. */
    private void applyState(SamplingState newState) {
        long intervalMs = (newState == SamplingState.THROTTLED)
            ? config.getBaseIntervalMs() * config.getThrottleMultiplier()
            : config.getBaseIntervalMs();
        stateHolder.setState(newState, intervalMs);
    }
}
