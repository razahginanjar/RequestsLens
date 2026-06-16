package agent.profiling;

import agent.model.FlameNode;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Always-on, low-overhead sampling profiler (Phase 6).
 *
 * <p>Every {@code intervalMs} it snapshots the stacks of RUNNABLE application
 * threads and folds them into a shared {@link FlameNode} tree (flame-graph data).
 * It needs no bytecode instrumentation, so it is safe to leave on in production —
 * cost is bounded by the sampling interval, not by request volume.
 *
 * <p>Statistical by nature: short-lived methods may never be sampled. For exact
 * per-method timing use the method-tracing path instead.
 */
public final class StackSampler {

    private static final Logger log = Logger.getLogger(StackSampler.class.getName());

    private final long intervalMs;
    private final FlameNode root = new FlameNode("root");
    private final Object lock = new Object();   // guards the tree (sampler writes, HTTP reads)

    public StackSampler(long intervalMs) {
        this.intervalMs = Math.max(5, intervalMs);
    }

    public void start() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "profiler-stack-sampler");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::sample, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        log.info("StackSampler started — interval=" + intervalMs + "ms");
    }

    /** Snapshot one round of stacks and fold them into the tree. */
    private void sample() {
        try {
            Map<Thread, StackTraceElement[]> stacks = Thread.getAllStackTraces();
            synchronized (lock) {
                for (Map.Entry<Thread, StackTraceElement[]> e : stacks.entrySet()) {
                    Thread t = e.getKey();
                    if (!shouldSample(t)) continue;
                    recordStack(e.getValue());
                }
            }
        } catch (Throwable t) {
            // Never let the sampler die.
        }
    }

    /** Sample on-CPU application threads; skip idle pools, JVM and agent threads. */
    private boolean shouldSample(Thread t) {
        if (t == null) return false;
        if (t.getState() != Thread.State.RUNNABLE) return false;       // approximate "on CPU"
        String name = t.getName();
        if (name == null) return false;
        if (name.startsWith("profiler-")) return false;                 // our own threads
        if (name.equals("Reference Handler") || name.equals("Finalizer")
            || name.startsWith("JFR ") || name.equals("Signal Dispatcher")) return false;
        return true;
    }

    boolean recordStack(StackTraceElement[] stack) {
        if (stack == null || stack.length == 0) return false;
        if (isProfilerControlPlaneStack(stack)) return false;
        return fold(stack);
    }

    /** Fold one stack bottom-up (outermost frame first) into the tree. */
    private boolean fold(StackTraceElement[] stack) {
        FlameNode node = root;
        boolean recorded = false;
        // Outermost frame is the last element; innermost (executing) is index 0.
        for (int i = stack.length - 1; i >= 0; i--) {
            StackTraceElement f = stack[i];
            if (isProfilerFrame(f)) continue;
            String frame = f.getClassName() + "." + f.getMethodName();
            if (!recorded) {
                root.samples++;
                recorded = true;
            }
            node = node.child(frame);
            node.samples++;
        }
        return recorded;
    }

    static boolean isProfilerControlPlaneStack(StackTraceElement[] stack) {
        if (stack == null) return false;
        for (StackTraceElement frame : stack) {
            String className = frame == null ? "" : frame.getClassName();
            if (className.startsWith("agent.http.")
                    || className.startsWith("agent.shaded.javalin.")
                    || className.startsWith("agent.shaded.jetty.")
                    || className.startsWith("agent.shaded.jackson.")) {
                return true;
            }
        }
        return false;
    }

    static boolean isProfilerFrame(StackTraceElement frame) {
        if (frame == null) return false;
        String className = frame.getClassName();
        return className != null && (
            className.startsWith("agent.analysis.")
                || className.startsWith("agent.alert.")
                || className.startsWith("agent.buffer.")
                || className.startsWith("agent.collector.")
                || className.startsWith("agent.core.")
                || className.startsWith("agent.http.")
                || className.startsWith("agent.model.")
                || className.startsWith("agent.persistence.")
                || className.startsWith("agent.profiling.")
                || className.startsWith("agent.sampling.")
                || className.startsWith("agent.shaded."));
    }

    /** Returns a deep copy of the current flame tree for the HTTP API. */
    public FlameNode snapshot() {
        synchronized (lock) {
            return copy(root);
        }
    }

    private static FlameNode copy(FlameNode src) {
        FlameNode c = new FlameNode(src.frame);
        c.samples = src.samples;
        for (FlameNode child : src.children.values()) {
            c.children.put(child.frame, copy(child));
        }
        return c;
    }
}
