package agent.core;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.util.Locale;
import java.util.Properties;
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
                        int traceMaxDepth, int traceMaxSpans, boolean allocDetailEnabled) {
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
                        default                  -> kv[0].trim();
                    };
                    // Only set if not already set by higher-priority source
                    props.putIfAbsent(key, kv[1].trim());
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

        // Tracing is a no-op without target packages — refuse to instrument "everything".
        if (traceEnabled && tracePackages.isBlank()) {
            log.warning("profiler.trace.enabled=true but profiler.trace.packages is empty — "
                + "method tracing will stay OFF (set e.g. profiler.trace.packages=com.example).");
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
            + " allocDetail=" + allocDetailEnabled);

        return new AgentConfig(port, host, interval, id, cpuSamplingInterval,
            authToken, corsEnabled, corsAllowedOrigins,
            persistenceEnabled, persistencePath, retentionDays,
            adaptiveEnabled, maxRps, throttleMultiplier, gcOverheadThreshold,
            webhookUrl, leakWindowMs,
            samplingProfilerEnabled, samplingProfilerMs, traceEnabled, tracePackages,
            traceSampleRate, traceMaxDepth, traceMaxSpans, allocDetailEnabled);
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
            "profiler.trace.alloc.detail.enabled"
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
