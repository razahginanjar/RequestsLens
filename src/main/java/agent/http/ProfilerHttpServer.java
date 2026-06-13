package agent.http;

import agent.core.AgentConfig;
import agent.core.CollectorRegistry;
import agent.model.BeanMemoryInfo;
import agent.model.EndpointStats;
import agent.model.GcEvent;
import agent.model.HeapSnapshot;
import agent.model.LeakWarning;
import agent.model.RequestTrace;
import agent.persistence.SqliteRepository;
import agent.profiling.StackSampler;
import agent.profiling.ThreadMetrics;
import agent.sampling.SamplingStateHolder;

import io.javalin.Javalin;
import io.javalin.config.JavalinConfig;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Embedded HTTP server that exposes profiling data as JSON.
 *
 * Uses Javalin — a lightweight HTTP framework that starts in under 100ms
 * and has no dependency on Spring or any other web framework.
 *
 * All routes are read-only GET endpoints. The server runs on a daemon
 * thread pool so it does not prevent JVM shutdown.
 */
public final class ProfilerHttpServer {

    private static final Logger log = Logger.getLogger(ProfilerHttpServer.class.getName());

    private final CollectorRegistry registry;
    private final AgentConfig       config;

    /** Classpath location of the bundled dashboard page. */
    private static final String DASHBOARD_RESOURCE = "/dashboard/index.html";

    /** Lazily-loaded, cached dashboard HTML (loaded once from the classpath). */
    private volatile String dashboardHtmlCache;

    public ProfilerHttpServer(CollectorRegistry registry, AgentConfig config) {
        this.registry = registry;
        this.config   = config;
    }

    /**
     * Starts the HTTP server. Returns immediately.
     * The server runs on Javalin's internal daemon thread pool.
     */
    public void start() {
        // Javalin 7 requires routes (and most config) to be registered upfront
        // inside the create() config block, before the server starts.
        Javalin app = Javalin.create(cfg -> {
            // Suppress Javalin's startup banner — moved to cfg.startup in Javalin 7.
            cfg.startup.showJavalinBanner = false;
            registerRoutes(cfg);
        });

        // start(port) is non-blocking — the server runs on daemon threads
        app.start(config.getHttpPort());

        log.info("ProfilerHttpServer started on port " + config.getHttpPort());
        log.info("Dashboard: http://localhost:" + config.getHttpPort() + "/profiler/dashboard");
    }

    private void registerRoutes(JavalinConfig cfg) {

        // ── GET /profiler/heap ────────────────────────────────────────────
        cfg.routes.get("/profiler/heap", ctx -> {
            // The ring buffer holds only the samples collected since the last
            // persistence drain (~last few seconds) — fine for a live chart.
            List<HeapSnapshot> samples = registry.heapBuffer().snapshot();

            // Build the response map — LinkedHashMap preserves insertion order
            // which makes the JSON more readable
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("sampleCount", samples.size());

            // The "current" value comes from the latest-snapshot cache, which the
            // HeapSampler updates every tick. We can't rely on the ring buffer
            // here because the AggregationDaemon drains it for persistence and it
            // may be momentarily empty. Fall back to the buffer if the cache is
            // not yet populated (e.g. before the first sample).
            HeapSnapshot latest = registry.getLatestHeapSnapshot();
            if (latest == null && !samples.isEmpty()) {
                latest = samples.get(samples.size() - 1);
            }
            if (latest != null) {
                response.put("current", Map.of(
                    "timestampMs",   latest.timestampMs(),
                    "usedBytes",     latest.usedBytes(),
                    "committedBytes",latest.committedBytes(),
                    "maxBytes",      latest.maxBytes(),
                    "usedMb",        latest.usedBytes() / (1024 * 1024),
                    "poolUsage",     latest.poolUsage()
                ));
            }

            // Include all buffered samples for charting
            response.put("samples", samples);

            ctx.json(response);
        });

        // ── GET /profiler/gc ──────────────────────────────────────────────
        cfg.routes.get("/profiler/gc", ctx -> {
            List<GcEvent> events = registry.gcBuffer().snapshot();

            // Compute summary statistics
            long totalPauseMs   = events.stream().mapToLong(GcEvent::durationMs).sum();
            long maxPauseMs     = events.stream().mapToLong(GcEvent::durationMs).max().orElse(0);
            double avgPauseMs   = events.isEmpty() ? 0.0
                : (double) totalPauseMs / events.size();

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("eventCount",    events.size());
            response.put("totalPauseMs",  totalPauseMs);
            response.put("maxPauseMs",    maxPauseMs);
            response.put("avgPauseMs",    Math.round(avgPauseMs * 100.0) / 100.0);
            response.put("events",        events);

            ctx.json(response);
        });

        // ── GET /profiler/status ──────────────────────────────────────────
        // Agent self-health + Phase 4 adaptive-sampling state.
        cfg.routes.get("/profiler/status", ctx -> {
            SamplingStateHolder state = registry.getSamplingStateHolder();
            var selfSnap = registry.selfMetrics()
                .snapshot(config.getInstanceId(), config.getBaseIntervalMs());

            Map<String, Object> status = new LinkedHashMap<>();
            status.put("instanceId",            config.getInstanceId());
            status.put("uptimeMs",              selfSnap.uptimeMs());
            status.put("samplingState",         state.getState().name());
            status.put("effectiveIntervalMs",   state.getEffectiveIntervalMs());
            status.put("baseIntervalMs",        config.getBaseIntervalMs());
            status.put("currentRps",            registry.getCurrentRps());
            status.put("rpsThreshold",          config.getMaxRps());
            status.put("agentHeapUsedBytes",    selfSnap.agentHeapUsedBytes());
            status.put("droppedSamples",        selfSnap.droppedSamples());
            status.put("droppedPersistence",    selfSnap.droppedPersistenceSamples());
            status.put("persistenceQueueDepth", selfSnap.persistenceQueueDepth());
            status.put("samplingDelays",        selfSnap.samplingDelays());
            status.put("lastSampleTimestampMs", selfSnap.lastSampleTimestampMs());

            // Phase 6 — deep profiling status
            status.put("cpuTimingSupported",   ThreadMetrics.cpuSupported());
            status.put("allocTimingSupported", ThreadMetrics.allocSupported());
            status.put("traceEnabled",         config.isTraceEnabled()
                                               && !config.getTracePackages().isBlank());
            status.put("tracePackages",        config.getTracePackages());
            status.put("samplingProfiler",     registry.getStackSampler() != null);
            status.put("recentTraceCount",     registry.recentTraces().size());

            ctx.json(status);
        });

        // ── GET /profiler/summary ─────────────────────────────────────────
        cfg.routes.get("/profiler/summary", ctx -> {
            List<HeapSnapshot> heapSamples = registry.heapBuffer().snapshot();
            List<GcEvent>      gcEvents    = registry.gcBuffer().snapshot();

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("instanceId", config.getInstanceId());
            summary.put("heapSampleCount", heapSamples.size());
            summary.put("gcEventCount",    gcEvents.size());

            // Prefer the always-fresh cache for the current heap figure; the
            // ring buffer is drained for persistence and may be empty.
            HeapSnapshot latest = registry.getLatestHeapSnapshot();
            if (latest == null && !heapSamples.isEmpty()) {
                latest = heapSamples.get(heapSamples.size() - 1);
            }
            if (latest != null) {
                summary.put("currentHeapUsedMb",
                    latest.usedBytes() / (1024 * 1024));
            }

            ctx.json(summary);
        });

        // ── GET /profiler/dashboard ───────────────────────────────────────
        // Serves the self-contained dashboard bundled in the agent JAR at
        // /dashboard/index.html. The HTML is loaded once from the classpath and
        // cached; if the resource is missing we fall back to a minimal page so
        // the route never 500s.
        cfg.routes.get("/profiler/dashboard", ctx -> {
            ctx.contentType("text/html");
            ctx.result(dashboardHtml());
        });

        // ── GET /profiler/endpoints ───────────────────────────────────────────
        cfg.routes.get("/profiler/endpoints", ctx -> {
            // Read the snapshot published by the AggregationDaemon. We must NOT
            // drain the endpoint buffer here: the daemon is its single consumer
            // (RingBuffer.drainTo clears slots as it reads). Re-draining here
            // would steal samples from the daemon and return partial data.
            List<EndpointStats> stats = registry.endpointStats();

            // Recompute the headline RPS from the published stats so the value
            // is consistent with the per-endpoint list being returned.
            double totalRps = stats.stream()
                .mapToDouble(EndpointStats::currentRps)
                .sum();

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("endpointCount", stats.size());
            response.put("totalRps",      Math.round(totalRps * 100.0) / 100.0);
            response.put("endpoints",     stats);
            ctx.json(response);
        });

        // ── GET /profiler/beans ───────────────────────────────────────────────
        cfg.routes.get("/profiler/beans", ctx -> {
            List<BeanMemoryInfo> beans = registry.beanMemoryRanking();

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("beanCount",    beans.size());
            response.put("beans",        beans);
            ctx.json(response);
        });

        // ── GET /profiler/history/heap ────────────────────────────────────────
        // Reads persisted heap samples from SQLite within a [from, to] time range.
        // Survives JVM restarts (data lives on disk). Both query params required.
        cfg.routes.get("/profiler/history/heap", ctx -> {
            SqliteRepository repo = registry.getSqliteRepository();
            if (repo == null) {
                // Persistence disabled (or failed to start) — nothing to query.
                ctx.status(503).json(Map.of("error", "Persistence not enabled"));
                return;
            }

            String fromStr = ctx.queryParam("from");
            String toStr   = ctx.queryParam("to");
            if (fromStr == null || toStr == null) {
                ctx.status(400).json(Map.of(
                    "error", "Both 'from' and 'to' query parameters are required",
                    "example", "/profiler/history/heap?from=1748000000000&to=1748003600000"
                ));
                return;
            }

            try {
                long fromMs = Long.parseLong(fromStr);
                long toMs   = Long.parseLong(toStr);
                if (toMs <= fromMs) {
                    ctx.status(400).json(Map.of("error", "'to' must be greater than 'from'"));
                    return;
                }

                List<HeapSnapshot> samples = repo.queryHeap(fromMs, toMs);
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("fromMs",      fromMs);
                response.put("toMs",        toMs);
                response.put("sampleCount", samples.size());
                response.put("samples",     samples);
                ctx.json(response);

            } catch (NumberFormatException e) {
                ctx.status(400).json(Map.of("error",
                    "'from' and 'to' must be epoch milliseconds (long integers)"));
            }
        });

        // ── GET /profiler/history/gc ──────────────────────────────────────────
        // Reads persisted GC events from SQLite within a [from, to] time range.
        cfg.routes.get("/profiler/history/gc", ctx -> {
            SqliteRepository repo = registry.getSqliteRepository();
            if (repo == null) {
                ctx.status(503).json(Map.of("error", "Persistence not enabled"));
                return;
            }

            String fromStr = ctx.queryParam("from");
            String toStr   = ctx.queryParam("to");
            if (fromStr == null || toStr == null) {
                ctx.status(400).json(Map.of(
                    "error", "Both 'from' and 'to' query parameters are required"));
                return;
            }

            try {
                long fromMs = Long.parseLong(fromStr);
                long toMs   = Long.parseLong(toStr);
                if (toMs <= fromMs) {
                    ctx.status(400).json(Map.of("error", "'to' must be greater than 'from'"));
                    return;
                }

                List<GcEvent> events = repo.queryGc(fromMs, toMs);
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("fromMs",     fromMs);
                response.put("toMs",       toMs);
                response.put("eventCount", events.size());
                response.put("events",     events);
                ctx.json(response);

            } catch (NumberFormatException e) {
                ctx.status(400).json(Map.of("error", "Invalid time parameters"));
            }
        });

        // ── GET /profiler/leaks ───────────────────────────────────────────
        // The leak warnings active as of the most recent aggregation cycle.
        cfg.routes.get("/profiler/leaks", ctx -> {
            List<LeakWarning> warnings = registry.getActiveLeakWarnings();
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("activeWarnings", warnings.size());
            response.put("warnings",       warnings);
            ctx.json(response);
        });

        // ── GET /profiler/traces (Phase 6) ────────────────────────────────
        // Lightweight summaries of recent request traces (newest first).
        cfg.routes.get("/profiler/traces", ctx -> {
            List<RequestTrace> traces = registry.recentTraces();
            List<Map<String, Object>> summaries = new java.util.ArrayList<>();
            for (RequestTrace t : traces) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("traceId",         t.traceId());
                s.put("method",          t.method());
                s.put("path",            t.path());
                s.put("timestampMs",     t.timestampMs());
                s.put("totalWallMs",     t.totalWallNs() / 1_000_000.0);
                s.put("totalCpuMs",      t.totalCpuNs()  / 1_000_000.0);
                s.put("totalAllocBytes", t.totalAllocBytes());
                summaries.add(s);
            }
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("traceCount", summaries.size());
            response.put("traces",     summaries);
            ctx.json(response);
        });

        // ── GET /profiler/trace/{id} (Phase 6) ────────────────────────────
        // The full method call tree for one trace.
        cfg.routes.get("/profiler/trace/{id}", ctx -> {
            String id = ctx.pathParam("id");
            RequestTrace match = null;
            for (RequestTrace t : registry.recentTraces()) {
                if (t.traceId().equals(id)) { match = t; break; }
            }
            if (match == null) {
                ctx.status(404).json(Map.of("error", "No trace with id " + id
                    + " (it may have aged out of the recent buffer)"));
                return;
            }
            ctx.json(match);
        });

        // ── GET /profiler/flamegraph (Phase 6) ────────────────────────────
        // The folded sampling-profiler tree (samples per frame).
        cfg.routes.get("/profiler/flamegraph", ctx -> {
            StackSampler sampler = registry.getStackSampler();
            if (sampler == null) {
                ctx.json(Map.of("enabled", false, "frame", "root", "samples", 0,
                    "children", Map.of()));
                return;
            }
            ctx.json(sampler.snapshot());
        });
    }

    /**
     * Returns the dashboard HTML, loading it once from the bundled classpath
     * resource and caching it. If the resource cannot be read (e.g. it was not
     * packaged), a minimal fallback page is returned so the route never fails.
     *
     * <p>Double-checked locking keeps the (cheap) load single-shot without
     * synchronizing on every request.
     */
    private String dashboardHtml() {
        String cached = dashboardHtmlCache;
        if (cached != null) return cached;

        synchronized (this) {
            if (dashboardHtmlCache != null) return dashboardHtmlCache;

            String html;
            try (InputStream in = getClass().getResourceAsStream(DASHBOARD_RESOURCE)) {
                if (in == null) {
                    log.warning("Dashboard resource not found on classpath: " + DASHBOARD_RESOURCE);
                    html = fallbackDashboard();
                } else {
                    html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            } catch (IOException e) {
                log.warning("Failed to read dashboard resource: " + e.getMessage());
                html = fallbackDashboard();
            }

            dashboardHtmlCache = html;
            return html;
        }
    }

    /** Minimal page shown only if the bundled dashboard resource is unavailable. */
    private static String fallbackDashboard() {
        return """
            <!DOCTYPE html><html><head><title>JVM Profiler Agent</title></head>
            <body style="font-family:sans-serif">
              <h1>JVM Profiler Agent</h1>
              <p>The dashboard resource was not bundled. Raw JSON endpoints:</p>
              <ul>
                <li><a href="/profiler/status">/profiler/status</a></li>
                <li><a href="/profiler/heap">/profiler/heap</a></li>
                <li><a href="/profiler/gc">/profiler/gc</a></li>
                <li><a href="/profiler/endpoints">/profiler/endpoints</a></li>
                <li><a href="/profiler/beans">/profiler/beans</a></li>
                <li><a href="/profiler/leaks">/profiler/leaks</a></li>
              </ul>
            </body></html>
            """;
    }
}