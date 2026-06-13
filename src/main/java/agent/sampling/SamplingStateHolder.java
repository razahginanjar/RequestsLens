package agent.sampling;

import agent.model.SamplingState;

/**
 * Shared mutable state for the adaptive sampler.
 *
 * <h2>Why volatile?</h2>
 * Two threads access these fields:
 * <ul>
 *   <li>Writer: {@code AdaptiveSamplingController} (aggregation daemon thread)</li>
 *   <li>Reader: {@code HeapSampler} (sampler daemon thread)</li>
 * </ul>
 * {@code volatile} guarantees the reader always sees the latest value written
 * by the writer, with no locking. This is safe because there is exactly one
 * writer and one reader, and neither performs a read-then-write compound
 * operation on these fields.
 */
public final class SamplingStateHolder {

    private volatile SamplingState state = SamplingState.NORMAL;
    private volatile long          effectiveIntervalMs;

    public SamplingStateHolder(long baseIntervalMs) {
        this.effectiveIntervalMs = baseIntervalMs;
    }

    // ── Writer method (called by AdaptiveSamplingController) ──────────────
    public void setState(SamplingState s, long intervalMs) {
        // Write the interval first, then the state. The reader only ever uses
        // the interval, so ordering is not strictly required, but writing the
        // value before the "flag" is a good habit for one-writer/one-reader.
        this.effectiveIntervalMs = intervalMs;
        this.state               = s;
    }

    // ── Reader methods (called by HeapSampler / HTTP thread) ──────────────
    public SamplingState getState()               { return state; }
    public long          getEffectiveIntervalMs() { return effectiveIntervalMs; }
}
