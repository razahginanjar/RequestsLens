package agent.core;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Holds all agent configuration.
 *
 * Load once at startup via AgentConfig.load(args).
 * Pass the single instance everywhere via constructor injection.
 * Never load config inside a hot path.
 */
public final class AgentConfig {

    private static final Logger log = Logger.getLogger(AgentConfig.class.getName());

    // ── Defaults ──────────────────────────────────────────────────────────
    private static final int    DEFAULT_PORT     = 7070;
    private static final long   DEFAULT_INTERVAL = 10L;   // ms
    private static final String DEFAULT_INSTANCE_ID_SUFFIX = ":7070";
    private static final String DEFAULT_HTTP_HOST = "127.0.0.1";
    private static final String DEFAULT_AUTH_TOKEN = "";
    private static final boolean DEFAULT_CORS_ENABLED = false;
    private static final String DEFAULT_CORS_ALLOWED_ORIGINS = "";
    private static final long   DEFAULT_CPU_SAMPLING_INTERVAL_MS = 1000L;

    // Phase 3 — persistence defaults
    private static final boolean DEFAULT_PERSISTENCE_ENABLED = true;
    private static final String  DEFAULT_PERSISTENCE_PATH    = "./profiler-data/profiler.db";
    private static final int     DEFAULT_RETENTION_DAYS      = 7;

    // Phase 4 — adaptive sampling & alerting defaults
    private static final boolean DEFAULT_ADAPTIVE_ENABLED      = true;
    private static final double  DEFAULT_MAX_RPS               = 500.0;
    private static final long    DEFAULT_THROTTLE_MULTIPLIER   = 5L;
    private static final double  DEFAULT_GC_OVERHEAD_THRESHOLD = 15.0;   // percent
    private static final String  DEFAULT_WEBHOOK_URL           = "";     // empty = disabled
    private static final long    DEFAULT_LEAK_WINDOW_MS        = 60_000L;

    // Phase 6 — deep request profiling defaults
    private static final boolean DEFAULT_SAMPLING_PROFILER_ENABLED = true;
    private static final long    DEFAULT_SAMPLING_PROFILER_MS      = 20L;
    private static final boolean DEFAULT_TRACE_ENABLED             = false;
    private static final String  DEFAULT_TRACE_PACKAGES            = "";
    private static final int     DEFAULT_TRACE_SAMPLE_RATE         = 50;
    private static final int     DEFAULT_TRACE_MAX_DEPTH           = 40;
    private static final int     DEFAULT_TRACE_MAX_SPANS           = 5000;
    private static final boolean DEFAULT_ALLOC_DETAIL_ENABLED      = true;
    private static final boolean DEFAULT_LINE_PROFILING_ENABLED    = false;
    private static final String  DEFAULT_LINE_MODE                 = "sampled";
    private static final String  DEFAULT_LINE_PACKAGES             = "";
    private static final long    DEFAULT_LINE_SAMPLE_INTERVAL_MS   = 5L;
    private static final int     DEFAULT_LINE_MAX_SAMPLES_PER_TRACE = 1000;
    private static final int     DEFAULT_LINE_MAX_LINES_PER_TRACE  = 300;
    private static final int     DEFAULT_LINE_MAX_TRACE_PAYLOAD_BYTES = 262_144;
    private static final boolean DEFAULT_LINE_ALLOC_ENABLED        = false;
    private static final int     MAX_LINE_SAMPLES_PER_TRACE        = 100_000;
    private static final int     MAX_LINE_LINES_PER_TRACE          = 10_000;
    private static final int     MAX_LINE_TRACE_PAYLOAD_BYTES      = 4 * 1024 * 1024;
    private static final boolean DEFAULT_SOURCE_VIEW_ENABLED       = false;
    private static final String  DEFAULT_SOURCE_ROOTS              = "";
    private static final int     DEFAULT_SOURCE_CONTEXT_LINES      = 6;
    private static final int     MAX_SOURCE_CONTEXT_LINES          = 50;
    private static final boolean DEFAULT_DEBUG_SNAPSHOT_ENABLED    = false;
    private static final boolean DEFAULT_DEBUG_CAPTURE_ARGS        = true;
    private static final boolean DEFAULT_DEBUG_CAPTURE_RETURN      = true;
    private static final int     DEFAULT_DEBUG_MAX_SNAPSHOTS_PER_TRACE = 200;
    private static final int     DEFAULT_DEBUG_MAX_SNAPSHOTS_PER_SPAN  = 8;
    private static final int     DEFAULT_DEBUG_MAX_VALUE_LENGTH    = 120;
    private static final int     MAX_DEBUG_SNAPSHOTS_PER_TRACE    = 5000;
    private static final int     MAX_DEBUG_SNAPSHOTS_PER_SPAN     = 64;
    private static final int     MAX_DEBUG_VALUE_LENGTH           = 1000;
    private static final boolean DEFAULT_LOG_CAPTURE_ENABLED       = false;
    private static final int     DEFAULT_LOG_MAX_EVENTS            = 1000;
    private static final int     MAX_LOG_EVENTS                    = 20_000;
    private static final boolean DEFAULT_JFR_ENABLED               = false;
    private static final int     DEFAULT_JFR_MAX_EVENTS            = 1000;
    private static final int     MAX_JFR_EVENTS                    = 20_000;
    private static final long    DEFAULT_JFR_THRESHOLD_MS          = 10L;
    private static final long    MAX_JFR_THRESHOLD_MS              = 60_000L;

    private static final String[] LINE_PROFILING_EXCLUDED_PREFIXES = {
        "agent.",
        "java.",
        "javax.",
        "jakarta.",
        "jdk.",
        "sun.",
        "com.sun.",
        "org.springframework.",
        "org.hibernate.",
        "com.fasterxml.jackson.",
        "org.slf4j.",
        "ch.qos.logback.",
        "io.netty.",
        "reactor.",
        "net.bytebuddy.",
        "org.aspectj.",
        "org.junit.",
        "com.zaxxer.hikari.",
        "org.postgresql.",
        "com.mysql.",
        "com.microsoft.sqlserver.",
        "org.xerial."
    };

    // ── Fields ────────────────────────────────────────────────────────────
    private final int    httpPort;
    private final String httpHost;
    private final long   baseIntervalMs;
    private final String instanceId;
    private final long   cpuSamplingIntervalMs;

    // HTTP safety configuration
    private final String  authToken;
    private final boolean corsEnabled;
    private final String  corsAllowedOrigins;

    // Phase 3 — persistence configuration
    private final boolean persistenceEnabled;
    private final String  persistencePath;
    private final int     persistenceRetentionDays;

    // Phase 4 — adaptive sampling & alerting configuration
    private final boolean adaptiveSamplingEnabled;
    private final double  maxRps;
    private final long    throttleMultiplier;
    private final double  gcOverheadThreshold;
    private final String  webhookUrl;
    private final long    leakDetectionWindowMs;

    // Phase 6 — deep request profiling configuration
    private final boolean samplingProfilerEnabled;
    private final long    samplingProfilerIntervalMs;
    private final boolean traceEnabled;
    private final String  tracePackages;
    private final int     traceSampleRate;
    private final int     traceMaxDepth;
    private final int     traceMaxSpans;
    private final boolean allocDetailEnabled;
    private final boolean lineProfilingConfigured;
    private final String  lineMode;
    private final String  linePackages;
    private final long    lineSampleIntervalMs;
    private final int     lineMaxSamplesPerTrace;
    private final int     lineMaxLinesPerTrace;
    private final int     lineMaxTracePayloadBytes;
    private final boolean lineAllocEnabled;
    private final boolean sourceViewEnabled;
    private final String  sourceRoots;
    private final int     sourceContextLines;
    private final boolean debugSnapshotEnabled;
    private final boolean debugSnapshotCaptureArgs;
    private final boolean debugSnapshotCaptureReturn;
    private final int     debugMaxSnapshotsPerTrace;
    private final int     debugMaxSnapshotsPerSpan;
    private final int     debugMaxValueLength;
    private final boolean logCaptureEnabled;
    private final int     logMaxEvents;
    private final boolean jfrEnabled;
    private final int     jfrMaxEvents;
    private final long    jfrThresholdMs;

    private AgentConfig(int httpPort, String httpHost, long baseIntervalMs, String instanceId,
                        long cpuSamplingIntervalMs,
                        String authToken, boolean corsEnabled, String corsAllowedOrigins,
                        boolean persistenceEnabled, String persistencePath,
                        int persistenceRetentionDays,
                        boolean adaptiveSamplingEnabled, double maxRps,
                        long throttleMultiplier, double gcOverheadThreshold,
                        String webhookUrl, long leakDetectionWindowMs,
                        boolean samplingProfilerEnabled, long samplingProfilerIntervalMs,
                        boolean traceEnabled, String tracePackages, int traceSampleRate,
                        int traceMaxDepth, int traceMaxSpans, boolean allocDetailEnabled,
                        boolean lineProfilingConfigured, String lineMode, String linePackages,
                        long lineSampleIntervalMs, int lineMaxSamplesPerTrace,
                        int lineMaxLinesPerTrace, int lineMaxTracePayloadBytes,
                        boolean lineAllocEnabled, boolean sourceViewEnabled,
                        String sourceRoots, int sourceContextLines,
                        boolean debugSnapshotEnabled,
                        boolean debugSnapshotCaptureArgs,
                        boolean debugSnapshotCaptureReturn,
                        int debugMaxSnapshotsPerTrace,
                        int debugMaxSnapshotsPerSpan,
                        int debugMaxValueLength,
                        boolean logCaptureEnabled,
                        int logMaxEvents,
                        boolean jfrEnabled,
                        int jfrMaxEvents,
                        long jfrThresholdMs) {
        this.httpPort                 = httpPort;
        this.httpHost                 = httpHost;
        this.baseIntervalMs           = baseIntervalMs;
        this.instanceId               = instanceId;
        this.cpuSamplingIntervalMs    = cpuSamplingIntervalMs;
        this.authToken                = authToken;
        this.corsEnabled              = corsEnabled;
        this.corsAllowedOrigins       = corsAllowedOrigins;
        this.persistenceEnabled       = persistenceEnabled;
        this.persistencePath          = persistencePath;
        this.persistenceRetentionDays = persistenceRetentionDays;
        this.adaptiveSamplingEnabled  = adaptiveSamplingEnabled;
        this.maxRps                   = maxRps;
        this.throttleMultiplier       = throttleMultiplier;
        this.gcOverheadThreshold      = gcOverheadThreshold;
        this.webhookUrl               = webhookUrl;
        this.leakDetectionWindowMs    = leakDetectionWindowMs;
        this.samplingProfilerEnabled    = samplingProfilerEnabled;
        this.samplingProfilerIntervalMs = samplingProfilerIntervalMs;
        this.traceEnabled               = traceEnabled;
        this.tracePackages              = tracePackages;
        this.traceSampleRate            = traceSampleRate;
        this.traceMaxDepth              = traceMaxDepth;
        this.traceMaxSpans              = traceMaxSpans;
        this.allocDetailEnabled         = allocDetailEnabled;
        this.lineProfilingConfigured    = lineProfilingConfigured;
        this.lineMode                   = lineMode;
        this.linePackages               = linePackages;
        this.lineSampleIntervalMs       = lineSampleIntervalMs;
        this.lineMaxSamplesPerTrace     = lineMaxSamplesPerTrace;
        this.lineMaxLinesPerTrace       = lineMaxLinesPerTrace;
        this.lineMaxTracePayloadBytes   = lineMaxTracePayloadBytes;
        this.lineAllocEnabled           = lineAllocEnabled;
        this.sourceViewEnabled          = sourceViewEnabled;
        this.sourceRoots                = sourceRoots;
        this.sourceContextLines         = sourceContextLines;
        this.debugSnapshotEnabled       = debugSnapshotEnabled;
        this.debugSnapshotCaptureArgs   = debugSnapshotCaptureArgs;
        this.debugSnapshotCaptureReturn = debugSnapshotCaptureReturn;
        this.debugMaxSnapshotsPerTrace  = debugMaxSnapshotsPerTrace;
        this.debugMaxSnapshotsPerSpan   = debugMaxSnapshotsPerSpan;
        this.debugMaxValueLength        = debugMaxValueLength;
        this.logCaptureEnabled          = logCaptureEnabled;
        this.logMaxEvents               = logMaxEvents;
        this.jfrEnabled                 = jfrEnabled;
        this.jfrMaxEvents               = jfrMaxEvents;
        this.jfrThresholdMs             = jfrThresholdMs;
    }

    /**
     * Load configuration from all sources.
     *
     * @param agentArgs the string passed after -javaagent:agent.jar=<HERE>
     *                  Can be null if no arguments were provided.
     */
    public static AgentConfig load(String agentArgs) {
        Properties props = new Properties();

        // Source 1 — properties file (highest priority)
        loadPropertiesFile(props);

        // Source 2 — system properties (override file)
        applySystemProperties(props);

        // Source 3 — agent argument string (lowest priority)
        // e.g. "port=8080,interval=5" → split on comma, then on =
        if (agentArgs != null && !agentArgs.isBlank()) {
            String previousAgentKey = null;
            boolean previousAgentValueWasSet = false;
            for (String pair : agentArgs.split(",")) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2) {
                    // Map short arg names to full property names
                    String key = switch (kv[0].trim()) {
                        case "port"              -> "profiler.http.port";
                        case "host"              -> "profiler.http.host";
                        case "auth.token"        -> "profiler.auth.token";
                        case "cors.enabled"      -> "profiler.http.cors.enabled";
                        case "cors.origins"      -> "profiler.http.cors.allowed.origins";
                        case "interval"          -> "profiler.sampling.interval.ms";
                        case "cpu.interval"      -> "profiler.cpu.sampling.interval.ms";
                        case "alert.webhook.url" -> "profiler.alert.webhook.url";
                        case "max.rps"           -> "profiler.sampling.adaptive.max.rps";
                        case "trace.enabled"     -> "profiler.trace.enabled";
                        case "trace.packages"    -> "profiler.trace.packages";
                        case "trace.sample.rate" -> "profiler.trace.sample.rate";
                        case "line.enabled"      -> "profiler.line.enabled";
                        case "line.mode"         -> "profiler.line.mode";
                        case "line.packages"     -> "profiler.line.packages";
                        case "line.interval"     -> "profiler.line.sample.interval.ms";
                        case "line.max.samples"  -> "profiler.line.max.samples.per.trace";
                        case "line.max.lines"    -> "profiler.line.max.lines.per.trace";
                        case "line.max.payload.bytes" -> "profiler.line.max.trace.payload.bytes";
                        case "line.alloc.enabled" -> "profiler.line.alloc.enabled";
                        case "source.enabled"    -> "profiler.source.enabled";
                        case "source.roots"      -> "profiler.source.roots";
                        case "source.context.lines" -> "profiler.source.context.lines";
                        case "debug.enabled"     -> "profiler.debug.enabled";
                        case "debug.capture.args" -> "profiler.debug.capture.args";
                        case "debug.capture.return" -> "profiler.debug.capture.return";
                        case "debug.max.snapshots" -> "profiler.debug.max.snapshots.per.trace";
                        case "debug.max.snapshots.per.trace" -> "profiler.debug.max.snapshots.per.trace";
                        case "debug.max.snapshots.per.span" -> "profiler.debug.max.snapshots.per.span";
                        case "debug.max.value.length" -> "profiler.debug.max.value.length";
                        case "logs.enabled"       -> "profiler.logs.enabled";
                        case "logs.max.events"    -> "profiler.logs.max.events";
                        case "jfr.enabled"        -> "profiler.jfr.enabled";
                        case "jfr.max.events"     -> "profiler.jfr.max.events";
                        case "jfr.threshold.ms"   -> "profiler.jfr.threshold.ms";
                        default                  -> kv[0].trim();
                    };
                    // Only set if not already set by higher-priority source
                    if (!props.containsKey(key)) {
                        props.setProperty(key, kv[1].trim());
                        previousAgentValueWasSet = true;
                    } else {
                        previousAgentValueWasSet = false;
                    }
                    previousAgentKey = key;
                } else if (previousAgentKey != null && previousAgentValueWasSet
                        && acceptsAgentArgContinuation(previousAgentKey)) {
                    String continuation = pair.trim();
                    if (!continuation.isBlank()) {
                        props.setProperty(previousAgentKey,
                            props.getProperty(previousAgentKey) + "," + continuation);
                    }
                }
            }
        }

        // Parse final values with defaults
        int  port     = parseInt(props, "profiler.http.port",
                                  DEFAULT_PORT);
        String host    = props.getProperty("profiler.http.host",
                                  DEFAULT_HTTP_HOST).trim();
        long interval = parseLong(props, "profiler.sampling.interval.ms",
                                  DEFAULT_INTERVAL);
        long cpuSamplingInterval = parseLong(props, "profiler.cpu.sampling.interval.ms",
                                  DEFAULT_CPU_SAMPLING_INTERVAL_MS);
        String id     = props.getProperty("profiler.instance.id",
                                  resolveHostname() + ":" + port);
        String authToken = props.getProperty("profiler.auth.token",
                                  DEFAULT_AUTH_TOKEN).trim();
        boolean corsEnabled = Boolean.parseBoolean(
            props.getProperty("profiler.http.cors.enabled",
                String.valueOf(DEFAULT_CORS_ENABLED)));
        String corsAllowedOrigins = props.getProperty(
            "profiler.http.cors.allowed.origins", DEFAULT_CORS_ALLOWED_ORIGINS).trim();

        // Validate
        if (host.isBlank()) {
            log.warning("profiler.http.host is blank. Resetting to " + DEFAULT_HTTP_HOST);
            host = DEFAULT_HTTP_HOST;
        }
        if (interval < 5) {
            log.warning("profiler.sampling.interval.ms=" + interval
                + " is below minimum of 5ms. Resetting to 5ms.");
            interval = 5;
        }
        if (cpuSamplingInterval < 250) {
            log.warning("profiler.cpu.sampling.interval.ms=" + cpuSamplingInterval
                + " is below minimum of 250ms. Resetting to 250ms.");
            cpuSamplingInterval = 250;
        }
        if (port < 1024 || port > 65535) {
            log.warning("profiler.http.port=" + port
                + " is invalid. Resetting to " + DEFAULT_PORT);
            port = DEFAULT_PORT;
        }
        if (corsEnabled && corsAllowedOrigins.isBlank()) {
            log.warning("profiler.http.cors.enabled=true but "
                + "profiler.http.cors.allowed.origins is empty. Disabling CORS.");
            corsEnabled = false;
        }
        if (authToken.isBlank()) {
            if (isLoopbackHost(host)) {
                log.warning("Profiler HTTP auth is disabled. The server is bound to "
                    + host + " only; set profiler.auth.token before exposing it remotely.");
            } else {
                log.warning("Profiler HTTP auth is disabled while profiler.http.host="
                    + host + ". Sensitive bean/class details will be redacted; set "
                    + "profiler.auth.token before exposing the server.");
            }
        } else if (authToken.length() < 16) {
            log.warning("profiler.auth.token is shorter than 16 characters. "
                + "Use a high-entropy token for shared environments.");
        }

        // ── Phase 3 — persistence settings ────────────────────────────────
        boolean persistenceEnabled = Boolean.parseBoolean(
            props.getProperty("profiler.persistence.enabled",
                String.valueOf(DEFAULT_PERSISTENCE_ENABLED)));
        String persistencePath = props.getProperty(
            "profiler.persistence.path", DEFAULT_PERSISTENCE_PATH);
        int retentionDays = parseInt(props,
            "profiler.persistence.retention.days", DEFAULT_RETENTION_DAYS);

        // Retention must be at least 1 day — a zero/negative value would purge
        // everything immediately, defeating the point of persistence.
        if (retentionDays < 1) {
            log.warning("profiler.persistence.retention.days=" + retentionDays
                + " is invalid. Resetting to " + DEFAULT_RETENTION_DAYS);
            retentionDays = DEFAULT_RETENTION_DAYS;
        }

        // ── Phase 4 — adaptive sampling & alerting settings ───────────────
        boolean adaptiveEnabled = Boolean.parseBoolean(
            props.getProperty("profiler.sampling.adaptive.enabled",
                String.valueOf(DEFAULT_ADAPTIVE_ENABLED)));
        double maxRps = parseDouble(props,
            "profiler.sampling.adaptive.max.rps", DEFAULT_MAX_RPS);
        long throttleMultiplier = parseLong(props,
            "profiler.sampling.adaptive.multiplier", DEFAULT_THROTTLE_MULTIPLIER);
        double gcOverheadThreshold = parseDouble(props,
            "profiler.alert.gc.overhead.threshold", DEFAULT_GC_OVERHEAD_THRESHOLD);
        String webhookUrl = props.getProperty(
            "profiler.alert.webhook.url", DEFAULT_WEBHOOK_URL);
        long leakWindowMs = parseLong(props,
            "profiler.leak.detection.window.ms", DEFAULT_LEAK_WINDOW_MS);

        // The throttle multiplier must be at least 1 (1 = no throttling effect).
        if (throttleMultiplier < 1) {
            log.warning("profiler.sampling.adaptive.multiplier=" + throttleMultiplier
                + " is invalid. Resetting to " + DEFAULT_THROTTLE_MULTIPLIER);
            throttleMultiplier = DEFAULT_THROTTLE_MULTIPLIER;
        }

        // ── Phase 6 — deep request profiling settings ─────────────────────
        boolean samplingProfilerEnabled = Boolean.parseBoolean(
            props.getProperty("profiler.sampling.profiler.enabled",
                String.valueOf(DEFAULT_SAMPLING_PROFILER_ENABLED)));
        long samplingProfilerMs = parseLong(props,
            "profiler.sampling.profiler.interval.ms", DEFAULT_SAMPLING_PROFILER_MS);
        if (samplingProfilerMs < 5) samplingProfilerMs = 5;

        boolean traceEnabled = Boolean.parseBoolean(
            props.getProperty("profiler.trace.enabled",
                String.valueOf(DEFAULT_TRACE_ENABLED)));
        String tracePackages = props.getProperty("profiler.trace.packages", DEFAULT_TRACE_PACKAGES);
        int traceSampleRate = parseInt(props, "profiler.trace.sample.rate", DEFAULT_TRACE_SAMPLE_RATE);
        if (traceSampleRate < 1) traceSampleRate = 1;
        int traceMaxDepth = parseInt(props, "profiler.trace.max.depth", DEFAULT_TRACE_MAX_DEPTH);
        if (traceMaxDepth < 1) traceMaxDepth = DEFAULT_TRACE_MAX_DEPTH;
        int traceMaxSpans = parseInt(props, "profiler.trace.max.spans", DEFAULT_TRACE_MAX_SPANS);
        if (traceMaxSpans < 1) traceMaxSpans = DEFAULT_TRACE_MAX_SPANS;
        boolean allocDetailEnabled = Boolean.parseBoolean(
            props.getProperty("profiler.trace.alloc.detail.enabled",
                String.valueOf(DEFAULT_ALLOC_DETAIL_ENABLED)));

        boolean lineProfilingConfigured = Boolean.parseBoolean(
            props.getProperty("profiler.line.enabled",
                String.valueOf(DEFAULT_LINE_PROFILING_ENABLED)));
        String lineMode = normalizeLineMode(
            props.getProperty("profiler.line.mode", DEFAULT_LINE_MODE));
        String linePackages = normalizePackageList(
            props.getProperty("profiler.line.packages", DEFAULT_LINE_PACKAGES));
        long lineSampleIntervalMs = parseLong(props,
            "profiler.line.sample.interval.ms", DEFAULT_LINE_SAMPLE_INTERVAL_MS);
        if (lineSampleIntervalMs < 1) {
            log.warning("profiler.line.sample.interval.ms=" + lineSampleIntervalMs
                + " is invalid. Resetting to " + DEFAULT_LINE_SAMPLE_INTERVAL_MS);
            lineSampleIntervalMs = DEFAULT_LINE_SAMPLE_INTERVAL_MS;
        }
        int lineMaxSamplesPerTrace = enforceIntRange(props,
            "profiler.line.max.samples.per.trace",
            DEFAULT_LINE_MAX_SAMPLES_PER_TRACE, 1, MAX_LINE_SAMPLES_PER_TRACE);
        int lineMaxLinesPerTrace = enforceIntRange(props,
            "profiler.line.max.lines.per.trace",
            DEFAULT_LINE_MAX_LINES_PER_TRACE, 1, MAX_LINE_LINES_PER_TRACE);
        int lineMaxTracePayloadBytes = enforceIntRange(props,
            "profiler.line.max.trace.payload.bytes",
            DEFAULT_LINE_MAX_TRACE_PAYLOAD_BYTES, 1024, MAX_LINE_TRACE_PAYLOAD_BYTES);
        boolean lineAllocEnabled = Boolean.parseBoolean(
            props.getProperty("profiler.line.alloc.enabled",
                String.valueOf(DEFAULT_LINE_ALLOC_ENABLED)));
        boolean sourceViewEnabled = Boolean.parseBoolean(
            props.getProperty("profiler.source.enabled",
                String.valueOf(DEFAULT_SOURCE_VIEW_ENABLED)));
        String sourceRoots = normalizeCommaList(
            props.getProperty("profiler.source.roots", DEFAULT_SOURCE_ROOTS));
        int sourceContextLines = enforceIntRange(props,
            "profiler.source.context.lines",
            DEFAULT_SOURCE_CONTEXT_LINES, 0, MAX_SOURCE_CONTEXT_LINES);

        boolean debugSnapshotEnabled = Boolean.parseBoolean(
            props.getProperty("profiler.debug.enabled",
                String.valueOf(DEFAULT_DEBUG_SNAPSHOT_ENABLED)));
        boolean debugSnapshotCaptureArgs = Boolean.parseBoolean(
            props.getProperty("profiler.debug.capture.args",
                String.valueOf(DEFAULT_DEBUG_CAPTURE_ARGS)));
        boolean debugSnapshotCaptureReturn = Boolean.parseBoolean(
            props.getProperty("profiler.debug.capture.return",
                String.valueOf(DEFAULT_DEBUG_CAPTURE_RETURN)));
        int debugMaxSnapshotsPerTrace = enforceIntRange(props,
            "profiler.debug.max.snapshots.per.trace",
            DEFAULT_DEBUG_MAX_SNAPSHOTS_PER_TRACE, 1, MAX_DEBUG_SNAPSHOTS_PER_TRACE);
        int debugMaxSnapshotsPerSpan = enforceIntRange(props,
            "profiler.debug.max.snapshots.per.span",
            DEFAULT_DEBUG_MAX_SNAPSHOTS_PER_SPAN, 1, MAX_DEBUG_SNAPSHOTS_PER_SPAN);
        int debugMaxValueLength = enforceIntRange(props,
            "profiler.debug.max.value.length",
            DEFAULT_DEBUG_MAX_VALUE_LENGTH, 1, MAX_DEBUG_VALUE_LENGTH);
        boolean logCaptureEnabled = Boolean.parseBoolean(
            props.getProperty("profiler.logs.enabled",
                String.valueOf(DEFAULT_LOG_CAPTURE_ENABLED)));
        int logMaxEvents = enforceIntRange(props,
            "profiler.logs.max.events", DEFAULT_LOG_MAX_EVENTS, 10, MAX_LOG_EVENTS);
        boolean jfrEnabled = Boolean.parseBoolean(
            props.getProperty("profiler.jfr.enabled",
                String.valueOf(DEFAULT_JFR_ENABLED)));
        int jfrMaxEvents = enforceIntRange(props,
            "profiler.jfr.max.events", DEFAULT_JFR_MAX_EVENTS, 10, MAX_JFR_EVENTS);
        long jfrThresholdMs = parseLong(props,
            "profiler.jfr.threshold.ms", DEFAULT_JFR_THRESHOLD_MS);
        if (jfrThresholdMs < 0L) {
            log.warning("profiler.jfr.threshold.ms=" + jfrThresholdMs
                + " is invalid. Resetting to " + DEFAULT_JFR_THRESHOLD_MS);
            jfrThresholdMs = DEFAULT_JFR_THRESHOLD_MS;
        } else if (jfrThresholdMs > MAX_JFR_THRESHOLD_MS) {
            log.warning("profiler.jfr.threshold.ms=" + jfrThresholdMs
                + " is above safety max " + MAX_JFR_THRESHOLD_MS
                + ". Clamping to " + MAX_JFR_THRESHOLD_MS);
            jfrThresholdMs = MAX_JFR_THRESHOLD_MS;
        }

        // Tracing is a no-op without target packages — refuse to instrument "everything".
        if (traceEnabled && tracePackages.isBlank()) {
            log.warning("profiler.trace.enabled=true but profiler.trace.packages is empty — "
                + "method tracing will stay OFF (set e.g. profiler.trace.packages=com.example).");
        }
        if (debugSnapshotEnabled && !traceEnabled) {
            log.warning("profiler.debug.enabled=true but profiler.trace.enabled=false — "
                + "request debug snapshots will stay OFF.");
        }
        if (debugSnapshotEnabled && tracePackages.isBlank()) {
            log.warning("profiler.debug.enabled=true but profiler.trace.packages is empty — "
                + "request debug snapshots need the method trace package allow-list.");
        }
        if (lineProfilingConfigured && linePackages.isBlank()) {
            log.warning("profiler.line.enabled=true but profiler.line.packages is empty — "
                + "line profiling will stay OFF (set e.g. profiler.line.packages=com.example).");
        }
        if (lineProfilingConfigured && containsOnlyExcludedLinePackages(linePackages)) {
            log.warning("profiler.line.packages only contains known dependency/agent prefixes — "
                + "line profiling will not match target application classes.");
        }
        if (sourceViewEnabled && sourceRoots.isBlank()) {
            log.warning("profiler.source.enabled=true but profiler.source.roots is empty — "
                + "source code view will stay OFF.");
        }
        if (sourceViewEnabled && linePackages.isBlank()) {
            log.warning("profiler.source.enabled=true but profiler.line.packages is empty — "
                + "source code view has no target package allow-list.");
        }

        log.info("AgentConfig loaded — host=" + host
            + " port=" + port
            + " interval=" + interval + "ms instanceId=" + id
            + " cpuSampling=" + cpuSamplingInterval + "ms"
            + " auth=" + (!authToken.isBlank())
            + " cors=" + corsEnabled
            + " persistence=" + persistenceEnabled
            + " dbPath=" + persistencePath
            + " retentionDays=" + retentionDays
            + " adaptive=" + adaptiveEnabled
            + " maxRps=" + maxRps
            + " throttleX=" + throttleMultiplier
            + " gcOverheadThreshold=" + gcOverheadThreshold
            + " webhook=" + (webhookUrl.isBlank() ? "(none)" : webhookUrl)
            + " leakWindowMs=" + leakWindowMs
            + " samplingProfiler=" + samplingProfilerEnabled + "@" + samplingProfilerMs + "ms"
            + " trace=" + traceEnabled
            + " tracePackages=" + (tracePackages.isBlank() ? "(none)" : tracePackages)
            + " traceSampleRate=" + traceSampleRate
            + " allocDetail=" + allocDetailEnabled
            + " lineProfiling=" + lineProfilingConfigured
            + " lineMode=" + lineMode
            + " linePackages=" + (linePackages.isBlank() ? "(none)" : linePackages)
            + " lineInterval=" + lineSampleIntervalMs + "ms"
            + " lineMaxSamples=" + lineMaxSamplesPerTrace
            + " lineMaxLines=" + lineMaxLinesPerTrace
            + " lineMaxPayloadBytes=" + lineMaxTracePayloadBytes
            + " lineAlloc=" + lineAllocEnabled
            + " sourceView=" + sourceViewEnabled
            + " sourceRoots=" + (sourceRoots.isBlank() ? "(none)" : sourceRoots)
            + " sourceContext=" + sourceContextLines
            + " debugSnapshots=" + debugSnapshotEnabled
            + " debugCaptureArgs=" + debugSnapshotCaptureArgs
            + " debugCaptureReturn=" + debugSnapshotCaptureReturn
            + " debugMaxSnapshotsPerTrace=" + debugMaxSnapshotsPerTrace
            + " debugMaxSnapshotsPerSpan=" + debugMaxSnapshotsPerSpan
            + " debugMaxValueLength=" + debugMaxValueLength
            + " logCapture=" + logCaptureEnabled
            + " logMaxEvents=" + logMaxEvents
            + " jfr=" + jfrEnabled
            + " jfrMaxEvents=" + jfrMaxEvents
            + " jfrThresholdMs=" + jfrThresholdMs);

        return new AgentConfig(port, host, interval, id, cpuSamplingInterval,
            authToken, corsEnabled, corsAllowedOrigins,
            persistenceEnabled, persistencePath, retentionDays,
            adaptiveEnabled, maxRps, throttleMultiplier, gcOverheadThreshold,
            webhookUrl, leakWindowMs,
            samplingProfilerEnabled, samplingProfilerMs, traceEnabled, tracePackages,
            traceSampleRate, traceMaxDepth, traceMaxSpans, allocDetailEnabled,
            lineProfilingConfigured, lineMode, linePackages, lineSampleIntervalMs,
            lineMaxSamplesPerTrace, lineMaxLinesPerTrace, lineMaxTracePayloadBytes,
            lineAllocEnabled, sourceViewEnabled, sourceRoots, sourceContextLines,
            debugSnapshotEnabled, debugSnapshotCaptureArgs, debugSnapshotCaptureReturn,
            debugMaxSnapshotsPerTrace, debugMaxSnapshotsPerSpan, debugMaxValueLength,
            logCaptureEnabled, logMaxEvents,
            jfrEnabled, jfrMaxEvents, jfrThresholdMs);
    }

    // ── Getters ───────────────────────────────────────────────────────────
    public int    getHttpPort()       { return httpPort; }
    public String getHttpHost()       { return httpHost; }
    public long   getBaseIntervalMs() { return baseIntervalMs; }
    public String getInstanceId()     { return instanceId; }
    public long   getCpuSamplingIntervalMs() { return cpuSamplingIntervalMs; }

    // HTTP safety getters
    public String  getAuthToken()          { return authToken; }
    public boolean isAuthEnabled()         { return !authToken.isBlank(); }
    public boolean isCorsEnabled()         { return corsEnabled; }
    public String  getCorsAllowedOrigins() { return corsAllowedOrigins; }
    public boolean isLocalOnlyHttpBind()   { return isLoopbackHost(httpHost); }

    // Phase 3 — persistence getters
    public boolean isPersistenceEnabled()        { return persistenceEnabled; }
    public String  getPersistencePath()          { return persistencePath; }
    public int     getPersistenceRetentionDays() { return persistenceRetentionDays; }

    // Phase 4 — adaptive sampling & alerting getters
    public boolean isAdaptiveSamplingEnabled() { return adaptiveSamplingEnabled; }
    public double  getMaxRps()                 { return maxRps; }
    public long    getThrottleMultiplier()     { return throttleMultiplier; }
    public double  getGcOverheadThreshold()    { return gcOverheadThreshold; }
    public String  getWebhookUrl()             { return webhookUrl; }
    public long    getLeakDetectionWindowMs()  { return leakDetectionWindowMs; }

    // Phase 6 — deep request profiling getters
    public boolean isSamplingProfilerEnabled()    { return samplingProfilerEnabled; }
    public long    getSamplingProfilerIntervalMs(){ return samplingProfilerIntervalMs; }
    public boolean isTraceEnabled()               { return traceEnabled; }
    public String  getTracePackages()             { return tracePackages; }
    public int     getTraceSampleRate()           { return traceSampleRate; }
    public int     getTraceMaxDepth()             { return traceMaxDepth; }
    public int     getTraceMaxSpans()             { return traceMaxSpans; }
    public boolean isAllocDetailEnabled()         { return allocDetailEnabled; }
    public boolean isLineProfilingConfigured()    { return lineProfilingConfigured; }
    public boolean isLineProfilingActive()         { return lineProfilingConfigured && !linePackages.isBlank(); }
    public String  getLineMode()                  { return lineMode; }
    public boolean isSampledLineProfilingActive() {
        return isLineProfilingActive() && "sampled".equals(lineMode);
    }
    public boolean isDeterministicLineProfilingActive() {
        return isLineProfilingActive() && "deterministic".equals(lineMode);
    }
    public String  getLinePackages()              { return linePackages; }
    public long    getLineSampleIntervalMs()      { return lineSampleIntervalMs; }
    public int     getLineMaxSamplesPerTrace()    { return lineMaxSamplesPerTrace; }
    public int     getLineMaxLinesPerTrace()      { return lineMaxLinesPerTrace; }
    public int     getLineMaxTracePayloadBytes()  { return lineMaxTracePayloadBytes; }
    public boolean isLineAllocEnabled()           { return lineAllocEnabled; }
    public boolean isLineAllocationProfilingActive() {
        return isLineProfilingActive() && lineAllocEnabled;
    }
    public boolean isSourceViewConfigured()       { return sourceViewEnabled; }
    public boolean isSourceViewActive() {
        return sourceViewEnabled && !sourceRoots.isBlank() && !linePackages.isBlank();
    }
    public String  getSourceRoots()              { return sourceRoots; }
    public int     getSourceContextLines()       { return sourceContextLines; }
    public boolean isRequestDebugSnapshotConfigured() { return debugSnapshotEnabled; }
    public boolean isRequestDebugSnapshotActive() {
        return debugSnapshotEnabled && traceEnabled && !tracePackages.isBlank();
    }
    public boolean isDebugSnapshotCaptureArgs()  { return debugSnapshotCaptureArgs; }
    public boolean isDebugSnapshotCaptureReturn(){ return debugSnapshotCaptureReturn; }
    public int     getDebugMaxSnapshotsPerTrace(){ return debugMaxSnapshotsPerTrace; }
    public int     getDebugMaxSnapshotsPerSpan() { return debugMaxSnapshotsPerSpan; }
    public int     getDebugMaxValueLength()      { return debugMaxValueLength; }
    public boolean isLogCaptureEnabled()         { return logCaptureEnabled; }
    public int     getLogMaxEvents()             { return logMaxEvents; }
    public boolean isJfrEnabled()                { return jfrEnabled; }
    public int     getJfrMaxEvents()             { return jfrMaxEvents; }
    public long    getJfrThresholdMs()           { return jfrThresholdMs; }

    public boolean isLineProfilingTargetClass(String className) {
        return isLineProfilingActive()
            && matchesPackageList(className, linePackages)
            && !isExcludedLineProfilingClass(className);
    }

    public boolean isSourceViewTargetClass(String className) {
        return isSourceViewActive()
            && matchesPackageList(className, linePackages)
            && !isExcludedLineProfilingClass(className);
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private static void loadPropertiesFile(Properties props) {
        // Look for the file in the working directory
        try (InputStream in = new FileInputStream("jvm-profiler.properties")) {
            props.load(in);
            log.info("Loaded jvm-profiler.properties");
        } catch (IOException e) {
            // File not found is normal — all config is optional
            log.fine("No jvm-profiler.properties found — using defaults");
        }
    }

    private static void applySystemProperties(Properties props) {
        // Check all known property keys and pull from system properties
        String[] keys = {
            "profiler.http.port",
            "profiler.http.host",
            "profiler.auth.token",
            "profiler.http.cors.enabled",
            "profiler.http.cors.allowed.origins",
            "profiler.sampling.interval.ms",
            "profiler.cpu.sampling.interval.ms",
            "profiler.instance.id",
            "profiler.persistence.enabled",
            "profiler.persistence.path",
            "profiler.persistence.retention.days",
            "profiler.sampling.adaptive.enabled",
            "profiler.sampling.adaptive.max.rps",
            "profiler.sampling.adaptive.multiplier",
            "profiler.alert.gc.overhead.threshold",
            "profiler.alert.webhook.url",
            "profiler.leak.detection.window.ms",
            "profiler.sampling.profiler.enabled",
            "profiler.sampling.profiler.interval.ms",
            "profiler.trace.enabled",
            "profiler.trace.packages",
            "profiler.trace.sample.rate",
            "profiler.trace.max.depth",
            "profiler.trace.max.spans",
            "profiler.trace.alloc.detail.enabled",
            "profiler.line.enabled",
            "profiler.line.mode",
            "profiler.line.packages",
            "profiler.line.sample.interval.ms",
            "profiler.line.max.samples.per.trace",
            "profiler.line.max.lines.per.trace",
            "profiler.line.max.trace.payload.bytes",
            "profiler.line.alloc.enabled",
            "profiler.source.enabled",
            "profiler.source.roots",
            "profiler.source.context.lines",
            "profiler.debug.enabled",
            "profiler.debug.capture.args",
            "profiler.debug.capture.return",
            "profiler.debug.max.snapshots.per.trace",
            "profiler.debug.max.snapshots.per.span",
            "profiler.debug.max.value.length",
            "profiler.logs.enabled",
            "profiler.logs.max.events",
            "profiler.jfr.enabled",
            "profiler.jfr.max.events",
            "profiler.jfr.threshold.ms"
        };
        for (String key : keys) {
            String val = System.getProperty(key);
            if (val != null) props.setProperty(key, val);
        }
    }

    private static int parseInt(Properties p, String key, int def) {
        try { return Integer.parseInt(p.getProperty(key, String.valueOf(def))); }
        catch (NumberFormatException e) {
            log.warning("Invalid value for " + key + " — using default " + def);
            return def;
        }
    }

    private static long parseLong(Properties p, String key, long def) {
        try { return Long.parseLong(p.getProperty(key, String.valueOf(def))); }
        catch (NumberFormatException e) {
            log.warning("Invalid value for " + key + " — using default " + def);
            return def;
        }
    }

    private static double parseDouble(Properties p, String key, double def) {
        try { return Double.parseDouble(p.getProperty(key, String.valueOf(def))); }
        catch (NumberFormatException e) {
            log.warning("Invalid value for " + key + " — using default " + def);
            return def;
        }
    }

    private static int enforceIntRange(Properties p, String key, int def, int min, int max) {
        int value = parseInt(p, key, def);
        if (value < min) {
            log.warning(key + "=" + value + " is invalid. Resetting to " + def);
            return def;
        }
        if (value > max) {
            log.warning(key + "=" + value + " is above safety max " + max
                + ". Clamping to " + max);
            return max;
        }
        return value;
    }

    private static boolean acceptsAgentArgContinuation(String key) {
        return "profiler.trace.packages".equals(key)
            || "profiler.line.packages".equals(key)
            || "profiler.source.roots".equals(key)
            || "profiler.http.cors.allowed.origins".equals(key);
    }

    private static String normalizePackageList(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) return "";
        Set<String> prefixes = new LinkedHashSet<>();
        for (String raw : rawValue.split(",")) {
            String prefix = normalizePackagePrefix(raw);
            if (!prefix.isBlank()) prefixes.add(prefix);
        }
        return String.join(",", prefixes);
    }

    private static String normalizeLineMode(String rawValue) {
        String mode = rawValue == null
            ? DEFAULT_LINE_MODE
            : rawValue.trim().toLowerCase(Locale.ROOT);
        if ("sampled".equals(mode) || "deterministic".equals(mode)) {
            return mode;
        }
        log.warning("profiler.line.mode=" + rawValue
            + " is invalid. Resetting to " + DEFAULT_LINE_MODE);
        return DEFAULT_LINE_MODE;
    }

    private static String normalizeCommaList(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) return "";
        Set<String> values = new LinkedHashSet<>();
        for (String raw : rawValue.split(",")) {
            String value = raw.trim();
            if (!value.isBlank()) values.add(value);
        }
        return String.join(",", values);
    }

    private static String normalizePackagePrefix(String raw) {
        if (raw == null) return "";
        String prefix = raw.trim();
        while (prefix.endsWith(".*")) {
            prefix = prefix.substring(0, prefix.length() - 2).trim();
        }
        while (prefix.endsWith(".")) {
            prefix = prefix.substring(0, prefix.length() - 1).trim();
        }
        return prefix;
    }

    private static boolean matchesPackageList(String className, String packageList) {
        if (className == null || className.isBlank()
                || packageList == null || packageList.isBlank()) {
            return false;
        }
        for (String raw : packageList.split(",")) {
            String prefix = normalizePackagePrefix(raw);
            if (prefix.isBlank()) continue;
            if (className.equals(prefix) || className.startsWith(prefix + ".")) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsOnlyExcludedLinePackages(String packageList) {
        if (packageList == null || packageList.isBlank()) return false;
        boolean sawPrefix = false;
        for (String raw : packageList.split(",")) {
            String prefix = normalizePackagePrefix(raw);
            if (prefix.isBlank()) continue;
            sawPrefix = true;
            if (!isExcludedLineProfilingClass(prefix)
                    && !isExcludedLineProfilingClass(prefix + ".Example")) {
                return false;
            }
        }
        return sawPrefix;
    }

    private static boolean isExcludedLineProfilingClass(String className) {
        if (className == null || className.isBlank()) return true;
        for (String prefix : LINE_PROFILING_EXCLUDED_PREFIXES) {
            if (className.startsWith(prefix)) return true;
        }
        return false;
    }

    private static boolean isLoopbackHost(String host) {
        if (host == null || host.isBlank()) return false;

        String normalized = host.trim()
            .replace("[", "")
            .replace("]", "")
            .toLowerCase(Locale.ROOT);
        if (normalized.equals("localhost")
                || normalized.equals("127.0.0.1")
                || normalized.equals("::1")
                || normalized.equals("0:0:0:0:0:0:0:1")) {
            return true;
        }
        if (normalized.equals("0.0.0.0") || normalized.equals("::")) {
            return false;
        }

        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (Exception e) {
            return false;
        }
    }

    private static String resolveHostname() {
        try { return InetAddress.getLocalHost().getHostName(); }
        catch (Exception e) { return "unknown"; }
    }
}
