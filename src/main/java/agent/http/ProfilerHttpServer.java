package agent.http;

import agent.core.AgentConfig;
import agent.core.CollectorRegistry;
import agent.model.AgentStatus;
import agent.model.BeanMemoryInfo;
import agent.model.CpuSnapshot;
import agent.model.EndpointStats;
import agent.model.FlameNode;
import agent.model.GcEvent;
import agent.model.HeapSnapshot;
import agent.model.LeakWarning;
import agent.model.RequestTrace;
import agent.persistence.HistoryQueryResult;
import agent.persistence.PersistenceException;
import agent.persistence.PersistenceWriter;
import agent.persistence.SqliteRepository;
import agent.profiling.LineProfilingSupport;
import agent.profiling.StackSampler;
import agent.profiling.ThreadMetrics;
import agent.sampling.SamplingStateHolder;

import io.javalin.Javalin;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
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
    private static final String API_VERSION = "1";

    private final CollectorRegistry registry;
    private final AgentConfig       config;
    private final SourceCodeService sourceCodeService = new SourceCodeService();

    /** Classpath location of the bundled dashboard page. */
    private static final String DASHBOARD_RESOURCE = "/dashboard/index.html";
    private static final String REDACTION_MESSAGE =
        "Sensitive bean/class details require profiler.auth.token or loopback-only binding.";

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

        // start(host, port) is non-blocking; the server runs on daemon threads.
        app.start(config.getHttpHost(), config.getHttpPort());

        String baseUrl = "http://" + config.getHttpHost() + ":" + config.getHttpPort();
        log.info("ProfilerHttpServer started on " + baseUrl);
        log.info("Dashboard: " + baseUrl + "/profiler/dashboard");
    }

    private void registerRoutes(JavalinConfig cfg) {
        registerPreflightRoutes(cfg);

        cfg.routes.get("/profiler/api", ctx -> {
            if (!authorize(ctx)) return;
            ctx.json(apiCatalog());
        });

        // ── GET /profiler/heap ────────────────────────────────────────────
        cfg.routes.get("/profiler/heap", ctx -> {
            if (!authorize(ctx)) return;
            // The ring buffer holds only the samples collected since the last
            // persistence drain (~last few seconds) — fine for a live chart.
            List<HeapSnapshot> samples = registry.heapBuffer().snapshot();

            // Build the response map — LinkedHashMap preserves insertion order
            // which makes the JSON more readable
            Map<String, Object> response = apiResponse("heap");
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
            if (!authorize(ctx)) return;
            List<GcEvent> events = registry.gcBuffer().snapshot();

            // Compute summary statistics
            long totalPauseMs   = events.stream().mapToLong(GcEvent::durationMs).sum();
            long maxPauseMs     = events.stream().mapToLong(GcEvent::durationMs).max().orElse(0);
            double avgPauseMs   = events.isEmpty() ? 0.0
                : (double) totalPauseMs / events.size();

            Map<String, Object> response = apiResponse("gc");
            response.put("eventCount",    events.size());
            response.put("totalPauseMs",  totalPauseMs);
            response.put("maxPauseMs",    maxPauseMs);
            response.put("avgPauseMs",    Math.round(avgPauseMs * 100.0) / 100.0);
            response.put("events",        events);

            ctx.json(response);
        });

        // -- GET /profiler/cpu --------------------------------------------------
        cfg.routes.get("/profiler/cpu", ctx -> {
            if (!authorize(ctx)) return;
            List<CpuSnapshot> samples = registry.cpuBuffer().snapshot();

            Map<String, Object> response = apiResponse("cpu");
            response.put("sampleCount", samples.size());

            CpuSnapshot latest = registry.getLatestCpuSnapshot();
            if (latest == null && !samples.isEmpty()) {
                latest = samples.get(samples.size() - 1);
            }
            if (latest != null) {
                response.put("current", cpuSnapshotMap(latest));
            }
            response.put("samples", samples);

            ctx.json(response);
        });

        // ── GET /profiler/status ──────────────────────────────────────────
        // Agent self-health + Phase 4 adaptive-sampling state.
        cfg.routes.get("/profiler/status", ctx -> {
            if (!authorize(ctx)) return;
            boolean exposeSensitiveDetails = canExposeSensitiveDetails();
            SamplingStateHolder state = registry.getSamplingStateHolder();
            var selfSnap = registry.selfMetrics()
                .snapshot(config.getInstanceId(), config.getBaseIntervalMs());
            CpuSnapshot latestCpu = registry.getLatestCpuSnapshot();

            Map<String, Object> status = apiResponse("status");
            status.put("instanceId",            config.getInstanceId());
            status.put("httpHost",              config.getHttpHost());
            status.put("authEnabled",           config.isAuthEnabled());
            status.put("corsEnabled",           config.isCorsEnabled());
            status.put("sensitiveDetailsRedacted", !exposeSensitiveDetails);
            status.put("uptimeMs",              selfSnap.uptimeMs());
            status.put("samplingState",         state.getState().name());
            status.put("effectiveIntervalMs",   state.getEffectiveIntervalMs());
            status.put("baseIntervalMs",        config.getBaseIntervalMs());
            status.put("cpuSamplingIntervalMs", config.getCpuSamplingIntervalMs());
            status.put("currentRps",            registry.getCurrentRps());
            status.put("rpsThreshold",          config.getMaxRps());
            status.put("agentHeapUsedBytes",    selfSnap.agentHeapUsedBytes());
            status.put("droppedSamples",        selfSnap.droppedSamples());
            status.put("droppedGcEvents",       selfSnap.droppedGcEvents());
            status.put("droppedEndpointSamples", selfSnap.droppedEndpointSamples());
            status.put("droppedCpuSamples",     selfSnap.droppedCpuSamples());
            status.put("droppedTraces",         selfSnap.droppedTraces());
            status.put("droppedPersistence",    selfSnap.droppedPersistenceSamples());
            status.put("persistenceConfigured", config.isPersistenceEnabled());
            status.put("persistenceAvailable",  registry.getSqliteRepository() != null);
            status.put("persistencePath",        exposeSensitiveDetails
                ? config.getPersistencePath()
                : "(redacted)");
            status.put("persistenceRetentionDays", config.getPersistenceRetentionDays());
            status.put("persistenceHistoryLimit",  SqliteRepository.MAX_QUERY_ROWS);
            status.put("persistenceQueueCapacity", PersistenceWriter.QUEUE_CAPACITY);
            status.put("persistenceQueueDepth", selfSnap.persistenceQueueDepth());
            status.put("persistenceFlushes",    selfSnap.persistenceFlushes());
            status.put("persistenceFlushFailures",
                selfSnap.persistenceFlushFailures());
            status.put("lastPersistenceFlushTimestampMs",
                selfSnap.lastPersistenceFlushTimestampMs());
            status.put("lastPersistenceFlushDurationMs",
                selfSnap.lastPersistenceFlushDurationMs());
            status.put("persistedHeapSamples",  selfSnap.persistedHeapSamples());
            status.put("persistedGcEvents",     selfSnap.persistedGcEvents());
            status.put("persistedCpuSamples",   selfSnap.persistedCpuSamples());
            status.put("persistencePurgeRuns",  selfSnap.persistencePurgeRuns());
            status.put("persistencePurgeFailures",
                selfSnap.persistencePurgeFailures());
            status.put("lastPersistencePurgeTimestampMs",
                selfSnap.lastPersistencePurgeTimestampMs());
            status.put("lastPersistencePurgeDeletedRows",
                selfSnap.lastPersistencePurgeDeletedRows());
            status.put("samplingDelays",        selfSnap.samplingDelays());
            status.put("lastSampleTimestampMs", selfSnap.lastSampleTimestampMs());
            status.put("lastCpuSampleTimestampMs", selfSnap.lastCpuSampleTimestampMs());
            status.put("aggregationCycles",     selfSnap.aggregationCycles());
            status.put("aggregationErrors",     selfSnap.aggregationErrors());
            status.put("lastAggregationTimestampMs", selfSnap.lastAggregationTimestampMs());
            status.put("lastAggregationDurationMs", selfSnap.lastAggregationDurationMs());
            status.put("profilerHttpRequests",  selfSnap.profilerHttpRequests());
            status.put("profilerHttpAuthFailures", selfSnap.profilerHttpAuthFailures());
            status.put("lastProfilerHttpRequestTimestampMs",
                selfSnap.lastProfilerHttpRequestTimestampMs());
            status.putAll(selfMonitoringSummary(selfSnap, System.currentTimeMillis()));
            status.put("bufferCapacities", Map.of(
                "heap", registry.heapBuffer().capacity(),
                "gc", registry.gcBuffer().capacity(),
                "cpu", registry.cpuBuffer().capacity(),
                "endpoint", registry.endpointBuffer().capacity(),
                "trace", registry.traceBuffer().capacity()));

            if (latestCpu != null) {
                status.put("cpuSampleTimestampMs", latestCpu.timestampMs());
                status.put("processCpuLoadPercent", latestCpu.processCpuLoadPercent());
                status.put("systemCpuLoadPercent", latestCpu.systemCpuLoadPercent());
                status.put("processCpuTimeMs", latestCpu.processCpuTimeMs());
                status.put("agentThreadCpuTimeMs", latestCpu.agentThreadCpuTimeMs());
                status.put("agentThreadCpuLoadPercent", latestCpu.agentThreadCpuLoadPercent());
                status.put("cpuAvailableProcessors", latestCpu.availableProcessors());
                status.put("processCpuSupported", latestCpu.processCpuSupported());
                status.put("systemCpuSupported", latestCpu.systemCpuSupported());
                status.put("agentThreadCpuSupported", latestCpu.agentThreadCpuSupported());
            } else {
                status.put("cpuSampleTimestampMs", 0L);
                status.put("processCpuLoadPercent", -1.0);
                status.put("systemCpuLoadPercent", -1.0);
                status.put("processCpuTimeMs", -1L);
                status.put("agentThreadCpuTimeMs", -1L);
                status.put("agentThreadCpuLoadPercent", -1.0);
                status.put("cpuAvailableProcessors", Runtime.getRuntime().availableProcessors());
                status.put("processCpuSupported", false);
                status.put("systemCpuSupported", false);
                status.put("agentThreadCpuSupported", ThreadMetrics.cpuSupported());
            }

            // Phase 6 — deep profiling status
            status.put("cpuTimingSupported",   ThreadMetrics.cpuSupported());
            status.put("allocTimingSupported", ThreadMetrics.allocSupported());
            status.put("traceEnabled",         config.isTraceEnabled()
                                               && !config.getTracePackages().isBlank());
            status.put("tracePackages",        exposeSensitiveDetails
                                               ? config.getTracePackages()
                                               : "(redacted)");
            status.put("lineProfilingConfigured", config.isLineProfilingConfigured());
            status.put("lineProfilingEnabled", config.isLineProfilingActive());
            status.put("linePackages",         exposeSensitiveDetails
                                               ? config.getLinePackages()
                                               : "(redacted)");
            status.put("lineSampleIntervalMs", config.getLineSampleIntervalMs());
            status.put("lineMaxSamplesPerTrace", config.getLineMaxSamplesPerTrace());
            status.put("lineMaxLinesPerTrace", config.getLineMaxLinesPerTrace());
            status.put("lineMaxTracePayloadBytes", config.getLineMaxTracePayloadBytes());
            status.put("lineAllocEnabled",     config.isLineAllocationProfilingActive());
            status.put("sourceViewConfigured", config.isSourceViewConfigured());
            status.put("sourceViewEnabled",    config.isSourceViewActive());
            status.put("sourceRootCount",
                SourceCodeService.rootCount(config.getSourceRoots()));
            status.put("sourceRoots",          exposeSensitiveDetails
                ? config.getSourceRoots() : "(redacted)");
            status.put("sourceContextLines",   config.getSourceContextLines());
            status.put("lineActiveRequests",   LineProfilingSupport.activeSessionCount());
            status.put("lineCompletedRequests", LineProfilingSupport.completedSessionCount());
            status.put("samplingProfiler",     registry.getStackSampler() != null);
            status.put("recentTraceCount",     registry.recentTraces().size());
            status.put("links",                apiLinks());

            ctx.json(status);
        });

        // ── GET /profiler/summary ─────────────────────────────────────────
        cfg.routes.get("/profiler/summary", ctx -> {
            if (!authorize(ctx)) return;
            List<HeapSnapshot> heapSamples = registry.heapBuffer().snapshot();
            List<GcEvent>      gcEvents    = registry.gcBuffer().snapshot();
            List<CpuSnapshot>  cpuSamples  = registry.cpuBuffer().snapshot();

            Map<String, Object> summary = apiResponse("summary");
            summary.put("instanceId", config.getInstanceId());
            summary.put("heapSampleCount", heapSamples.size());
            summary.put("gcEventCount",    gcEvents.size());
            summary.put("cpuSampleCount",  cpuSamples.size());

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
            CpuSnapshot latestCpu = registry.getLatestCpuSnapshot();
            if (latestCpu == null && !cpuSamples.isEmpty()) {
                latestCpu = cpuSamples.get(cpuSamples.size() - 1);
            }
            if (latestCpu != null) {
                summary.put("processCpuLoadPercent", latestCpu.processCpuLoadPercent());
                summary.put("systemCpuLoadPercent", latestCpu.systemCpuLoadPercent());
            }

            ctx.json(summary);
        });

        // ── GET /profiler/dashboard ───────────────────────────────────────
        // Serves the self-contained dashboard bundled in the agent JAR at
        // /dashboard/index.html. The HTML is loaded once from the classpath and
        // cached; if the resource is missing we fall back to a minimal page so
        // the route never 500s.
        cfg.routes.get("/profiler/dashboard", ctx -> {
            if (!authorize(ctx)) return;
            ctx.contentType("text/html");
            ctx.result(dashboardHtml());
        });

        // ── GET /profiler/endpoints ───────────────────────────────────────────
        cfg.routes.get("/profiler/endpoints", ctx -> {
            if (!authorize(ctx)) return;
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

            Map<String, Object> response = apiResponse("endpoints");
            response.put("endpointCount", stats.size());
            response.put("totalRps",      Math.round(totalRps * 100.0) / 100.0);
            response.put("endpoints",     stats);
            ctx.json(response);
        });

        // ── GET /profiler/beans ───────────────────────────────────────────────
        cfg.routes.get("/profiler/beans", ctx -> {
            if (!authorize(ctx)) return;
            List<BeanMemoryInfo> beans = registry.beanMemoryRanking();

            Map<String, Object> response = apiResponse("beans");
            response.put("beanCount",    beans.size());
            response.put("redacted",     !canExposeSensitiveDetails());
            response.put("beans",        canExposeSensitiveDetails()
                ? beans
                : redactedBeans(beans));
            ctx.json(response);
        });

        // ── GET /profiler/history/heap ────────────────────────────────────────
        // Reads persisted heap samples from SQLite within a [from, to] time range.
        // Survives JVM restarts (data lives on disk). Both query params required.
        cfg.routes.get("/profiler/history/heap", ctx -> {
            if (!authorize(ctx)) return;
            SqliteRepository repo = registry.getSqliteRepository();
            if (repo == null) {
                // Persistence disabled (or failed to start) — nothing to query.
                ctx.status(503).json(apiError("history.heap",
                    "Persistence not enabled or unavailable"));
                return;
            }

            String fromStr = ctx.queryParam("from");
            String toStr   = ctx.queryParam("to");
            if (fromStr == null || toStr == null) {
                ctx.status(400).json(historyBadRequest("history.heap",
                    "Both 'from' and 'to' query parameters are required",
                    "/profiler/history/heap?from=1748000000000&to=1748003600000"));
                return;
            }

            try {
                long fromMs = Long.parseLong(fromStr);
                long toMs   = Long.parseLong(toStr);
                if (toMs <= fromMs) {
                    ctx.status(400).json(historyBadRequest("history.heap",
                        "'to' must be greater than 'from'",
                        "/profiler/history/heap?from=1748000000000&to=1748003600000"));
                    return;
                }

                HistoryQueryResult<HeapSnapshot> result =
                    repo.queryHeapResult(fromMs, toMs);
                Map<String, Object> response = apiResponse("history.heap");
                response.put("fromMs",      fromMs);
                response.put("toMs",        toMs);
                response.put("sampleCount", result.rows().size());
                response.put("limited",     result.limited());
                response.put("limit",       result.limit());
                response.put("samples",     result.rows());
                ctx.json(response);

            } catch (NumberFormatException e) {
                ctx.status(400).json(historyBadRequest("history.heap",
                    "'from' and 'to' must be epoch milliseconds (long integers)",
                    "/profiler/history/heap?from=1748000000000&to=1748003600000"));
            } catch (PersistenceException e) {
                ctx.status(500).json(apiError("history.heap",
                    "Persistence query failed"));
            }
        });

        // ── GET /profiler/history/gc ──────────────────────────────────────────
        // Reads persisted GC events from SQLite within a [from, to] time range.
        cfg.routes.get("/profiler/history/gc", ctx -> {
            if (!authorize(ctx)) return;
            SqliteRepository repo = registry.getSqliteRepository();
            if (repo == null) {
                ctx.status(503).json(apiError("history.gc",
                    "Persistence not enabled or unavailable"));
                return;
            }

            String fromStr = ctx.queryParam("from");
            String toStr   = ctx.queryParam("to");
            if (fromStr == null || toStr == null) {
                ctx.status(400).json(historyBadRequest("history.gc",
                    "Both 'from' and 'to' query parameters are required",
                    "/profiler/history/gc?from=1748000000000&to=1748003600000"));
                return;
            }

            try {
                long fromMs = Long.parseLong(fromStr);
                long toMs   = Long.parseLong(toStr);
                if (toMs <= fromMs) {
                    ctx.status(400).json(historyBadRequest("history.gc",
                        "'to' must be greater than 'from'",
                        "/profiler/history/gc?from=1748000000000&to=1748003600000"));
                    return;
                }

                HistoryQueryResult<GcEvent> result = repo.queryGcResult(fromMs, toMs);
                Map<String, Object> response = apiResponse("history.gc");
                response.put("fromMs",     fromMs);
                response.put("toMs",       toMs);
                response.put("eventCount", result.rows().size());
                response.put("limited",    result.limited());
                response.put("limit",      result.limit());
                response.put("events",     result.rows());
                ctx.json(response);

            } catch (NumberFormatException e) {
                ctx.status(400).json(historyBadRequest("history.gc",
                    "'from' and 'to' must be epoch milliseconds (long integers)",
                    "/profiler/history/gc?from=1748000000000&to=1748003600000"));
            } catch (PersistenceException e) {
                ctx.status(500).json(apiError("history.gc",
                    "Persistence query failed"));
            }
        });

        // -- GET /profiler/history/cpu -----------------------------------------
        // Reads persisted CPU samples from SQLite within a [from, to] time range.
        cfg.routes.get("/profiler/history/cpu", ctx -> {
            if (!authorize(ctx)) return;
            SqliteRepository repo = registry.getSqliteRepository();
            if (repo == null) {
                ctx.status(503).json(apiError("history.cpu",
                    "Persistence not enabled or unavailable"));
                return;
            }

            String fromStr = ctx.queryParam("from");
            String toStr   = ctx.queryParam("to");
            if (fromStr == null || toStr == null) {
                ctx.status(400).json(historyBadRequest("history.cpu",
                    "Both 'from' and 'to' query parameters are required",
                    "/profiler/history/cpu?from=1748000000000&to=1748003600000"));
                return;
            }

            try {
                long fromMs = Long.parseLong(fromStr);
                long toMs   = Long.parseLong(toStr);
                if (toMs <= fromMs) {
                    ctx.status(400).json(historyBadRequest("history.cpu",
                        "'to' must be greater than 'from'",
                        "/profiler/history/cpu?from=1748000000000&to=1748003600000"));
                    return;
                }

                HistoryQueryResult<CpuSnapshot> result = repo.queryCpuResult(fromMs, toMs);
                Map<String, Object> response = apiResponse("history.cpu");
                response.put("fromMs",      fromMs);
                response.put("toMs",        toMs);
                response.put("sampleCount", result.rows().size());
                response.put("limited",     result.limited());
                response.put("limit",       result.limit());
                response.put("samples",     result.rows());
                ctx.json(response);

            } catch (NumberFormatException e) {
                ctx.status(400).json(historyBadRequest("history.cpu",
                    "'from' and 'to' must be epoch milliseconds (long integers)",
                    "/profiler/history/cpu?from=1748000000000&to=1748003600000"));
            } catch (PersistenceException e) {
                ctx.status(500).json(apiError("history.cpu",
                    "Persistence query failed"));
            }
        });

        // ── GET /profiler/leaks ───────────────────────────────────────────
        // The leak warnings active as of the most recent aggregation cycle.
        cfg.routes.get("/profiler/leaks", ctx -> {
            if (!authorize(ctx)) return;
            List<LeakWarning> warnings = registry.getActiveLeakWarnings();
            Map<String, Object> response = apiResponse("leaks");
            response.put("activeWarnings", warnings.size());
            response.put("warnings",       warnings);
            ctx.json(response);
        });

        // ── GET /profiler/traces (Phase 6) ────────────────────────────────
        // Lightweight summaries of recent request traces (newest first).
        cfg.routes.get("/profiler/traces", ctx -> {
            if (!authorize(ctx)) return;
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
                s.put("capturedSpans",   t.capturedSpans());
                s.put("droppedSpans",    t.droppedSpans());
                s.put("truncated",       t.truncated());
                s.put("lineSampleCount", t.lineSampleCount());
                s.put("lineHotspotCount", t.lineHotspots().size());
                s.put("lineAllocationCount", lineAllocationCount(t));
                s.put("lineAllocatedBytes", lineAllocatedBytes(t));
                s.put("droppedLineSamples", t.droppedLineSamples());
                s.put("droppedLineHotspots", t.droppedLineHotspots());
                s.put("lineHotspotsTruncated", t.lineHotspotsTruncated());
                summaries.add(s);
            }
            Map<String, Object> response = apiResponse("traces");
            response.put("traceCount", summaries.size());
            response.put("redacted", !canExposeSensitiveDetails());
            response.put("traces",     summaries);
            ctx.json(response);
        });

        // ── GET /profiler/trace/{id} (Phase 6) ────────────────────────────
        // The full method call tree for one trace.
        cfg.routes.get("/profiler/trace/{id}", ctx -> {
            if (!authorize(ctx)) return;
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
            if (!canExposeSensitiveDetails()) {
                ctx.json(redactedTrace(match));
                return;
            }
            ctx.json(match);
        });

        // -- GET /profiler/source ---------------------------------------------
        // Small, source-root-scoped source window for a sampled line hotspot.
        cfg.routes.get("/profiler/source", ctx -> {
            if (!authorize(ctx)) return;
            if (!canExposeSensitiveDetails()) {
                ctx.status(403).json(apiError("source", REDACTION_MESSAGE));
                return;
            }
            if (!config.isSourceViewActive()) {
                ctx.status(404).json(sourceResponse(SourceCodeService.SourceLookup
                    .unavailable(ctx.queryParam("className"), queryLine(ctx), "Source view is disabled")));
                return;
            }

            String className = ctx.queryParam("className");
            String lineParam = ctx.queryParam("line");
            if (className == null || className.isBlank()
                    || lineParam == null || lineParam.isBlank()) {
                ctx.status(400).json(apiError("source",
                    "Both 'className' and 'line' query parameters are required"));
                return;
            }
            if (!SourceCodeService.isSafeClassName(className)) {
                ctx.status(400).json(apiError("source",
                    "'className' must be a fully-qualified Java class name"));
                return;
            }

            int lineNumber;
            try {
                lineNumber = Integer.parseInt(lineParam);
            } catch (NumberFormatException e) {
                ctx.status(400).json(apiError("source",
                    "'line' must be a positive integer"));
                return;
            }
            if (lineNumber < 1) {
                ctx.status(400).json(apiError("source",
                    "'line' must be a positive integer"));
                return;
            }
            if (!config.isSourceViewTargetClass(className)) {
                ctx.status(403).json(apiError("source",
                    "Class is outside profiler.line.packages or is excluded"));
                return;
            }

            SourceCodeService.SourceLookup lookup = sourceCodeService.lookup(
                config.getSourceRoots(), className, lineNumber,
                config.getSourceContextLines());
            ctx.status(lookup.sourceAvailable() ? 200 : 404).json(sourceResponse(lookup));
        });

        // ── GET /profiler/flamegraph (Phase 6) ────────────────────────────
        // The folded sampling-profiler tree (samples per frame).
        cfg.routes.get("/profiler/flamegraph", ctx -> {
            if (!authorize(ctx)) return;
            StackSampler sampler = registry.getStackSampler();
            if (sampler == null) {
                ctx.json(Map.of("enabled", false, "frame", "root", "samples", 0,
                    "children", Map.of()));
                return;
            }
            FlameNode snapshot = sampler.snapshot();
            if (!canExposeSensitiveDetails()) {
                ctx.json(redactedFlamegraph(snapshot.samples));
                return;
            }
            ctx.json(snapshot);
        });
    }

    private Map<String, Object> apiCatalog() {
        Map<String, Object> catalog = apiResponse("api");
        catalog.put("instanceId", config.getInstanceId());
        catalog.put("authRequired", config.isAuthEnabled());
        catalog.put("corsEnabled", config.isCorsEnabled());
        catalog.put("sensitiveDetailsRedacted", !canExposeSensitiveDetails());
        catalog.put("capabilities", apiCapabilities());
        catalog.put("links", apiLinks());

        List<Map<String, Object>> routes = new ArrayList<>();
        routes.add(apiRoute("GET", "/profiler/api",
            "Machine-readable profiler API catalog", false, false, false));
        routes.add(apiRoute("GET", "/profiler/status",
            "Agent health, self-monitoring, and runtime state", false, false, false));
        routes.add(apiRoute("GET", "/profiler/summary",
            "Small heap and GC summary", false, false, false));
        routes.add(apiRoute("GET", "/profiler/heap",
            "Live heap samples and current heap snapshot", false, false, false));
        routes.add(apiRoute("GET", "/profiler/gc",
            "Recent GC events and pause summary", false, false, false));
        routes.add(apiRoute("GET", "/profiler/cpu",
            "Live process, system, and profiler-thread CPU samples", false, false, false));
        routes.add(apiRoute("GET", "/profiler/endpoints",
            "Aggregated Spring MVC endpoint latency and CPU statistics", false, false, false));
        routes.add(apiRoute("GET", "/profiler/beans",
            "Top Spring beans by estimated memory", true, false, false));
        routes.add(apiRoute("GET", "/profiler/history/heap",
            "Persisted heap samples in a time range", false, true, false));
        routes.add(apiRoute("GET", "/profiler/history/gc",
            "Persisted GC events in a time range", false, true, false));
        routes.add(apiRoute("GET", "/profiler/history/cpu",
            "Persisted CPU samples in a time range", false, true, false));
        routes.add(apiRoute("GET", "/profiler/leaks",
            "Active leak warnings from the latest aggregation cycle", false, false, false));
        routes.add(apiRoute("GET", "/profiler/traces",
            "Recent request trace summaries", false, false, true));
        routes.add(apiRoute("GET", "/profiler/trace/{id}",
            "Full method call tree and sampled line hotspots for one request trace", true, false, true));
        routes.add(apiRoute("GET", "/profiler/source",
            "Source window for one configured application line hotspot", true, false, true));
        routes.add(apiRoute("GET", "/profiler/flamegraph",
            "Sampling profiler flamegraph tree", true, false, false));
        routes.add(apiRoute("GET", "/profiler/dashboard",
            "Self-contained HTML dashboard", false, false, false));
        catalog.put("routeCount", routes.size());
        catalog.put("routes", routes);
        return catalog;
    }

    private Map<String, Object> apiResponse(String resource) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("apiVersion", API_VERSION);
        response.put("generatedAtMs", System.currentTimeMillis());
        response.put("resource", resource);
        return response;
    }

    private Map<String, Object> apiError(String resource, String message) {
        Map<String, Object> response = apiResponse(resource);
        response.put("error", message);
        return response;
    }

    private Map<String, Object> sourceResponse(SourceCodeService.SourceLookup lookup) {
        Map<String, Object> response = apiResponse("source");
        response.put("sourceAvailable", lookup.sourceAvailable());
        response.put("className", lookup.className());
        response.put("fileName", lookup.fileName());
        response.put("sourcePath", lookup.sourcePath());
        response.put("requestedLine", lookup.requestedLine());
        response.put("startLine", lookup.startLine());
        response.put("endLine", lookup.endLine());
        response.put("totalLines", lookup.totalLines());
        response.put("lines", lookup.lines());
        if (lookup.message() != null && !lookup.message().isBlank()) {
            response.put("message", lookup.message());
        }
        return response;
    }

    private static int queryLine(Context ctx) {
        String line = ctx.queryParam("line");
        if (line == null || line.isBlank()) return 0;
        try {
            return Integer.parseInt(line);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private Map<String, Object> historyBadRequest(String resource, String message,
                                                  String example) {
        Map<String, Object> response = apiError(resource, message);
        response.put("example", example);
        return response;
    }

    private Map<String, Object> apiCapabilities() {
        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("persistenceConfigured", config.isPersistenceEnabled());
        capabilities.put("persistenceAvailable", registry.getSqliteRepository() != null);
        capabilities.put("persistenceHistoryLimit", SqliteRepository.MAX_QUERY_ROWS);
        capabilities.put("persistenceRetentionDays", config.getPersistenceRetentionDays());
        capabilities.put("selfMonitoring", true);
        capabilities.put("adaptiveSampling", config.isAdaptiveSamplingEnabled());
        capabilities.put("cpuMonitoring", true);
        capabilities.put("cpuSamplingIntervalMs", config.getCpuSamplingIntervalMs());
        capabilities.put("traceConfigured", config.isTraceEnabled()
            && !config.getTracePackages().isBlank());
        capabilities.put("tracePackagesConfigured", !config.getTracePackages().isBlank());
        capabilities.put("allocationDetail", config.isAllocDetailEnabled());
        capabilities.put("lineProfilingConfigured", config.isLineProfilingConfigured());
        capabilities.put("lineProfilingEnabled", config.isLineProfilingActive());
        capabilities.put("linePackagesConfigured", !config.getLinePackages().isBlank());
        capabilities.put("lineAllocationDetail", config.isLineAllocationProfilingActive());
        capabilities.put("lineSampleIntervalMs", config.getLineSampleIntervalMs());
        capabilities.put("lineMaxSamplesPerTrace", config.getLineMaxSamplesPerTrace());
        capabilities.put("lineMaxLinesPerTrace", config.getLineMaxLinesPerTrace());
        capabilities.put("lineMaxTracePayloadBytes", config.getLineMaxTracePayloadBytes());
        capabilities.put("lineHotspots", config.isLineProfilingActive());
        capabilities.put("sourceViewConfigured", config.isSourceViewConfigured());
        capabilities.put("sourceViewEnabled", config.isSourceViewActive());
        capabilities.put("sourceRootCount",
            SourceCodeService.rootCount(config.getSourceRoots()));
        capabilities.put("sourceContextLines", config.getSourceContextLines());
        capabilities.put("samplingProfilerConfigured", config.isSamplingProfilerEnabled());
        capabilities.put("samplingProfilerAvailable", registry.getStackSampler() != null);
        capabilities.put("corsEnabled", config.isCorsEnabled());
        capabilities.put("authEnabled", config.isAuthEnabled());
        return capabilities;
    }

    private Map<String, Object> apiLinks() {
        Map<String, Object> links = new LinkedHashMap<>();
        links.put("api", "/profiler/api");
        links.put("status", "/profiler/status");
        links.put("dashboard", "/profiler/dashboard");
        links.put("heap", "/profiler/heap");
        links.put("gc", "/profiler/gc");
        links.put("cpu", "/profiler/cpu");
        links.put("endpoints", "/profiler/endpoints");
        links.put("beans", "/profiler/beans");
        links.put("historyHeap", "/profiler/history/heap");
        links.put("historyGc", "/profiler/history/gc");
        links.put("historyCpu", "/profiler/history/cpu");
        links.put("leaks", "/profiler/leaks");
        links.put("traces", "/profiler/traces");
        links.put("source", "/profiler/source");
        links.put("flamegraph", "/profiler/flamegraph");
        return links;
    }

    private static Map<String, Object> apiRoute(String method, String path,
                                                String description,
                                                boolean sensitive,
                                                boolean requiresPersistence,
                                                boolean requiresTracing) {
        Map<String, Object> route = new LinkedHashMap<>();
        route.put("method", method);
        route.put("path", path);
        route.put("description", description);
        route.put("sensitive", sensitive);
        route.put("requiresPersistence", requiresPersistence);
        route.put("requiresTracing", requiresTracing);
        return route;
    }

    private void registerPreflightRoutes(JavalinConfig cfg) {
        cfg.routes.options("/profiler/api", this::handlePreflight);
        cfg.routes.options("/profiler/heap", this::handlePreflight);
        cfg.routes.options("/profiler/gc", this::handlePreflight);
        cfg.routes.options("/profiler/cpu", this::handlePreflight);
        cfg.routes.options("/profiler/status", this::handlePreflight);
        cfg.routes.options("/profiler/summary", this::handlePreflight);
        cfg.routes.options("/profiler/dashboard", this::handlePreflight);
        cfg.routes.options("/profiler/endpoints", this::handlePreflight);
        cfg.routes.options("/profiler/beans", this::handlePreflight);
        cfg.routes.options("/profiler/history/heap", this::handlePreflight);
        cfg.routes.options("/profiler/history/gc", this::handlePreflight);
        cfg.routes.options("/profiler/history/cpu", this::handlePreflight);
        cfg.routes.options("/profiler/leaks", this::handlePreflight);
        cfg.routes.options("/profiler/traces", this::handlePreflight);
        cfg.routes.options("/profiler/trace/{id}", this::handlePreflight);
        cfg.routes.options("/profiler/source", this::handlePreflight);
        cfg.routes.options("/profiler/flamegraph", this::handlePreflight);
    }

    private void handlePreflight(Context ctx) {
        registry.selfMetrics().recordProfilerHttpRequest();
        applyCors(ctx);
        String origin = ctx.header("Origin");
        if (origin != null && config.isCorsEnabled() && isOriginAllowed(origin)) {
            ctx.status(204);
            return;
        }
        ctx.status(403).json(Map.of("error", "CORS origin not allowed"));
    }

    private boolean authorize(Context ctx) {
        registry.selfMetrics().recordProfilerHttpRequest();
        applyCors(ctx);

        if (!config.isAuthEnabled()) {
            return true;
        }

        String expected = config.getAuthToken();
        String authorization = ctx.header("Authorization");
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                && constantTimeEquals(expected, authorization.substring(7).trim())) {
            return true;
        }

        if (constantTimeEquals(expected, ctx.queryParam("token"))) {
            return true;
        }

        ctx.header("WWW-Authenticate", "Bearer");
        registry.selfMetrics().incrementProfilerHttpAuthFailures();
        ctx.status(401).json(Map.of("error", "Unauthorized"));
        return false;
    }

    private void applyCors(Context ctx) {
        String origin = ctx.header("Origin");
        if (origin == null || origin.isBlank()
                || !config.isCorsEnabled()
                || !isOriginAllowed(origin)) {
            return;
        }

        ctx.header("Access-Control-Allow-Origin", origin);
        ctx.header("Vary", "Origin");
        ctx.header("Access-Control-Allow-Headers", "Authorization, Content-Type");
        ctx.header("Access-Control-Allow-Methods", "GET, OPTIONS");
    }

    private boolean isOriginAllowed(String origin) {
        for (String allowed : config.getCorsAllowedOrigins().split(",")) {
            if (origin.equals(allowed.trim())) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Object> selfMonitoringSummary(AgentStatus snap, long nowMs) {
        List<String> issues = new ArrayList<>();
        addIssue(issues, snap.droppedSamples(), "heap-sample-drops");
        addIssue(issues, snap.droppedGcEvents(), "gc-event-drops");
        addIssue(issues, snap.droppedEndpointSamples(), "endpoint-sample-drops");
        addIssue(issues, snap.droppedCpuSamples(), "cpu-sample-drops");
        addIssue(issues, snap.droppedTraces(), "trace-drops");
        addIssue(issues, snap.droppedPersistenceSamples(), "persistence-queue-drops");
        addIssue(issues, snap.samplingDelays(), "sampling-delays");
        addIssue(issues, snap.aggregationErrors(), "aggregation-errors");
        addIssue(issues, snap.persistenceFlushFailures(), "persistence-flush-failures");
        addIssue(issues, snap.persistencePurgeFailures(), "persistence-purge-failures");
        addIssue(issues, snap.profilerHttpAuthFailures(), "profiler-http-auth-failures");

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("selfMonitoringStatus", selfMonitoringStatus(snap, issues));
        summary.put("selfMonitoringIssues", issues);
        summary.put("selfMonitoringIssueCount", issues.size());
        summary.put("totalDroppedSamples", snap.totalDroppedSamples());
        summary.put("totalInternalErrors", snap.totalInternalErrors());
        summary.put("lastSampleAgeMs", ageMs(nowMs, snap.lastSampleTimestampMs()));
        summary.put("lastCpuSampleAgeMs", ageMs(nowMs, snap.lastCpuSampleTimestampMs()));
        summary.put("lastAggregationAgeMs", ageMs(nowMs, snap.lastAggregationTimestampMs()));
        summary.put("lastPersistenceFlushAgeMs",
            ageMs(nowMs, snap.lastPersistenceFlushTimestampMs()));
        summary.put("lastProfilerHttpRequestAgeMs",
            ageMs(nowMs, snap.lastProfilerHttpRequestTimestampMs()));
        return summary;
    }

    private static void addIssue(List<String> issues, long count, String issue) {
        if (count > 0L) {
            issues.add(issue);
        }
    }

    private static String selfMonitoringStatus(AgentStatus snap, List<String> issues) {
        if (snap.totalInternalErrors() > 0L) {
            return "error";
        }
        return issues.isEmpty() ? "ok" : "warn";
    }

    private static long ageMs(long nowMs, long timestampMs) {
        return timestampMs > 0L ? Math.max(0L, nowMs - timestampMs) : -1L;
    }

    private static boolean constantTimeEquals(String expected, String candidate) {
        if (expected == null || candidate == null) return false;
        return MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8),
            candidate.getBytes(StandardCharsets.UTF_8));
    }

    private boolean canExposeSensitiveDetails() {
        return config.isAuthEnabled() || config.isLocalOnlyHttpBind();
    }

    private static Map<String, Object> cpuSnapshotMap(CpuSnapshot snapshot) {
        return Map.of(
            "timestampMs", snapshot.timestampMs(),
            "processCpuLoadPercent", snapshot.processCpuLoadPercent(),
            "systemCpuLoadPercent", snapshot.systemCpuLoadPercent(),
            "processCpuTimeMs", snapshot.processCpuTimeMs(),
            "agentThreadCpuTimeMs", snapshot.agentThreadCpuTimeMs(),
            "agentThreadCpuLoadPercent", snapshot.agentThreadCpuLoadPercent(),
            "availableProcessors", snapshot.availableProcessors(),
            "processCpuSupported", snapshot.processCpuSupported(),
            "systemCpuSupported", snapshot.systemCpuSupported(),
            "agentThreadCpuSupported", snapshot.agentThreadCpuSupported());
    }

    private static List<Map<String, Object>> redactedBeans(List<BeanMemoryInfo> beans) {
        List<Map<String, Object>> redacted = new ArrayList<>();
        for (BeanMemoryInfo bean : beans) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("beanName", "(redacted)");
            row.put("className", "(redacted)");
            row.put("scope", bean.scope());
            row.put("estimatedBytes", bean.estimatedBytes());
            redacted.add(row);
        }
        return redacted;
    }

    private static Map<String, Object> redactedTrace(RequestTrace trace) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("traceId", trace.traceId());
        response.put("method", trace.method());
        response.put("path", trace.path());
        response.put("timestampMs", trace.timestampMs());
        response.put("totalWallNs", trace.totalWallNs());
        response.put("totalCpuNs", trace.totalCpuNs());
        response.put("totalAllocBytes", trace.totalAllocBytes());
        response.put("capturedSpans", trace.capturedSpans());
        response.put("droppedSpans", trace.droppedSpans());
        response.put("truncated", trace.truncated());
        response.put("depthLimitExceeded", trace.depthLimitExceeded());
        response.put("spanLimitExceeded", trace.spanLimitExceeded());
        response.put("lineSampleCount", trace.lineSampleCount());
        response.put("lineHotspotCount", trace.lineHotspots().size());
        response.put("lineAllocationCount", lineAllocationCount(trace));
        response.put("lineAllocatedBytes", lineAllocatedBytes(trace));
        response.put("droppedLineSamples", trace.droppedLineSamples());
        response.put("droppedLineHotspots", trace.droppedLineHotspots());
        response.put("lineHotspotsTruncated", trace.lineHotspotsTruncated());
        response.put("lineSampleIntervalMs", trace.lineSampleIntervalMs());
        response.put("redacted", true);
        response.put("message", REDACTION_MESSAGE);
        return response;
    }

    private static long lineAllocationCount(RequestTrace trace) {
        long total = 0L;
        for (var hotspot : trace.lineHotspots()) {
            total += hotspot.allocationCount();
        }
        return total;
    }

    private static long lineAllocatedBytes(RequestTrace trace) {
        long total = 0L;
        for (var hotspot : trace.lineHotspots()) {
            total += hotspot.allocatedBytes();
        }
        return total;
    }

    private static Map<String, Object> redactedFlamegraph(long samples) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("enabled", true);
        response.put("redacted", true);
        response.put("frame", "root");
        response.put("samples", samples);
        response.put("children", Map.of());
        response.put("message", REDACTION_MESSAGE);
        return response;
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
            <!DOCTYPE html><html><head><title>RequestLens</title></head>
            <body style="font-family:sans-serif">
              <h1>RequestLens</h1>
              <p>The dashboard resource was not bundled. Raw JSON endpoints:</p>
              <ul>
                <li><a href="/profiler/api">/profiler/api</a></li>
                <li><a href="/profiler/status">/profiler/status</a></li>
                <li><a href="/profiler/heap">/profiler/heap</a></li>
                <li><a href="/profiler/gc">/profiler/gc</a></li>
                <li><a href="/profiler/cpu">/profiler/cpu</a></li>
                <li><a href="/profiler/endpoints">/profiler/endpoints</a></li>
                <li><a href="/profiler/beans">/profiler/beans</a></li>
                <li><a href="/profiler/leaks">/profiler/leaks</a></li>
              </ul>
            </body></html>
            """;
    }
}
