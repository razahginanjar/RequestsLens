package agent.http;

import agent.collector.jfr.JfrEventRecorder;
import agent.collector.logging.LogCaptureSupport;
import agent.core.AgentConfig;
import agent.core.CollectorRegistry;
import agent.persistence.SqliteRepository;
import agent.profiling.asyncprofiler.AsyncProfilerController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ProfilerApiCatalog {

    private final CollectorRegistry registry;
    private final AgentConfig config;
    private final ProfilerHttpSecurity security;

    ProfilerApiCatalog(CollectorRegistry registry,
                       AgentConfig config,
                       ProfilerHttpSecurity security) {
        this.registry = registry;
        this.config = config;
        this.security = security;
    }

    Map<String, Object> catalog() {
        Map<String, Object> catalog = ProfilerHttpServer.apiResponseStatic("api");
        catalog.put("instanceId", config.getInstanceId());
        catalog.put("authRequired", config.isAuthEnabled());
        catalog.put("corsEnabled", config.isCorsEnabled());
        catalog.put("sensitiveDetailsRedacted", !security.canExposeSensitiveDetails());
        catalog.put("capabilities", capabilities());
        catalog.put("links", links());

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
        routes.add(apiRoute("GET", "/profiler/logs",
            "Bounded live target logs and structured GC/JVM events", true, false, false));
        routes.add(apiRoute("GET", "/profiler/jfr/events",
            "Bounded in-process JFR JVM events", true, false, false));
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
            "Full method call tree, explanation, comparison, and line hotspots for one request trace", true, false, true));
        routes.add(apiRoute("GET", "/profiler/investigate",
            "Request-centered investigation view correlating trace, JFR, logs, and native profiler evidence", true, false, true));
        routes.add(apiRoute("GET", "/profiler/source",
            "Source window for one configured application line hotspot", true, false, true));
        routes.add(apiRoute("GET", "/profiler/package-discovery",
            "Suggest trace and line package prefixes from a target jar", true, false, false));
        routes.add(apiRoute("GET", "/profiler/flamegraph",
            "Bounded sampling profiler flamegraph tree", true, false, false));
        routes.add(apiRoute("GET", "/profiler/async/status",
            "Embedded async-profiler backend status", true, false, false));
        routes.add(apiRoute("POST", "/profiler/async/start",
            "Start a bounded async-profiler native profiling session", true, false, false));
        routes.add(apiRoute("POST", "/profiler/async/stop",
            "Stop async-profiler and keep the latest collapsed stack snapshot", true, false, false));
        routes.add(apiRoute("GET", "/profiler/async/collapsed",
            "Latest async-profiler collapsed stacks", true, false, false));
        routes.add(apiRoute("GET", "/profiler/async/flamegraph",
            "Latest async-profiler native flamegraph tree", true, false, false));
        routes.add(apiRoute("GET", "/profiler/dashboard",
            "Bundled HTML dashboard shell", false, false, false));
        routes.add(apiRoute("GET", "/profiler/dashboard.js",
            "Bundled dashboard JavaScript asset", false, false, false));
        catalog.put("routeCount", routes.size());
        catalog.put("routes", routes);
        return catalog;
    }

    Map<String, Object> capabilities() {
        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("persistenceConfigured", config.isPersistenceEnabled());
        capabilities.put("persistenceAvailable", registry.getSqliteRepository() != null);
        capabilities.put("persistenceHistoryLimit", SqliteRepository.MAX_QUERY_ROWS);
        capabilities.put("persistenceRetentionDays", config.getPersistenceRetentionDays());
        capabilities.put("selfMonitoring", true);
        capabilities.put("adaptiveSampling", config.isAdaptiveSamplingEnabled());
        capabilities.put("cpuMonitoring", true);
        capabilities.put("cpuSamplingIntervalMs", config.getCpuSamplingIntervalMs());
        capabilities.put("yamlConfig", true);
        capabilities.put("yamlConfigAutoDiscovery", true);
        capabilities.put("yamlConfigLoaded", config.isConfigFileLoaded());
        capabilities.put("yamlConfigAutoDiscovered",
            config.isConfigFileAutoDiscovered());
        capabilities.put("yamlConfigNames", List.of(
            "requestlens-agent.yaml",
            "requestlens-agent.yml",
            "requestlens.yaml",
            "requestlens.yml"));
        capabilities.put("traceConfigured", config.isTraceEnabled()
            && !config.getTracePackages().isBlank());
        capabilities.put("tracePackagesConfigured", !config.getTracePackages().isBlank());
        capabilities.put("allocationDetail", config.isAllocDetailEnabled());
        capabilities.put("externalSqlSpans", true);
        capabilities.put("externalHttpSpans", true);
        capabilities.put("lineProfilingConfigured", config.isLineProfilingConfigured());
        capabilities.put("lineProfilingEnabled", config.isLineProfilingActive());
        capabilities.put("lineMode", config.getLineMode());
        capabilities.put("sampledLineProfiling", config.isSampledLineProfilingActive());
        capabilities.put("deterministicLineProfiling",
            config.isDeterministicLineProfilingActive());
        capabilities.put("deterministicLineSelfTime", true);
        capabilities.put("linePackagesConfigured", !config.getLinePackages().isBlank());
        capabilities.put("lineAllocationDetail", config.isLineAllocationProfilingActive());
        capabilities.put("lineSampleIntervalMs", config.getLineSampleIntervalMs());
        capabilities.put("lineMaxSamplesPerTrace", config.getLineMaxSamplesPerTrace());
        capabilities.put("lineMaxLinesPerTrace", config.getLineMaxLinesPerTrace());
        capabilities.put("lineMaxTracePayloadBytes", config.getLineMaxTracePayloadBytes());
        capabilities.put("lineHotspots", config.isLineProfilingActive());
        capabilities.put("sourceFreeMethodLines", true);
        capabilities.put("sourceViewConfigured", config.isSourceViewConfigured());
        capabilities.put("sourceViewEnabled", config.isSourceViewActive());
        capabilities.put("sourceRootCount",
            SourceCodeService.rootCount(config.getSourceRoots()));
        capabilities.put("sourceContextLines", config.getSourceContextLines());
        capabilities.put("requestDebugSnapshots",
            config.isRequestDebugSnapshotActive());
        capabilities.put("requestExplanationComparison", true);
        capabilities.put("requestInvestigation", true);
        capabilities.put("requestInvestigationWindowMs",
            ProfilerHttpServer.DEFAULT_INVESTIGATION_WINDOW_MS);
        capabilities.put("liveLogsConfigured", config.isLogCaptureEnabled());
        capabilities.put("liveLogsAvailable", LogCaptureSupport.isEnabled());
        capabilities.put("liveLogMaxEvents", config.getLogMaxEvents());
        capabilities.put("structuredJvmEvents", true);
        capabilities.put("jfrConfigured", config.isJfrEnabled());
        capabilities.put("jfrAvailable", JfrEventRecorder.isJfrAvailable());
        capabilities.put("jfrRunning", registry.getJfrEventRecorder() != null
            && registry.getJfrEventRecorder().isRunning());
        capabilities.put("jfrEvents", registry.getJfrEventRecorder() != null);
        capabilities.put("jfrMaxEvents", config.getJfrMaxEvents());
        capabilities.put("jfrThresholdMs", config.getJfrThresholdMs());
        capabilities.put("debugSnapshotConfigured",
            config.isRequestDebugSnapshotConfigured());
        capabilities.put("debugSnapshotArgs",
            config.isDebugSnapshotCaptureArgs());
        capabilities.put("debugSnapshotReturn",
            config.isDebugSnapshotCaptureReturn());
        capabilities.put("debugMaxSnapshotsPerTrace",
            config.getDebugMaxSnapshotsPerTrace());
        capabilities.put("debugMaxSnapshotsPerSpan",
            config.getDebugMaxSnapshotsPerSpan());
        capabilities.put("debugMaxValueLength",
            config.getDebugMaxValueLength());
        capabilities.put("samplingProfilerConfigured", config.isSamplingProfilerEnabled());
        capabilities.put("samplingProfilerAvailable", registry.getStackSampler() != null);
        AsyncProfilerController.Status asyncStatus = asyncProfilerController().status();
        capabilities.put("asyncProfilerConfigured", asyncStatus.configured());
        capabilities.put("asyncProfilerEmbedded", asyncStatus.embedded());
        capabilities.put("asyncProfilerAvailable", asyncStatus.available());
        capabilities.put("asyncProfilerRunning", asyncStatus.running());
        capabilities.put("asyncProfilerVersion", asyncStatus.version());
        capabilities.put("asyncProfilerPlatform", asyncStatus.platform());
        capabilities.put("asyncProfilerEvents", List.of("cpu", "wall", "alloc", "lock", "itimer"));
        capabilities.put("asyncProfilerDefaultEvent", config.getAsyncProfilerEvent());
        capabilities.put("asyncProfilerInterval", config.getAsyncProfilerInterval());
        capabilities.put("asyncProfilerDurationSeconds",
            config.getAsyncProfilerDurationSeconds());
        capabilities.put("instrumentationDiagnostics", true);
        capabilities.put("packageDiscovery", true);
        capabilities.put("corsEnabled", config.isCorsEnabled());
        capabilities.put("authEnabled", config.isAuthEnabled());
        return capabilities;
    }

    Map<String, Object> links() {
        Map<String, Object> links = new LinkedHashMap<>();
        links.put("api", "/profiler/api");
        links.put("status", "/profiler/status");
        links.put("dashboard", "/profiler/dashboard");
        links.put("dashboardScript", "/profiler/dashboard.js");
        links.put("heap", "/profiler/heap");
        links.put("gc", "/profiler/gc");
        links.put("logs", "/profiler/logs");
        links.put("jfrEvents", "/profiler/jfr/events");
        links.put("cpu", "/profiler/cpu");
        links.put("endpoints", "/profiler/endpoints");
        links.put("beans", "/profiler/beans");
        links.put("historyHeap", "/profiler/history/heap");
        links.put("historyGc", "/profiler/history/gc");
        links.put("historyCpu", "/profiler/history/cpu");
        links.put("leaks", "/profiler/leaks");
        links.put("traces", "/profiler/traces");
        links.put("investigation", "/profiler/investigate");
        links.put("source", "/profiler/source");
        links.put("packageDiscovery", "/profiler/package-discovery");
        links.put("flamegraph", "/profiler/flamegraph");
        links.put("asyncStatus", "/profiler/async/status");
        links.put("asyncStart", "/profiler/async/start");
        links.put("asyncStop", "/profiler/async/stop");
        links.put("asyncCollapsed", "/profiler/async/collapsed");
        links.put("asyncFlamegraph", "/profiler/async/flamegraph");
        return links;
    }

    private AsyncProfilerController asyncProfilerController() {
        AsyncProfilerController controller = registry.getAsyncProfilerController();
        if (controller == null) {
            controller = new AsyncProfilerController(config);
            registry.setAsyncProfilerController(controller);
        }
        return controller;
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
}
