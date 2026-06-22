package agent.http;

import agent.core.AgentConfig;
import agent.core.CollectorRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfilerHttpServerContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build();
    private static final String TOKEN = "contract-token-123456789";

    @Test
    void authProtectedRoutesRequireBearerToken() throws Exception {
        try (RunningServer server = start("auth.token=" + TOKEN)) {
            assertEquals(401, get(server, "/profiler/status", null).statusCode());
            assertEquals(401, get(server, "/profiler/status", "wrong-token").statusCode());

            HttpResponse<String> response = get(server, "/profiler/status", TOKEN);
            assertEquals(200, response.statusCode());
            assertEquals("status", json(response).get("resource"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void apiCatalogIncludesStableRoutesCapabilitiesAndLinks() throws Exception {
        try (RunningServer server = start("auth.token=" + TOKEN)) {
            HttpResponse<String> response = get(server, "/profiler/api", TOKEN);
            assertEquals(200, response.statusCode());

            Map<String, Object> body = json(response);
            assertEquals("api", body.get("resource"));
            assertEquals("1", body.get("apiVersion"));
            assertEquals(true, body.get("authRequired"));

            Map<String, Object> capabilities =
                (Map<String, Object>) body.get("capabilities");
            assertEquals(true, capabilities.get("selfMonitoring"));
            assertEquals(true, capabilities.get("cpuMonitoring"));

            Map<String, Object> links = (Map<String, Object>) body.get("links");
            assertEquals("/profiler/dashboard", links.get("dashboard"));
            assertEquals("/profiler/dashboard.js", links.get("dashboardScript"));

            List<Map<String, Object>> routes =
                (List<Map<String, Object>>) body.get("routes");
            assertEquals(routes.size(), ((Number) body.get("routeCount")).intValue());
            assertTrue(routes.stream().anyMatch(route ->
                "GET".equals(route.get("method"))
                    && "/profiler/dashboard.js".equals(route.get("path"))));
            assertTrue(routes.stream().anyMatch(route ->
                "GET".equals(route.get("method"))
                    && "/profiler/status".equals(route.get("path"))));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void statusIncludesSelfMonitoringAndDashboardLinks() throws Exception {
        try (RunningServer server = start("auth.token=" + TOKEN)) {
            HttpResponse<String> response = get(server, "/profiler/status", TOKEN);
            assertEquals(200, response.statusCode());

            Map<String, Object> body = json(response);
            assertEquals("status", body.get("resource"));
            assertTrue(body.containsKey("internalErrors"));
            assertTrue(body.containsKey("totalInternalErrors"));
            assertTrue(body.containsKey("selfMonitoringStatus"));

            Map<String, Object> links = (Map<String, Object>) body.get("links");
            assertEquals("/profiler/dashboard.js", links.get("dashboardScript"));
        }
    }

    @Test
    void dashboardHtmlAndScriptAssetsAreServed() throws Exception {
        try (RunningServer server = start("auth.token=" + TOKEN)) {
            HttpResponse<String> html = get(server, "/profiler/dashboard", TOKEN);
            assertEquals(200, html.statusCode());
            assertTrue(html.body().contains(
                "<script src=\"/profiler/dashboard.js\"></script>"));

            HttpResponse<String> script = get(server, "/profiler/dashboard.js", TOKEN);
            assertEquals(200, script.statusCode());
            assertTrue(script.headers().firstValue("content-type").orElse("")
                .contains("application/javascript"));
            assertTrue(script.body().contains("\"use strict\";"));
            assertTrue(script.body().contains("fetch("));

            HttpResponse<String> publicScript = get(server, "/profiler/dashboard.js", null);
            assertEquals(200, publicScript.statusCode());
            assertTrue(publicScript.body().contains("\"use strict\";"));
        }
    }

    @Test
    void corsPreflightHonorsConfiguredOrigin() throws Exception {
        try (RunningServer server = start("auth.token=" + TOKEN
                + ",cors.enabled=true,cors.origins=http://localhost:3000")) {
            HttpRequest request = HttpRequest.newBuilder(server.uri("/profiler/status"))
                .timeout(Duration.ofSeconds(5))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "http://localhost:3000")
                .build();

            HttpResponse<String> response = HTTP.send(request,
                HttpResponse.BodyHandlers.ofString());
            assertEquals(204, response.statusCode());
            assertEquals("http://localhost:3000",
                response.headers().firstValue("access-control-allow-origin").orElse(""));
        }
    }

    private static RunningServer start(String args) {
        AgentConfig config = AgentConfig.load(args + ",profiler.persistence.enabled=false");
        CollectorRegistry registry = new CollectorRegistry(config.getBaseIntervalMs());
        Javalin app = new ProfilerHttpServer(registry, config)
            .createApp()
            .start("127.0.0.1", 0);
        return new RunningServer(app);
    }

    private static HttpResponse<String> get(RunningServer server, String path,
                                            String token)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(server.uri(path))
            .timeout(Duration.ofSeconds(5))
            .GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Map<String, Object> json(HttpResponse<String> response)
            throws IOException {
        return JSON.readValue(response.body(), new TypeReference<>() {});
    }

    private record RunningServer(Javalin app) implements AutoCloseable {
        URI uri(String path) {
            return URI.create("http://127.0.0.1:" + app.port() + path);
        }

        @Override
        public void close() {
            app.stop();
        }
    }
}
