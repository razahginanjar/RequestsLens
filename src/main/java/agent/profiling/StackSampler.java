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
                    fold(e.getValue());
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

    /** Fold one stack bottom-up (outermost frame first) into the tree. */
    private void fold(StackTraceElement[] stack) {
        if (stack == null || stack.length == 0) return;
        root.samples++;
        FlameNode node = root;
        // Outermost frame is the last element; innermost (executing) is index 0.
        for (int i = stack.length - 1; i >= 0; i--) {
            StackTraceElement f = stack[i];
            String frame = f.getClassName() + "." + f.getMethodName();
            node = node.child(frame);
            node.samples++;
        }
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
