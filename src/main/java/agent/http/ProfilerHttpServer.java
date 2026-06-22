package agent.http;

import agent.analysis.RequestInvestigationAnalyzer;
import agent.analysis.TraceInsightAnalyzer;
import agent.core.AgentConfig;
import agent.core.CollectorRegistry;
import agent.core.JarPackageDiscovery;
import agent.collector.jfr.JfrEventRecorder;
import agent.collector.logging.LogCaptureSupport;
import agent.model.AgentStatus;
import agent.model.BeanMemoryInfo;
import agent.model.CpuSnapshot;
import agent.model.EndpointStats;
import agent.model.FlameNode;
import agent.model.GcEvent;
import agent.model.HeapSnapshot;
import agent.model.LeakWarning;
import agent.model.MethodSpan;
import agent.model.RequestTrace;
import agent.persistence.HistoryQueryResult;
import agent.persistence.PersistenceException;
import agent.persistence.PersistenceWriter;
import agent.persistence.SqliteRepository;
import agent.profiling.LineProfilingSupport;
import agent.profiling.StackSampler;
import agent.profiling.ThreadMetrics;
import agent.profiling.asyncprofiler.AsyncProfilerController;
import agent.sampling.SamplingStateHolder;

import io.javalin.Javalin;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Embedded HTTP server that exposes profiling data as JSON.
 *
 * Uses Javalin â€” a lightweight HTTP framework that starts in under 100ms
 * and has no dependency on Spring or any other web framework.
 *
 * Most routes are read-only GET endpoints. Explicit profiler control routes
 * use POST. The server runs on a daemon thread pool so it does not prevent JVM
 * shutdown.
 */
public final class ProfilerHttpServer {

    private static final Logger log = Logger.getLogger(ProfilerHttpServer.class.getName());
    private static final String API_VERSION = "1";
    private static final int DEFAULT_LOG_RESPONSE_LIMIT = 200;
    private static final int MAX_LOG_RESPONSE_LIMIT = 1000;
    private static final int DEFAULT_JFR_RESPONSE_LIMIT = 200;
    private static final int MAX_JFR_RESPONSE_LIMIT = 1000;
    private static final int DEFAULT_ASYNC_STACK_LIMIT = 100;
    private static final int MAX_ASYNC_STACK_LIMIT = 1000;
    static final int DEFAULT_INVESTIGATION_WINDOW_MS = 5_000;
    private static final int MAX_INVESTIGATION_WINDOW_MS = 60_000;
    private static final int DEFAULT_INVESTIGATION_EVENT_LIMIT = 40;
    private static final int MAX_INVESTIGATION_EVENT_LIMIT = 200;
    private static final int DEFAULT_INVESTIGATION_STACK_LIMIT = 12;
    private static final int MAX_INVESTIGATION_STACK_LIMIT = 100;

    private final CollectorRegistry registry;
    private final AgentConfig       config;
    private final ProfilerHttpSecurity security;
    private final ProfilerApiCatalog apiCatalog;
    private final SourceCodeService sourceCodeService = new SourceCodeService();
    private final ProfilerDashboardAssets dashboardAssets = new ProfilerDashboardAssets();
    private final JarPackageDiscovery.DiscoveryResult runtimePackageDiscovery;

    static final String REDACTION_MESSAGE =
        "Sensitive bean/class details require profiler.auth.token or loopback-only binding.";

    public ProfilerHttpServer(CollectorRegistry registry, AgentConfig config) {
        this.registry = registry;
        this.config   = config;
        this.security = new ProfilerHttpSecurity(registry, config);
        this.runtimePackageDiscovery = JarPackageDiscovery.discoverRuntime();
        this.apiCatalog = new ProfilerApiCatalog(registry, config, security);
    }

    /**
     * Starts the HTTP server. Returns immediately.
     * The server runs on Javalin's internal daemon thread pool.
     */
    public void start() {
        // Javalin 7 requires routes (and most config) to be registered upfront
        // inside the create() config block, before the server starts.
        Javalin app = Javalin.create(cfg -> {
            // Suppress Javalin's startup banner â€” moved to cfg.startup in Javalin 7.
            cfg.startup.showJavalinBanner = false;
            registerRoutes(cfg);
        });

        // start(host, port) is non-blocking; the server runs on daemon threads.
        app.start(config.getHttpHost(), config.getHttpPort());

        String baseUrl = "http://" + config.getHttpHost() + ":" + config.getHttpPort();
        log.info("ProfilerHttpServer started on " + baseUrl);
        log.info("Dashboard: " + baseUrl + "/profiler/dashboard");
    }

    Javalin createApp() {
        return Javalin.create(cfg -> {
            cfg.startup.showJavalinBanner = false;
            registerRoutes(cfg);
        });
    }

    private void registerRoutes(JavalinConfig cfg) {
        registerPreflightRoutes(cfg);

        cfg.routes.get("/profiler/api", ctx -> {
            if (!authorize(ctx)) return;
            ctx.json(apiCatalog());
        });

        // â”€â”€ GET /profiler/heap â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        cfg.routes.get("/profiler/heap", ctx -> {
            if (!authorize(ctx)) return;
            // The ring buffer holds only the samples collected since the last
            // persistence drain (~last few seconds) â€” fine for a live chart.
            List<HeapSnapshot> samples = registry.heapBuffer().snapshot();

            // Build the response map â€” LinkedHashMap preserves insertion order
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

        // â”€â”€ GET /profiler/gc â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
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

        // -- GET /profiler/logs ------------------------------------------------
        cfg.routes.get("/profiler/logs", ctx -> {
            if (!authorize(ctx)) return;
            if (!canExposeSensitiveDetails()) {
                ctx.status(403).json(apiError("logs", REDACTION_MESSAGE));
                return;
            }
            int limit = boundedIntQuery(ctx, "limit", DEFAULT_LOG_RESPONSE_LIMIT,
                1, MAX_LOG_RESPONSE_LIMIT);
            String kind = ctx.queryParam("kind");
            ctx.json(ProfilerEventResponses.logsResponse(registry.logBuffer().snapshot(),
                registry.gcBuffer().snapshot(),
                config.isLogCaptureEnabled(),
                registry.logBuffer().capacity(),
                LogCaptureSupport.capturedCount(),
                LogCaptureSupport.droppedCount(),
                limit,
                kind));
        });

        // -- GET /profiler/jfr/events ----------------------------------------
        cfg.routes.get("/profiler/jfr/events", ctx -> {
            if (!authorize(ctx)) return;
            if (!canExposeSensitiveDetails()) {
                ctx.status(403).json(apiError("jfr.events", REDACTION_MESSAGE));
                return;
            }
            int limit = boundedIntQuery(ctx, "limit", DEFAULT_JFR_RESPONSE_LIMIT,
                1, MAX_JFR_RESPONSE_LIMIT);
            String category = ctx.queryParam("category");
            JfrEventRecorder recorder = registry.getJfrEventRecorder();
            ctx.json(ProfilerEventResponses.jfrEventsResponse(registry.jfrBuffer().snapshot(),
                config.isJfrEnabled(),
                JfrEventRecorder.isJfrAvailable(),
                recorder != null && recorder.isRunning(),
                registry.jfrBuffer().capacity(),
                recorder == null ? 0L : recorder.capturedCount(),
                recorder == null ? 0L : recorder.droppedCount(),
                recorder == null ? 0L : recorder.errorCount(),
                recorder == null ? List.of() : recorder.unsupportedEvents(),
                limit,
                category));
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

        // â”€â”€ GET /profiler/status â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
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
            status.put("configFileLoaded",      config.isConfigFileLoaded());
            status.put("configFileAutoDiscovered",
                config.isConfigFileAutoDiscovered());
            status.put("configFilePath",        exposeSensitiveDetails
                ? config.getConfigFilePath()
                : config.isConfigFileLoaded() ? "(redacted)" : "");
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
            status.put("internalErrors",        selfSnap.internalErrors());
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
                "logs", registry.logBuffer().capacity(),
                "jfr", registry.jfrBuffer().capacity(),
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

            // Phase 6 â€” deep profiling status
            status.put("cpuTimingSupported",   ThreadMetrics.cpuSupported());
            status.put("allocTimingSupported", ThreadMetrics.allocSupported());
            status.put("traceEnabled",         config.isTraceEnabled()
                                               && !config.getTracePackages().isBlank());
            status.put("tracePackages",        exposeSensitiveDetails
                                               ? config.getTracePackages()
                                               : "(redacted)");
            status.put("lineProfilingConfigured", config.isLineProfilingConfigured());
            status.put("lineProfilingEnabled", config.isLineProfilingActive());
            status.put("lineMode",              config.getLineMode());
            status.put("sampledLineProfilingEnabled",
                                               config.isSampledLineProfilingActive());
            status.put("deterministicLineProfilingEnabled",
                                               config.isDeterministicLineProfilingActive());
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
            status.put("debugSnapshotConfigured",
                config.isRequestDebugSnapshotConfigured());
            status.put("debugSnapshotEnabled",
                config.isRequestDebugSnapshotActive());
            status.put("debugSnapshotCaptureArgs",
                config.isDebugSnapshotCaptureArgs());
            status.put("debugSnapshotCaptureReturn",
                config.isDebugSnapshotCaptureReturn());
            status.put("debugMaxSnapshotsPerTrace",
                config.getDebugMaxSnapshotsPerTrace());
            status.put("debugMaxSnapshotsPerSpan",
                config.getDebugMaxSnapshotsPerSpan());
            status.put("debugMaxValueLength",
                config.getDebugMaxValueLength());
            JfrEventRecorder jfrRecorder = registry.getJfrEventRecorder();
            status.put("logCaptureConfigured", config.isLogCaptureEnabled());
            status.put("logCaptureEnabled", LogCaptureSupport.isEnabled());
            status.put("logMaxEvents", config.getLogMaxEvents());
            status.put("recentLogEventCount", registry.logBuffer().snapshot().size());
            status.put("capturedLogEvents", LogCaptureSupport.capturedCount());
            status.put("droppedLogEvents", LogCaptureSupport.droppedCount());
            status.put("jfrConfigured", config.isJfrEnabled());
            status.put("jfrAvailable", JfrEventRecorder.isJfrAvailable());
            status.put("jfrRunning", jfrRecorder != null && jfrRecorder.isRunning());
            status.put("jfrMaxEvents", config.getJfrMaxEvents());
            status.put("jfrThresholdMs", config.getJfrThresholdMs());
            status.put("recentJfrEventCount", registry.jfrBuffer().snapshot().size());
            status.put("capturedJfrEvents",
                jfrRecorder == null ? 0L : jfrRecorder.capturedCount());
            status.put("droppedJfrEvents",
                jfrRecorder == null ? 0L : jfrRecorder.droppedCount());
            status.put("jfrErrors",
                jfrRecorder == null ? 0L : jfrRecorder.errorCount());
            status.put("jfrUnsupportedEvents",
                jfrRecorder == null ? List.of() : jfrRecorder.unsupportedEvents());
            AsyncProfilerController asyncController = asyncProfilerController();
            AsyncProfilerController.Status asyncStatus = asyncController.status();
            status.put("asyncProfilerConfigured", asyncStatus.configured());
            status.put("asyncProfilerEmbedded", asyncStatus.embedded());
            status.put("asyncProfilerInitialized", asyncStatus.initialized());
            status.put("asyncProfilerAvailable", asyncStatus.available());
            status.put("asyncProfilerRunning", asyncStatus.running());
            status.put("asyncProfilerVersion", asyncStatus.version());
            status.put("asyncProfilerPlatform", asyncStatus.platform());
            status.put("asyncProfilerEvent", asyncStatus.event());
            status.put("asyncProfilerInterval", asyncStatus.interval());
            status.put("asyncProfilerDurationSeconds", asyncStatus.durationSeconds());
            status.put("asyncProfilerMaxCollapsedLines",
                asyncStatus.maxCollapsedLines());
            status.put("asyncProfilerStartCount", asyncStatus.startCount());
            status.put("asyncProfilerStopCount", asyncStatus.stopCount());
            status.put("asyncProfilerErrors", asyncStatus.errorCount());
            status.put("asyncProfilerSampleCount", asyncStatus.sampleCount());
            status.put("asyncProfilerStackCount", asyncStatus.stackCount());
            status.put("asyncProfilerMessage", asyncStatus.message());
            status.put("asyncProfilerError", asyncStatus.error());
            status.put("lineActiveRequests",   LineProfilingSupport.activeSessionCount());
            status.put("lineCompletedRequests", LineProfilingSupport.completedSessionCount());
            status.put("samplingProfiler",     registry.getStackSampler() != null);
            status.put("recentTraceCount",     registry.recentTraces().size());
            status.put("instrumentationDiagnostics",
                registry.instrumentationDiagnostics().snapshot().toMap(exposeSensitiveDetails));
            status.put("packageDiscovery",
                runtimePackageDiscovery.toMap(exposeSensitiveDetails));
            status.put("links",                apiLinks());

            ctx.json(status);
        });

        // â”€â”€ GET /profiler/summary â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
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

        // â”€â”€ GET /profiler/dashboard â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        // Serves the bundled dashboard shell from the agent JAR at
        // /dashboard/index.html. The HTML is loaded once from the classpath and
        // cached; if the resource is missing we fall back to a minimal page so
        // the route never 500s.
        cfg.routes.get("/profiler/dashboard", ctx -> {
            if (!authorize(ctx)) return;
            ctx.contentType("text/html");
            ctx.result(dashboardAssets.html());
        });

        cfg.routes.get("/profiler/dashboard.js", ctx -> {
            if (!authorize(ctx)) return;
            ctx.contentType("application/javascript; charset=utf-8");
            ctx.result(dashboardAssets.script());
        });

        // â”€â”€ GET /profiler/endpoints â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
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

        // â”€â”€ GET /profiler/beans â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
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

        // â”€â”€ GET /profiler/history/heap â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        // Reads persisted heap samples from SQLite within a [from, to] time range.
        // Survives JVM restarts (data lives on disk). Both query params required.
        cfg.routes.get("/profiler/history/heap", ctx -> {
            if (!authorize(ctx)) return;
            SqliteRepository repo = registry.getSqliteRepository();
            if (repo == null) {
                // Persistence disabled (or failed to start) â€” nothing to query.
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

        // â”€â”€ GET /profiler/history/gc â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
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

        // â”€â”€ GET /profiler/leaks â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        // The leak warnings active as of the most recent aggregation cycle.
        cfg.routes.get("/profiler/leaks", ctx -> {
            if (!authorize(ctx)) return;
            List<LeakWarning> warnings = registry.getActiveLeakWarnings();
            Map<String, Object> response = apiResponse("leaks");
            response.put("activeWarnings", warnings.size());
            response.put("warnings",       warnings);
            ctx.json(response);
        });

        // â”€â”€ GET /profiler/traces (Phase 6) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
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
                s.put("externalSpanCount", externalSpanCount(t.root()));
                s.put("sqlSpanCount", spanKindCount(t.root(), "sql"));
                s.put("httpSpanCount", spanKindCount(t.root(), "http"));
                s.put("debugSnapshotCount", debugSnapshotCount(t.root()));
                s.put("droppedDebugSnapshots", droppedDebugSnapshotCount(t.root()));
                s.put("lineSampleCount", t.lineSampleCount());
                s.put("lineHotspotCount", t.lineHotspots().size());
                s.put("deterministicLineCount", deterministicLineCount(t.root()));
                s.put("deterministicLineSelfWallNs",
                    deterministicLineSelfWallNs(t.root()));
                s.put("deterministicLineSelfCpuNs",
                    deterministicLineSelfCpuNs(t.root()));
                s.put("deterministicLineAllocationCount",
                    deterministicLineAllocationCount(t.root()));
                s.put("deterministicLineAllocatedBytes",
                    deterministicLineAllocatedBytes(t.root()));
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

        // â”€â”€ GET /profiler/trace/{id} (Phase 6) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        // The full method call tree for one trace.
        cfg.routes.get("/profiler/trace/{id}", ctx -> {
            if (!authorize(ctx)) return;
            String id = ctx.pathParam("id");
            RequestTrace match = null;
            List<RequestTrace> traces = registry.recentTraces();
            for (RequestTrace t : traces) {
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
            ctx.json(traceDetails(match, traces));
        });

        // -- GET /profiler/investigate ----------------------------------------
        // Correlates one request trace with nearby JFR, log, and native profile
        // evidence. Trace data is exact; the rest is time-window/process-wide.
        cfg.routes.get("/profiler/investigate", ctx -> {
            if (!authorize(ctx)) return;
            if (!canExposeSensitiveDetails()) {
                ctx.status(403).json(apiError("investigation", REDACTION_MESSAGE));
                return;
            }

            List<RequestTrace> traces = registry.recentTraces();
            String traceId = ctx.queryParam("traceId");
            RequestTrace trace = investigationTrace(traceId, traces);
            Map<String, Object> response = apiResponse("investigation");
            response.put("redacted", false);
            if (trace == null) {
                boolean requestedSpecificTrace = traceId != null && !traceId.isBlank();
                response.put("available", false);
                response.put("message", requestedSpecificTrace
                    ? "No trace with id " + traceId + " (it may have aged out of the recent buffer)"
                    : "No request traces are available yet.");
                if (requestedSpecificTrace) {
                    ctx.status(404);
                }
                ctx.json(response);
                return;
            }

            int windowMs = boundedIntQuery(ctx, "windowMs",
                DEFAULT_INVESTIGATION_WINDOW_MS, 0, MAX_INVESTIGATION_WINDOW_MS);
            int eventLimit = boundedIntQuery(ctx, "eventLimit",
                DEFAULT_INVESTIGATION_EVENT_LIMIT, 1, MAX_INVESTIGATION_EVENT_LIMIT);
            int stackLimit = boundedIntQuery(ctx, "stackLimit",
                DEFAULT_INVESTIGATION_STACK_LIMIT, 1, MAX_INVESTIGATION_STACK_LIMIT);
            AsyncProfilerController asyncController = asyncProfilerController();
            RequestInvestigationAnalyzer.RequestInvestigation investigation =
                RequestInvestigationAnalyzer.investigate(trace, traces,
                    registry.jfrBuffer().snapshot(), registry.logBuffer().snapshot(),
                    asyncController.status(), asyncController.snapshot(true),
                    windowMs, eventLimit, stackLimit, System.currentTimeMillis());

            response.put("available", true);
            response.put("traceId", trace.traceId());
            response.put("selectedBy", traceId == null || traceId.isBlank()
                ? "slowest-recent-trace" : "traceId");
            response.put("windowMs", windowMs);
            response.put("eventLimit", eventLimit);
            response.put("stackLimit", stackLimit);
            response.put("investigation", investigation);
            ctx.json(response);
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

        // -- GET /profiler/package-discovery ----------------------------------
        // Suggests trace.packages/line.packages from the runtime jar or a jar query.
        cfg.routes.get("/profiler/package-discovery", ctx -> {
            if (!authorize(ctx)) return;
            if (!canExposeSensitiveDetails()) {
                ctx.status(403).json(apiError("package-discovery", REDACTION_MESSAGE));
                return;
            }
            String jar = ctx.queryParam("jar");
            JarPackageDiscovery.DiscoveryResult result =
                jar == null || jar.isBlank()
                    ? runtimePackageDiscovery
                    : JarPackageDiscovery.discover(Path.of(jar));
            Map<String, Object> response = apiResponse("package-discovery");
            response.putAll(result.toMap(true));
            ctx.status(result.available() ? 200 : 404).json(response);
        });

        // â”€â”€ GET /profiler/flamegraph (Phase 6) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        // The folded sampling-profiler tree (samples per frame).
        cfg.routes.get("/profiler/flamegraph", ctx -> {
            if (!authorize(ctx)) return;
            ProfilerFlamegraphResponses.Options options =
                ProfilerFlamegraphResponses.options(ctx);
            StackSampler sampler = registry.getStackSampler();
            if (sampler == null) {
                ctx.json(Map.of("enabled", false, "frame", "root", "samples", 0,
                    "children", Map.of()));
                return;
            }
            FlameNode snapshot = sampler.snapshot();
            if (!canExposeSensitiveDetails()) {
                ctx.json(ProfilerFlamegraphResponses.redacted(snapshot.samples, options));
                return;
            }
            ctx.json(ProfilerFlamegraphResponses.response(snapshot, options));
        });

        // -- Embedded async-profiler controls/readouts ------------------------
        cfg.routes.get("/profiler/async/status", ctx -> {
            if (!authorize(ctx)) return;
            if (!canExposeSensitiveDetails()) {
                ctx.status(403).json(apiError("async.status", REDACTION_MESSAGE));
                return;
            }
            ctx.json(asyncProfilerStatusResponse(asyncProfilerController(),
                "async.status"));
        });

        cfg.routes.post("/profiler/async/start", ctx -> {
            if (!authorize(ctx)) return;
            if (!canExposeSensitiveDetails()) {
                ctx.status(403).json(apiError("async.start", REDACTION_MESSAGE));
                return;
            }
            AsyncProfilerController controller = asyncProfilerController();
            long interval = boundedLongQuery(ctx, "interval",
                config.getAsyncProfilerInterval(), 1_000L, 1_000_000_000L);
            int durationSeconds = boundedIntQuery(ctx, "durationSeconds",
                config.getAsyncProfilerDurationSeconds(), 1,
                config.getAsyncProfilerDurationSeconds());
            AsyncProfilerController.CommandResult result = controller.start(
                ctx.queryParam("event"), interval, durationSeconds);
            Map<String, Object> response = asyncProfilerStatusResponse(controller,
                "async.start");
            response.put("commandSuccess", result.success());
            response.put("commandMessage", result.message());
            ctx.status(result.statusCode()).json(response);
        });

        cfg.routes.post("/profiler/async/stop", ctx -> {
            if (!authorize(ctx)) return;
            if (!canExposeSensitiveDetails()) {
                ctx.status(403).json(apiError("async.stop", REDACTION_MESSAGE));
                return;
            }
            AsyncProfilerController controller = asyncProfilerController();
            AsyncProfilerController.CommandResult result = controller.stop();
            Map<String, Object> response = asyncProfilerStatusResponse(controller,
                "async.stop");
            response.put("commandSuccess", result.success());
            response.put("commandMessage", result.message());
            ctx.status(result.statusCode()).json(response);
        });

        cfg.routes.get("/profiler/async/collapsed", ctx -> {
            if (!authorize(ctx)) return;
            if (!canExposeSensitiveDetails()) {
                ctx.status(403).json(apiError("async.collapsed", REDACTION_MESSAGE));
                return;
            }
            int limit = boundedIntQuery(ctx, "limit", DEFAULT_ASYNC_STACK_LIMIT,
                1, MAX_ASYNC_STACK_LIMIT);
            ctx.json(asyncCollapsedResponse(asyncProfilerController(), limit));
        });

        cfg.routes.get("/profiler/async/flamegraph", ctx -> {
            if (!authorize(ctx)) return;
            if (!canExposeSensitiveDetails()) {
                ctx.status(403).json(apiError("async.flamegraph", REDACTION_MESSAGE));
                return;
            }
            AsyncProfilerController controller = asyncProfilerController();
            AsyncProfilerController.CollapsedSnapshot snapshot =
                controller.snapshot(true);
            Map<String, Object> response = ProfilerFlamegraphResponses.response(snapshot.root(),
                ProfilerFlamegraphResponses.options(ctx));
            response.put("resource", "async.flamegraph");
            response.put("backend", "async-profiler");
            response.put("stackCount", snapshot.stackCount());
            response.put("truncated", snapshot.truncated());
            response.put("skippedLines", snapshot.skippedLines());
            response.put("status", asyncProfilerStatusResponse(controller,
                "async.status"));
            ctx.json(response);
        });
    }

    private Map<String, Object> apiCatalog() {
        return apiCatalog.catalog();
    }

    private Map<String, Object> apiCapabilities() {
        return apiCatalog.capabilities();
    }

    private Map<String, Object> apiLinks() {
        return apiCatalog.links();
    }

    private Map<String, Object> apiResponse(String resource) {
        return apiResponseStatic(resource);
    }

    static Map<String, Object> apiResponseStatic(String resource) {
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

    private AsyncProfilerController asyncProfilerController() {
        AsyncProfilerController controller = registry.getAsyncProfilerController();
        if (controller == null) {
            controller = new AsyncProfilerController(config);
            registry.setAsyncProfilerController(controller);
        }
        return controller;
    }

    private Map<String, Object> asyncProfilerStatusResponse(
            AsyncProfilerController controller, String resource) {
        AsyncProfilerController.Status status = controller.status();
        Map<String, Object> response = apiResponse(resource);
        response.put("configured", status.configured());
        response.put("embedded", status.embedded());
        response.put("initialized", status.initialized());
        response.put("available", status.available());
        response.put("running", status.running());
        response.put("version", status.version());
        response.put("platform", status.platform());
        response.put("event", status.event());
        response.put("interval", status.interval());
        response.put("durationSeconds", status.durationSeconds());
        response.put("maxCollapsedLines", status.maxCollapsedLines());
        response.put("libPath", status.libPath() == null || status.libPath().isBlank()
            ? "(embedded)" : status.libPath());
        response.put("startedAtMs", status.startedAtMs());
        response.put("stoppedAtMs", status.stoppedAtMs());
        response.put("activeDurationMs", status.activeDurationMs());
        response.put("startCount", status.startCount());
        response.put("stopCount", status.stopCount());
        response.put("errorCount", status.errorCount());
        response.put("sampleCount", status.sampleCount());
        response.put("stackCount", status.stackCount());
        response.put("flamegraphSamples", status.flamegraphSamples());
        response.put("truncated", status.truncated());
        response.put("skippedLines", status.skippedLines());
        response.put("message", status.message());
        response.put("error", status.error());
        return response;
    }

    private Map<String, Object> asyncCollapsedResponse(
            AsyncProfilerController controller, int limit) {
        AsyncProfilerController.CollapsedSnapshot snapshot = controller.snapshot(true);
        Map<String, Object> response = apiResponse("async.collapsed");
        response.put("status", asyncProfilerStatusResponse(controller, "async.status"));
        response.put("stackCount", snapshot.stackCount());
        response.put("sampleCount", snapshot.root().samples);
        response.put("truncated", snapshot.truncated());
        response.put("skippedLines", snapshot.skippedLines());
        response.put("limit", limit);

        List<Map<String, Object>> stacks = new ArrayList<>();
        int emitted = 0;
        for (AsyncProfilerController.CollapsedStack stack : snapshot.stacks()) {
            if (emitted++ >= limit) break;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stack", stack.stack());
            row.put("samples", stack.samples());
            row.put("frames", stack.frames());
            stacks.add(row);
        }
        response.put("returnedStackCount", stacks.size());
        response.put("limited", snapshot.stacks().size() > stacks.size());
        response.put("stacks", stacks);
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

    private void registerPreflightRoutes(JavalinConfig cfg) {
        security.registerPreflightRoutes(cfg);
    }

    private boolean authorize(Context ctx) {
        return security.authorize(ctx);
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
        addIssue(issues, snap.internalErrors(), "internal-errors");
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

    private boolean canExposeSensitiveDetails() {
        return security.canExposeSensitiveDetails();
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

    private static Map<String, Object> traceDetails(RequestTrace trace,
                                                    List<RequestTrace> recentTraces) {
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
        response.put("externalSpanCount", externalSpanCount(trace.root()));
        response.put("sqlSpanCount", spanKindCount(trace.root(), "sql"));
        response.put("httpSpanCount", spanKindCount(trace.root(), "http"));
        response.put("debugSnapshotCount", debugSnapshotCount(trace.root()));
        response.put("droppedDebugSnapshots", droppedDebugSnapshotCount(trace.root()));
        response.put("root", trace.root());
        response.put("lineHotspots", trace.lineHotspots());
        response.put("lineSampleCount", trace.lineSampleCount());
        response.put("lineHotspotCount", trace.lineHotspots().size());
        response.put("deterministicLineCount", deterministicLineCount(trace.root()));
        response.put("deterministicLineSelfWallNs",
            deterministicLineSelfWallNs(trace.root()));
        response.put("deterministicLineSelfCpuNs",
            deterministicLineSelfCpuNs(trace.root()));
        response.put("deterministicLineAllocationCount",
            deterministicLineAllocationCount(trace.root()));
        response.put("deterministicLineAllocatedBytes",
            deterministicLineAllocatedBytes(trace.root()));
        response.put("lineAllocationCount", lineAllocationCount(trace));
        response.put("lineAllocatedBytes", lineAllocatedBytes(trace));
        response.put("droppedLineSamples", trace.droppedLineSamples());
        response.put("droppedLineHotspots", trace.droppedLineHotspots());
        response.put("lineHotspotsTruncated", trace.lineHotspotsTruncated());
        response.put("lineSampleIntervalMs", trace.lineSampleIntervalMs());
        response.put("traceExplanation", TraceInsightAnalyzer.explain(trace));
        response.put("traceComparison", TraceInsightAnalyzer.compare(trace, recentTraces));
        response.put("redacted", false);
        return response;
    }

    private static RequestTrace investigationTrace(String traceId,
                                                   List<RequestTrace> traces) {
        List<RequestTrace> safeTraces = traces == null ? List.of() : traces;
        if (traceId != null && !traceId.isBlank()) {
            for (RequestTrace trace : safeTraces) {
                if (trace.traceId().equals(traceId)) {
                    return trace;
                }
            }
            return null;
        }
        return safeTraces.stream()
            .max(Comparator.comparingLong(RequestTrace::totalWallNs))
            .orElse(null);
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
        response.put("externalSpanCount", externalSpanCount(trace.root()));
        response.put("sqlSpanCount", spanKindCount(trace.root(), "sql"));
        response.put("httpSpanCount", spanKindCount(trace.root(), "http"));
        response.put("debugSnapshotCount", debugSnapshotCount(trace.root()));
        response.put("droppedDebugSnapshots", droppedDebugSnapshotCount(trace.root()));
        response.put("lineSampleCount", trace.lineSampleCount());
        response.put("lineHotspotCount", trace.lineHotspots().size());
        response.put("deterministicLineCount", deterministicLineCount(trace.root()));
        response.put("deterministicLineSelfWallNs",
            deterministicLineSelfWallNs(trace.root()));
        response.put("deterministicLineSelfCpuNs",
            deterministicLineSelfCpuNs(trace.root()));
        response.put("deterministicLineAllocationCount",
            deterministicLineAllocationCount(trace.root()));
        response.put("deterministicLineAllocatedBytes",
            deterministicLineAllocatedBytes(trace.root()));
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

    private static long externalSpanCount(MethodSpan span) {
        if (span == null) return 0L;
        long total = isExternalSpan(span) ? 1L : 0L;
        for (MethodSpan child : span.children) {
            total += externalSpanCount(child);
        }
        return total;
    }

    private static long spanKindCount(MethodSpan span, String kind) {
        if (span == null) return 0L;
        long total = kind.equals(span.spanKind) ? 1L : 0L;
        for (MethodSpan child : span.children) {
            total += spanKindCount(child, kind);
        }
        return total;
    }

    private static long debugSnapshotCount(MethodSpan span) {
        if (span == null) return 0L;
        long total = span.debugSnapshots.size();
        for (MethodSpan child : span.children) {
            total += debugSnapshotCount(child);
        }
        return total;
    }

    private static long droppedDebugSnapshotCount(MethodSpan span) {
        if (span == null) return 0L;
        long total = span.droppedDebugSnapshots;
        for (MethodSpan child : span.children) {
            total += droppedDebugSnapshotCount(child);
        }
        return total;
    }

    private static boolean isExternalSpan(MethodSpan span) {
        return span != null
            && span.spanKind != null
            && !span.spanKind.isBlank()
            && !"method".equals(span.spanKind)
            && !"request".equals(span.spanKind);
    }

    private static long deterministicLineCount(MethodSpan span) {
        if (span == null) return 0L;
        long total = span.lineStats.size();
        for (MethodSpan child : span.children) {
            total += deterministicLineCount(child);
        }
        return total;
    }

    private static long deterministicLineAllocationCount(MethodSpan span) {
        if (span == null) return 0L;
        long total = 0L;
        for (MethodSpan.LineStat stat : span.lineStats.values()) {
            total += stat.allocationCount;
        }
        for (MethodSpan child : span.children) {
            total += deterministicLineAllocationCount(child);
        }
        return total;
    }

    private static long deterministicLineSelfWallNs(MethodSpan span) {
        if (span == null) return 0L;
        long total = 0L;
        for (MethodSpan.LineStat stat : span.lineStats.values()) {
            total += stat.selfWallNs;
        }
        for (MethodSpan child : span.children) {
            total += deterministicLineSelfWallNs(child);
        }
        return total;
    }

    private static long deterministicLineSelfCpuNs(MethodSpan span) {
        if (span == null) return 0L;
        long total = 0L;
        for (MethodSpan.LineStat stat : span.lineStats.values()) {
            total += stat.selfCpuNs;
        }
        for (MethodSpan child : span.children) {
            total += deterministicLineSelfCpuNs(child);
        }
        return total;
    }

    private static long deterministicLineAllocatedBytes(MethodSpan span) {
        if (span == null) return 0L;
        long total = 0L;
        for (MethodSpan.LineStat stat : span.lineStats.values()) {
            total += stat.allocatedBytes;
        }
        for (MethodSpan child : span.children) {
            total += deterministicLineAllocatedBytes(child);
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

    private static int boundedIntQuery(Context ctx, String name, int def, int min, int max) {
        String raw = ctx.queryParam(name);
        if (raw == null || raw.isBlank()) return def;
        try {
            int value = Integer.parseInt(raw);
            if (value < min) return min;
            return Math.min(value, max);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static long boundedLongQuery(Context ctx, String name, long def,
                                         long min, long max) {
        String raw = ctx.queryParam(name);
        if (raw == null || raw.isBlank()) return def;
        try {
            long value = Long.parseLong(raw);
            if (value < min) return min;
            return Math.min(value, max);
        } catch (NumberFormatException e) {
            return def;
        }
    }

}
