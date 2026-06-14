package agent.model;

/**
 * A point-in-time CPU sample for the target JVM process and profiler-owned
 * daemon threads.
 *
 * <p>Percent fields use -1.0 when the JVM/OS does not expose the counter.
 */
public record CpuSnapshot(
    long timestampMs,
    double processCpuLoadPercent,
    double systemCpuLoadPercent,
    long processCpuTimeMs,
    long agentThreadCpuTimeMs,
    double agentThreadCpuLoadPercent,
    int availableProcessors,
    boolean processCpuSupported,
    boolean systemCpuSupported,
    boolean agentThreadCpuSupported
) {}
