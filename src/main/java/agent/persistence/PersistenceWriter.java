package agent.persistence;

import agent.core.AgentSelfMetrics;
import agent.model.GcEvent;
import agent.model.HeapSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Logger;

/**
 * Buffers metric data and writes it to SQLite in asynchronous batches.
 *
 * <h2>Design</h2>
 * Two bounded {@link BlockingQueue}s sit between the producers and SQLite.
 * Producers call the {@code enqueue*} methods, which use non-blocking
 * {@code offer()} and return immediately. The PersistenceDaemon calls
 * {@link #flush()} every few seconds to drain the queues and write batches.
 * This keeps all disk I/O off the producer threads.
 *
 * <h2>Backpressure / drop policy</h2>
 * The queues are bounded at {@link #QUEUE_CAPACITY}. At a 10ms sampling
 * interval that is ~100 heap samples/second, and a 5s flush drains ~500 at a
 * time, so {@value #QUEUE_CAPACITY} is roughly ten flush cycles of headroom.
 * If SQLite stalls long enough to fill a queue, further samples are dropped
 * (not blocked) and counted via {@link AgentSelfMetrics#incrementDroppedPersistence()}.
 * Dropping is a deliberate trade-off: a disk problem must never block the
 * application or crash the agent.
 *
 * <h2>Tiers</h2>
 * {@code enqueue*} is treated as Tier 1 (no allocation beyond the queue node,
 * no logging). {@link #flush()} is Tier 2 (runs every few seconds; logs only
 * on anomalies).
 */
public final class PersistenceWriter {

    private static final Logger log =
        Logger.getLogger(PersistenceWriter.class.getName());

    private static final int QUEUE_CAPACITY = 5_000;

    /** Max rows drained per flush, per queue — bounds one transaction's size. */
    private static final int HEAP_DRAIN_LIMIT = 1_000;
    private static final int GC_DRAIN_LIMIT   = 500;

    private final BlockingQueue<HeapSnapshot> heapQueue;
    private final BlockingQueue<GcEvent>      gcQueue;
    private final SqliteRepository            repository;
    private final AgentSelfMetrics            selfMetrics;

    public PersistenceWriter(SqliteRepository repository,
                             AgentSelfMetrics selfMetrics) {
        this.heapQueue   = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
        this.gcQueue     = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
        this.repository  = repository;
        this.selfMetrics = selfMetrics;
    }

    // ── Enqueue methods (Tier 1 safe — non-blocking) ──────────────────────

    /**
     * Enqueues a heap snapshot for persistence. Never blocks; if the queue is
     * full the snapshot is dropped and the drop counter is incremented.
     */
    public void enqueueHeap(HeapSnapshot snapshot) {
        if (!heapQueue.offer(snapshot)) {
            selfMetrics.incrementDroppedPersistence();
            // No logging — treated as a hot path.
        }
    }

    /**
     * Enqueues a GC event for persistence. Never blocks; drops on overflow.
     */
    public void enqueueGc(GcEvent event) {
        if (!gcQueue.offer(event)) {
            selfMetrics.incrementDroppedPersistence();
        }
    }

    // ── Flush (called by the persistence daemon every few seconds) ────────

    /**
     * Drains both queues and writes the drained rows to SQLite in batches.
     * Also republishes the current queue depth to self-metrics.
     */
    public void flush() {
        // Drain heap queue (bounded so one batch/transaction stays reasonable).
        List<HeapSnapshot> heapBatch = new ArrayList<>(HEAP_DRAIN_LIMIT);
        heapQueue.drainTo(heapBatch, HEAP_DRAIN_LIMIT);

        // Drain GC queue.
        List<GcEvent> gcBatch = new ArrayList<>(GC_DRAIN_LIMIT);
        gcQueue.drainTo(gcBatch, GC_DRAIN_LIMIT);

        // Publish queue depth AFTER draining so the gauge reflects the backlog
        // that remains, not what we just removed.
        int depth = heapQueue.size() + gcQueue.size();
        selfMetrics.setPersistenceQueueDepth(depth);

        // Write to SQLite — these may take a few milliseconds each.
        if (!heapBatch.isEmpty()) {
            repository.batchInsertHeap(heapBatch);
        }
        if (!gcBatch.isEmpty()) {
            repository.batchInsertGc(gcBatch);
        }

        // Warn only when the backlog is dangerously high (Tier 2: anomaly only).
        if (depth > QUEUE_CAPACITY * 0.8) {
            log.warning("Persistence queue at " + depth + "/" + QUEUE_CAPACITY
                + " — SQLite writes may be falling behind");
        }
    }

    public int heapQueueSize() { return heapQueue.size(); }
    public int gcQueueSize()   { return gcQueue.size(); }
}
