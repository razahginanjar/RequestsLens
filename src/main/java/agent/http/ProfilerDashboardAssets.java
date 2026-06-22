package agent.http;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

final class ProfilerDashboardAssets {

    private static final Logger log =
        Logger.getLogger(ProfilerDashboardAssets.class.getName());
    private static final String DASHBOARD_RESOURCE = "/dashboard/index.html";
    private static final String DASHBOARD_SCRIPT_RESOURCE = "/dashboard/dashboard.js";

    private volatile String htmlCache;
    private volatile String scriptCache;

    String html() {
        String cached = htmlCache;
        if (cached != null) return cached;

        synchronized (this) {
            if (htmlCache != null) return htmlCache;
            htmlCache = readResource(DASHBOARD_RESOURCE, ProfilerDashboardAssets::fallbackHtml);
            return htmlCache;
        }
    }

    String script() {
        String cached = scriptCache;
        if (cached != null) return cached;

        synchronized (this) {
            if (scriptCache != null) return scriptCache;
            scriptCache = readResource(DASHBOARD_SCRIPT_RESOURCE,
                ProfilerDashboardAssets::fallbackScript);
            return scriptCache;
        }
    }

    private String readResource(String resource, ResourceFallback fallback) {
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            if (in == null) {
                log.warning("Dashboard resource not found on classpath: " + resource);
                return fallback.value();
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warning("Failed to read dashboard resource " + resource + ": "
                + e.getMessage());
            return fallback.value();
        }
    }

    private static String fallbackHtml() {
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

    private static String fallbackScript() {
        return """
            "use strict";
            console.error("RequestLens dashboard script resource was not bundled.");
            """;
    }

    private interface ResourceFallback {
        String value();
    }
}
