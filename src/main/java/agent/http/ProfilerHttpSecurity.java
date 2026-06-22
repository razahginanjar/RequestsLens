package agent.http;

import agent.core.AgentConfig;
import agent.core.CollectorRegistry;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

final class ProfilerHttpSecurity {

    private final CollectorRegistry registry;
    private final AgentConfig config;

    ProfilerHttpSecurity(CollectorRegistry registry, AgentConfig config) {
        this.registry = registry;
        this.config = config;
    }

    void registerPreflightRoutes(JavalinConfig cfg) {
        cfg.routes.options("/profiler/api", this::handlePreflight);
        cfg.routes.options("/profiler/heap", this::handlePreflight);
        cfg.routes.options("/profiler/gc", this::handlePreflight);
        cfg.routes.options("/profiler/logs", this::handlePreflight);
        cfg.routes.options("/profiler/jfr/events", this::handlePreflight);
        cfg.routes.options("/profiler/cpu", this::handlePreflight);
        cfg.routes.options("/profiler/status", this::handlePreflight);
        cfg.routes.options("/profiler/summary", this::handlePreflight);
        cfg.routes.options("/profiler/dashboard", this::handlePreflight);
        cfg.routes.options("/profiler/dashboard.js", this::handlePreflight);
        cfg.routes.options("/profiler/endpoints", this::handlePreflight);
        cfg.routes.options("/profiler/beans", this::handlePreflight);
        cfg.routes.options("/profiler/history/heap", this::handlePreflight);
        cfg.routes.options("/profiler/history/gc", this::handlePreflight);
        cfg.routes.options("/profiler/history/cpu", this::handlePreflight);
        cfg.routes.options("/profiler/leaks", this::handlePreflight);
        cfg.routes.options("/profiler/traces", this::handlePreflight);
        cfg.routes.options("/profiler/trace/{id}", this::handlePreflight);
        cfg.routes.options("/profiler/investigate", this::handlePreflight);
        cfg.routes.options("/profiler/source", this::handlePreflight);
        cfg.routes.options("/profiler/package-discovery", this::handlePreflight);
        cfg.routes.options("/profiler/flamegraph", this::handlePreflight);
        cfg.routes.options("/profiler/async/status", this::handlePreflight);
        cfg.routes.options("/profiler/async/start", this::handlePreflight);
        cfg.routes.options("/profiler/async/stop", this::handlePreflight);
        cfg.routes.options("/profiler/async/collapsed", this::handlePreflight);
        cfg.routes.options("/profiler/async/flamegraph", this::handlePreflight);
    }

    boolean authorize(Context ctx) {
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

    boolean canExposeSensitiveDetails() {
        return config.isAuthEnabled() || config.isLocalOnlyHttpBind();
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
        ctx.header("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    }

    private boolean isOriginAllowed(String origin) {
        for (String allowed : config.getCorsAllowedOrigins().split(",")) {
            if (origin.equals(allowed.trim())) {
                return true;
            }
        }
        return false;
    }

    private static boolean constantTimeEquals(String expected, String candidate) {
        if (expected == null || candidate == null) return false;
        return MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8),
            candidate.getBytes(StandardCharsets.UTF_8));
    }
}
