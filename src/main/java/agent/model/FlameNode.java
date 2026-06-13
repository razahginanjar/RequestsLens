package agent.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Aggregated stack node for the sampling profiler (Phase 6).
 *
 * <p>{@code samples} is how many times a stack passing through this frame was
 * observed; {@code children} are keyed by the child frame string. The tree is
 * built bottom-up (outermost frame nearest the root), producing flame-graph data.
 *
 * <p>Mutated by the StackSampler thread and read by the HTTP API; callers
 * synchronize on the shared root when folding/snapshotting.
 */
public final class FlameNode {

    public final String frame;                 // "com.x.Foo.bar" or "root"
    public long samples;
    public final Map<String, FlameNode> children = new LinkedHashMap<>();

    public FlameNode(String frame) {
        this.frame = frame;
    }

    /** Returns the child for {@code frame}, creating it if absent. */
    public FlameNode child(String frame) {
        return children.computeIfAbsent(frame, FlameNode::new);
    }
}
