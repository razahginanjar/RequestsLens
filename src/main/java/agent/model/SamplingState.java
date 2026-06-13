package agent.model;

/**
 * The two states of the adaptive sampling controller.
 *
 * <ul>
 *   <li>{@code NORMAL}    — sampling at the configured base interval (e.g. 10ms).</li>
 *   <li>{@code THROTTLED} — sampling at a reduced rate (base × multiplier, e.g. 50ms),
 *       applied automatically when the target application is under high request load.</li>
 * </ul>
 */
public enum SamplingState {
    NORMAL,
    THROTTLED
}
