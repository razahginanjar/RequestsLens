package agent.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One node in a request's method call tree (Phase 6).
 *
 * <p>Built on the request thread while the request is in flight, then treated as
 * immutable once published. All times are nanoseconds. The {@code self*} fields
 * are this method MINUS its children, computed when the trace is finalized.
 *
 * <p>Start counters are intentionally NOT stored here — they live in
 * {@code RequestProfilingContext} so this serialized model stays clean.
 */
public final class MethodSpan {

    /** Fully-qualified declaring class (or "HTTP" for the synthetic request root). */
    public String className = "";
    /** Method name (or "METHOD /path" for the synthetic request root). */
    public String methodName = "";

    /** Total time including children. */
    public long wallNs;
    public long cpuNs;
    public long allocBytes;

    /** Time attributable to this method alone (total − sum of children). */
    public long selfWallNs;
    public long selfCpuNs;
    public long selfAllocBytes;

    /** Child calls, in invocation order. */
    public final List<MethodSpan> children = new ArrayList<>();

    /**
     * Per-object-type allocation made while this span was executing (Phase 6,
     * Amendment A) — captured exactly via allocation-site instrumentation, not
     * sampled. Keyed by object type name. Written on the request thread only.
     */
    public final Map<String, TypeAlloc> allocByType = new LinkedHashMap<>();

    /** Count + total shallow bytes for one allocated object type. */
    public static final class TypeAlloc {
        public long count;
        public long bytes;
    }

    /** Records one allocation of {@code type} of {@code bytes} shallow size. */
    public void recordAlloc(String type, long bytes) {
        TypeAlloc a = allocByType.computeIfAbsent(type, k -> new TypeAlloc());
        a.count++;
        a.bytes += bytes;
    }
}
