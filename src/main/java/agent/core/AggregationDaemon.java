package agent.core;

import agent.alert.AlertEvaluator;
import agent.collector.spring.BeanMemoryMapper;
import agent.collector.spring.EndpointAggregator;
import agent.model.EndpointStats;
import agent.model.GcEvent;
import agent.model.HeapSnapshot;
import agent.model.RequestTrace;
import agent.persistence.PersistenceWriter;
import agent.sampling.AdaptiveSamplingController;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Background daemon that periodically aggregates collected metrics.
 *
 * Runs every 5 seconds. Drains the endpoint buffer, computes stats,
 * updates the registry, and refreshes the bean ranking cache.
 *
 * This is Tier 2 — moderate logging allowed, but not on every tick.
 */
public final class AggregationDaemon {

    private static final Logger log =
        Logger.getLogger(AggregationDaemon.class.getName());

    private static final long INTERVAL_SECONDS = 5L;

    /** Most-recent request traces kept for the HTTP API (newest first, capped). */
    private static final int MAX_RECENT_TRACES = 50;
    private final Deque<RequestTrace> recentTraces = new ArrayDeque<>();

    private final CollectorRegistry registry;
    private final EndpointAggregator endpointAggregator;
    private final BeanMemoryMapper   beanMapper;

    // Phase 4 collaborators
    private final AdaptiveSamplingController adaptiveController;
    private final AlertEvaluator             alertEvaluator;

    public AggregationDaemon(CollectorRegistry registry,
                             EndpointAggregator endpointAggregator,
                             BeanMemoryMapper beanMapper,
                             AdaptiveSamplingController adaptiveController,
                             AlertEvaluator alertEvaluator) {
        this.registry            = registry;
        this.endpointAggregator  = endpointAggregator;
        this.beanMapper          = beanMapper;
        this.adaptiveController  = adaptiveController;
        this.alertEvaluator      = alertEvaluator;
    }

    public void start() {
        ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "profiler-aggregation-daemon");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });

        scheduler.scheduleAtFixedRate(
            this::aggregate,
            INTERVAL_SECONDS,
            INTERVAL_SECONDS,
            TimeUnit.SECONDS
        );

        log.info("AggregationDaemon started — interval=" + INTERVAL_SECONDS + "s");
    }

    private void aggregate() {
        long startedMs = System.currentTimeMillis();
        long startedNs = System.nanoTime();
        try {
            // ── Aggregate endpoint samples ────────────────────────────────
            // This daemon is the SINGLE consumer of the endpoint ring buffer.
            // RingBuffer.drainTo() is destructive (it clears slots as it reads),
            // so no other component may drain it. We compute the stats here and
            // publish the result to the registry for the HTTP thread to read.
            List<EndpointStats> stats = endpointAggregator.aggregate();
            registry.updateEndpointStats(stats);

            // Update total RPS on registry (used by Phase 4 adaptive sampler)
            double totalRps = stats.stream()
                .mapToDouble(EndpointStats::currentRps)
                .sum();
            registry.setCurrentRps(totalRps);

            // ── Phase 4: adaptive sampling ────────────────────────────────
            // Feed the current RPS to the controller, which may throttle or
            // recover the HeapSampler's interval.
            adaptiveController.evaluate(totalRps);

            // ── Refresh bean memory ranking (uses internal 30s cache) ─────
            var beans = beanMapper.getTopBeans();
            registry.updateBeanRanking(beans);

            // ── Drain heap/GC buffers ONCE — reused for alerting + persistence
            // This daemon is the single consumer of the heap and GC ring
            // buffers. We drain them once into local batches and use those for
            // BOTH alert/leak evaluation (Phase 4) and persistence (Phase 3).
            // Draining empties the buffers, which is why the live /profiler/heap
            // route reads the latestHeapSnapshot cache for its "current" value.
            List<HeapSnapshot> heapBatch = new ArrayList<>();
            registry.heapBuffer().drainTo(heapBatch);
            List<GcEvent> gcBatch = new ArrayList<>();
            registry.gcBuffer().drainTo(gcBatch);

            // ── Phase 4: alert evaluation ─────────────────────────────────
            // GC overhead = total pause time in this window / window length × 100.
            long totalPauseMs = gcBatch.stream()
                .mapToLong(GcEvent::durationMs).sum();
            double gcOverhead = (double) totalPauseMs / (INTERVAL_SECONDS * 1000) * 100.0;

            alertEvaluator.evaluate(heapBatch, gcBatch, gcOverhead);
            // Publish leak warnings for the /profiler/leaks route.
            registry.setActiveLeakWarnings(alertEvaluator.getActiveLeakWarnings());

            // ── Phase 3: persist the drained batches ──────────────────────
            PersistenceWriter writer = registry.getPersistenceWriter();
            if (writer != null) {
                heapBatch.forEach(writer::enqueueHeap);
                gcBatch.forEach(writer::enqueueGc);
            }

            // ── Phase 6: drain finished request traces, publish newest-first
            List<RequestTrace> newTraces = new ArrayList<>();
            registry.traceBuffer().drainTo(newTraces);
            for (RequestTrace t : newTraces) {
                recentTraces.addFirst(t);
                while (recentTraces.size() > MAX_RECENT_TRACES) recentTraces.removeLast();
            }
            if (!newTraces.isEmpty()) {
                registry.updateRecentTraces(new ArrayList<>(recentTraces));
            }

        } catch (Exception e) {
            registry.selfMetrics().incrementAggregationErrors();
            // Never let aggregation failures propagate and kill the daemon
            log.warning("Aggregation cycle failed: " + e.getMessage());
        } finally {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs);
            registry.selfMetrics().recordAggregationCycle(startedMs, durationMs);
        }
    }
}
