package agent.integration;

import agent.persistence.SqliteRepository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end smoke/regression tests for the real Java agent deployment shape.
 *
 * <p>These tests launch the Spring Boot demo fat jar with
 * {@code -javaagent:target/requestlens-agent-...jar}. That catches the class of
 * errors unit tests cannot see: shading mistakes, Spring Boot classloader access,
 * Byte Buddy advice binding, and Javalin startup behavior.
 */
class AgentSpringBootIT {

    private static final Path ROOT = Path.of("").toAbsolutePath();
    private static final Path AGENT_JAR =
        ROOT.resolve("target/requestlens-agent-1.0.0-SNAPSHOT.jar");
    private static final Path DEMO_POM = ROOT.resolve("demo/pom.xml");
    private static final Path DEMO_JAR = ROOT.resolve("demo/target/profiler-demo-app.jar");
    private static final Path LOG_DIR = ROOT.resolve("target/it-logs");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build();

    @BeforeAll
    static void buildDemoJar() throws Exception {
        assertTrue(Files.exists(AGENT_JAR),
            "Agent jar does not exist. Run integration tests via `mvn verify` so package runs first.");
        Files.createDirectories(LOG_DIR);

        ProcessBuilder pb = new ProcessBuilder(
            mavenCommand(), "-q", "-f", DEMO_POM.toString(), "-DskipTests", "package");
        pb.directory(ROOT.toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes());
        assertTrue(process.waitFor(180, TimeUnit.SECONDS), "Timed out building demo jar");
        assertEquals(0, process.exitValue(), "Demo build failed:\n" + output);
        assertTrue(Files.exists(DEMO_JAR), "Demo jar was not created: " + DEMO_JAR);
    }

    @Test
    void springBootAgentPublishesRuntimeProfilingData() throws Exception {
        int appPort = freePort();
        int agentPort = freePort();
        Path log = LOG_DIR.resolve("agent-runtime.log");
        Process app = startDemo("runtime", appPort, agentPort, log,
            "line.enabled=true",
            "line.mode=deterministic",
            "line.packages=demo",
            "line.interval=1",
            "line.alloc.enabled=true",
            "source.enabled=true",
            "source.roots=demo/src/main/java",
            "source.context.lines=3");

        try {
            waitForText("http://127.0.0.1:" + appPort + "/hello",
                body -> body.contains("hello"), Duration.ofSeconds(45), app, log);

            String statusUrl = "http://127.0.0.1:" + agentPort + "/profiler/status";
            JsonNode status = waitForJson(statusUrl,
                json -> json.path("traceEnabled").asBoolean(false)
                    && json.path("samplingProfiler").asBoolean(false)
                    && json.path("lastCpuSampleTimestampMs").asLong() > 0,
                Duration.ofSeconds(30), app, log);
            assertEquals("demo", status.path("tracePackages").asText());
            assertTrue(status.path("profilerHttpRequests").asLong() > 0);
            assertTrue(status.path("bufferCapacities").path("heap").asInt() > 0);
            assertTrue(status.path("bufferCapacities").path("cpu").asInt() > 0);
            assertTrue(status.path("bufferCapacities").path("endpoint").asInt() > 0);
            assertTrue(status.path("cpuSamplingIntervalMs").asLong() >= 250L);
            assertTrue(status.path("lastCpuSampleTimestampMs").asLong() > 0);
            assertTrue(status.has("processCpuLoadPercent"));
            assertTrue(status.has("systemCpuLoadPercent"));
            assertTrue(status.has("agentThreadCpuLoadPercent"));
            assertTrue(status.has("droppedEndpointSamples"));
            assertTrue(status.has("droppedCpuSamples"));
            assertTrue(status.has("droppedTraces"));
            assertTrue(status.path("lineProfilingConfigured").asBoolean(false));
            assertTrue(status.path("lineProfilingEnabled").asBoolean(false));
            assertEquals("deterministic", status.path("lineMode").asText());
            assertFalse(status.path("sampledLineProfilingEnabled").asBoolean(true));
            assertTrue(status.path("deterministicLineProfilingEnabled").asBoolean(false));
            assertEquals("demo", status.path("linePackages").asText());
            assertEquals(1L, status.path("lineSampleIntervalMs").asLong());
            assertEquals(1000, status.path("lineMaxSamplesPerTrace").asInt());
            assertEquals(300, status.path("lineMaxLinesPerTrace").asInt());
            assertEquals(262_144, status.path("lineMaxTracePayloadBytes").asInt());
            assertTrue(status.path("lineAllocEnabled").asBoolean(false));
            assertTrue(status.path("sourceViewConfigured").asBoolean(false));
            assertTrue(status.path("sourceViewEnabled").asBoolean(false));
            assertEquals(1, status.path("sourceRootCount").asInt());
            assertEquals(3, status.path("sourceContextLines").asInt());
            assertTrue(status.has("lineActiveRequests"));
            assertTrue(status.has("lineCompletedRequests"));
            JsonNode instrumentation = status.path("instrumentationDiagnostics");
            assertTrue(instrumentation.path("discoveredTraceClasses").asInt() > 0);
            assertTrue(instrumentation.path("transformedTraceClasses").asInt() > 0);
            assertTrue(instrumentation.path("transformedTraceMethods").asInt() > 0);
            assertTrue(instrumentation.path("lineNumberDiagnosticsEnabled").asBoolean(false));
            assertTrue(instrumentation.path("lineNumberClasses").asInt() > 0);
            assertEquals(0, instrumentation.path("recentErrors").size());
            JsonNode packageDiscovery = status.path("packageDiscovery");
            assertTrue(packageDiscovery.path("available").asBoolean(false));
            assertEquals("demo", packageDiscovery.path("suggestedPackage").asText());
            assertTrue(packageDiscovery.path("topPackages").isArray());
            assertEquals("1", status.path("apiVersion").asText());
            assertEquals("status", status.path("resource").asText());
            assertTrue(status.path("generatedAtMs").asLong() > 0);
            assertTrue(status.has("selfMonitoringStatus"));
            assertTrue(status.path("selfMonitoringIssues").isArray());
            assertTrue(status.path("selfMonitoringIssueCount").asLong() >= 0);
            assertTrue(status.path("totalDroppedSamples").asLong() >= 0);
            assertTrue(status.path("totalInternalErrors").asLong() >= 0);
            assertTrue(status.path("lastCpuSampleAgeMs").asLong() >= 0);
            assertEquals("/profiler/api", status.path("links").path("api").asText());

            JsonNode api = getJson("http://127.0.0.1:" + agentPort + "/profiler/api");
            assertEquals("1", api.path("apiVersion").asText());
            assertEquals("api", api.path("resource").asText());
            assertFalse(api.path("authRequired").asBoolean(true));
            assertTrue(api.path("routeCount").asInt() >= 16);
            assertTrue(api.path("capabilities").path("selfMonitoring").asBoolean(false));
            assertTrue(api.path("capabilities").path("traceConfigured").asBoolean(false));
            assertTrue(api.path("capabilities").path("cpuMonitoring").asBoolean(false));
            assertTrue(api.path("capabilities").path("lineProfilingConfigured").asBoolean(false));
            assertTrue(api.path("capabilities").path("lineProfilingEnabled").asBoolean(false));
            assertEquals("deterministic", api.path("capabilities").path("lineMode").asText());
            assertFalse(api.path("capabilities").path("sampledLineProfiling").asBoolean(true));
            assertTrue(api.path("capabilities").path("deterministicLineProfiling").asBoolean(false));
            assertTrue(api.path("capabilities").path("deterministicLineSelfTime").asBoolean(false));
            assertTrue(api.path("capabilities").path("lineHotspots").asBoolean(false));
            assertTrue(api.path("capabilities").path("sourceFreeMethodLines").asBoolean(false));
            assertTrue(api.path("capabilities").path("lineAllocationDetail").asBoolean(false));
            assertTrue(api.path("capabilities").path("externalSqlSpans").asBoolean(false));
            assertTrue(api.path("capabilities").path("externalHttpSpans").asBoolean(false));
            assertTrue(api.path("capabilities").path("sourceViewEnabled").asBoolean(false));
            assertTrue(api.path("capabilities").path("samplingProfilerAvailable").asBoolean(false));
            assertTrue(api.path("capabilities").path("instrumentationDiagnostics").asBoolean(false));
            assertTrue(api.path("capabilities").path("packageDiscovery").asBoolean(false));
            assertTrue(apiRouteExists(api, "GET", "/profiler/status"));
            assertTrue(apiRouteExists(api, "GET", "/profiler/cpu"));
            assertTrue(apiRouteExists(api, "GET", "/profiler/source"));
            assertTrue(apiRouteExists(api, "GET", "/profiler/package-discovery"));
            assertTrue(apiRouteExists(api, "GET", "/profiler/dashboard"));

            JsonNode cpu = getJson("http://127.0.0.1:" + agentPort + "/profiler/cpu");
            assertEquals("cpu", cpu.path("resource").asText());
            assertTrue(cpu.path("sampleCount").asInt() > 0);
            assertTrue(cpu.path("current").has("processCpuLoadPercent"));

            JsonNode discovered = getJson("http://127.0.0.1:" + agentPort
                + "/profiler/package-discovery");
            assertEquals("package-discovery", discovered.path("resource").asText());
            assertTrue(discovered.path("available").asBoolean(false));
            assertEquals("demo", discovered.path("suggestedPackage").asText());
            assertEquals("demo", discovered.path("suggestedTracePackages").asText());

            for (int i = 0; i < 4; i++) {
                assertTrue(getText("http://127.0.0.1:" + appPort + "/slow").contains("slow"));
            }
            for (int i = 0; i < 4; i++) {
                assertTrue(getText("http://127.0.0.1:" + appPort + "/cpu").contains("cpu"));
            }
            assertTrue(getText("http://127.0.0.1:" + appPort + "/items/101").contains("item: 101"));
            assertTrue(getText("http://127.0.0.1:" + appPort + "/items/202").contains("item: 202"));
            assertTrue(getText("http://127.0.0.1:" + appPort + "/external")
                .contains("external: http=remote sql=42"));

            JsonNode endpoints = waitForJson(
                "http://127.0.0.1:" + agentPort + "/profiler/endpoints",
                json -> containsEndpoint(json, "/slow")
                    && containsEndpoint(json, "/cpu")
                    && containsEndpoint(json, "/items/{id}")
                    && containsEndpoint(json, "/external"),
                Duration.ofSeconds(25), app, log);
            assertEquals("endpoints", endpoints.path("resource").asText());
            assertTrue(endpoints.path("endpointCount").asInt() >= 2);
            assertFalse(containsEndpoint(endpoints, "/items/101"),
                "Endpoint stats should use Spring's route pattern, not raw path variables");
            JsonNode itemEndpoint = endpointForPath(endpoints, "/items/{id}");
            assertNotNull(itemEndpoint);
            assertTrue(itemEndpoint.path("requestCount").asLong() >= 2);
            JsonNode cpuEndpoint = endpointForPath(endpoints, "/cpu");
            assertNotNull(cpuEndpoint);
            assertTrue(cpuEndpoint.has("avgCpuMs"));
            assertTrue(cpuEndpoint.has("avgCpuToWallPercent"));

            JsonNode beans = waitForJson(
                "http://127.0.0.1:" + agentPort + "/profiler/beans",
                json -> json.path("beanCount").asInt() > 0,
                Duration.ofSeconds(25), app, log);
            assertEquals("beans", beans.path("resource").asText());
            assertTrue(beans.path("beans").isArray());

            JsonNode traces = waitForJson(
                "http://127.0.0.1:" + agentPort + "/profiler/traces",
                json -> traceIdForPath(json, "/slow") != null
                    && traceIdForPath(json, "/external") != null,
                Duration.ofSeconds(25), app, log);
            assertEquals("traces", traces.path("resource").asText());
            String traceId = traceIdForPath(traces, "/slow");
            assertNotNull(traceId);
            JsonNode traceSummary = traceSummaryForPath(traces, "/slow");
            assertNotNull(traceSummary);
            assertTrue(traceSummary.path("deterministicLineCount").asInt() > 0);
            assertTrue(traceSummary.has("deterministicLineSelfWallNs"));
            assertTrue(traceSummary.has("deterministicLineSelfCpuNs"));
            assertTrue(traceSummary.path("deterministicLineAllocationCount").asLong() > 0);
            assertTrue(traceSummary.path("deterministicLineAllocatedBytes").asLong() > 0);
            assertTrue(traceSummary.has("droppedLineHotspots"));

            JsonNode trace = getJson("http://127.0.0.1:" + agentPort + "/profiler/trace/" + traceId);
            assertEquals("/slow", trace.path("path").asText());
            assertTrue(trace.path("totalWallNs").asLong() > 0);
            assertFalse(trace.path("truncated").asBoolean(true));
            assertTrue(trace.path("capturedSpans").asInt() > 0);
            assertEquals(0, trace.path("droppedSpans").asInt());
            assertTrue(trace.path("deterministicLineCount").asInt() > 0);
            assertTrue(trace.path("deterministicLineSelfWallNs").asLong() >= 0L);
            assertTrue(trace.path("deterministicLineSelfCpuNs").asLong() >= 0L);
            assertTrue(treeContainsLineStats(trace.path("root"), "demo.DemoApplication", "slow"),
                "Expected deterministic line stats to include demo.DemoApplication.slow");
            assertTrue(treeContainsLineAllocation(trace.path("root"), "demo.DemoApplication", "slow"),
                "Expected deterministic line allocation data for demo.DemoApplication.slow");
            assertTrue(treeContainsSpan(trace.path("root"), "demo.DemoApplication", "slow"),
                "Expected trace tree to include demo.DemoApplication.slow");
            int sourceLine = lineHotspotLine(trace, "demo.DemoApplication", "slow");
            assertTrue(sourceLine > 0, "Expected a source line for demo.DemoApplication.slow");
            JsonNode source = getJson("http://127.0.0.1:" + agentPort
                + "/profiler/source?className=demo.DemoApplication&line=" + sourceLine);
            assertEquals("source", source.path("resource").asText());
            assertTrue(source.path("sourceAvailable").asBoolean(false));
            assertEquals("demo.DemoApplication", source.path("className").asText());
            assertEquals("demo/DemoApplication.java", source.path("sourcePath").asText());
            assertTrue(source.path("lines").isArray());
            assertTrue(source.path("lines").size() > 0);
            assertTrue(sourceLinesContainHighlight(source, sourceLine));

            Map<String, JsonNode> allocs = new HashMap<>();
            collectAllocations(trace.path("root"), allocs);
            assertAllocationTypePresent(allocs, "byte[]");
            assertAllocationTypePresent(allocs, "demo.DemoApplication.Item");
            assertAllocationTypePresent(allocs, "demo.DemoApplication.Item[]");
            assertAllocationTypePresent(allocs, "java.lang.Object[]");
            assertAllocationTypePresent(allocs, "int[][]");

            String externalTraceId = traceIdForPath(traces, "/external");
            assertNotNull(externalTraceId);
            JsonNode externalSummary = traceSummaryForPath(traces, "/external");
            assertNotNull(externalSummary);
            assertTrue(externalSummary.path("externalSpanCount").asLong() >= 2L,
                externalSummary.toString());
            assertTrue(externalSummary.path("sqlSpanCount").asLong() >= 1L,
                externalSummary.toString());
            assertTrue(externalSummary.path("httpSpanCount").asLong() >= 1L,
                externalSummary.toString());
            JsonNode externalTrace = getJson("http://127.0.0.1:" + agentPort
                + "/profiler/trace/" + externalTraceId);
            assertEquals("/external", externalTrace.path("path").asText());
            assertTrue(externalTrace.path("externalSpanCount").asLong() >= 2L,
                externalTrace.toString());
            assertTrue(externalTrace.path("sqlSpanCount").asLong() >= 1L,
                externalTrace.toString());
            assertTrue(externalTrace.path("httpSpanCount").asLong() >= 1L,
                externalTrace.toString());
            assertTrue(treeContainsSpanKind(externalTrace.path("root"), "sql"),
                externalTrace.toString());
            assertTrue(treeContainsSpanKind(externalTrace.path("root"), "http"),
                externalTrace.toString());
            assertTrue(treeContainsExternalResource(externalTrace.path("root"), "select ? as answer"),
                externalTrace.toString());
            assertTrue(treeContainsExternalResource(externalTrace.path("root"), "/remote"),
                externalTrace.toString());

            JsonNode flame = waitForJson(
                "http://127.0.0.1:" + agentPort + "/profiler/flamegraph",
                json -> json.path("samples").asLong() > 0,
                Duration.ofSeconds(10), app, log);
            assertEquals("root", flame.path("frame").asText());

            JsonNode finalStatus = waitForJson(statusUrl,
                json -> json.path("aggregationCycles").asLong() > 0
                    && json.path("lastAggregationTimestampMs").asLong() > 0,
                Duration.ofSeconds(10), app, log);
            assertEquals(0, finalStatus.path("aggregationErrors").asLong());
            assertEquals(0, finalStatus.path("totalInternalErrors").asLong());
            assertNotEquals("error", finalStatus.path("selfMonitoringStatus").asText());
            assertTrue(finalStatus.path("lastAggregationDurationMs").asLong() >= 0);
            assertTrue(finalStatus.path("profilerHttpRequests").asLong() > 0);

        } finally {
            stop(app);
        }
    }

    @Test
    void targetAppStillStartsWhenAgentHttpPortIsUnavailable() throws Exception {
        int appPort = freePort();
        int blockedAgentPort = freePort();
        Path log = LOG_DIR.resolve("agent-port-conflict.log");

        try (ServerSocket blocker = new ServerSocket(blockedAgentPort)) {
            Process app = startDemo("port-conflict", appPort, blockedAgentPort, log);
            try {
                String body = waitForText("http://127.0.0.1:" + appPort + "/hello",
                    text -> text.contains("hello"), Duration.ofSeconds(45), app, log);
                assertTrue(body.contains("hello"));
                assertTrue(app.isAlive(),
                    "Target app should continue running even if the agent HTTP server failed");
            } finally {
                stop(app);
            }
        }
    }

    @Test
    void profilerHttpApiRequiresBearerTokenWhenConfigured() throws Exception {
        int appPort = freePort();
        int agentPort = freePort();
        Path log = LOG_DIR.resolve("agent-auth.log");
        String token = "test-token-123456789";
        Process app = startDemo("auth", appPort, agentPort, log,
            "auth.token=" + token,
            "cors.enabled=true",
            "cors.origins=http://localhost:3000");

        try {
            waitForText("http://127.0.0.1:" + appPort + "/hello",
                body -> body.contains("hello"), Duration.ofSeconds(45), app, log);

            HttpResponse<String> rejected = waitForResponse(
                "http://127.0.0.1:" + agentPort + "/profiler/status", null,
                response -> response.statusCode() == 401, Duration.ofSeconds(30), app, log);
            assertEquals(401, rejected.statusCode());

            JsonNode status = getJson(
                "http://127.0.0.1:" + agentPort + "/profiler/status", token);
            assertTrue(status.path("authEnabled").asBoolean(false));
            assertEquals("127.0.0.1", status.path("httpHost").asText());
            assertTrue(status.path("corsEnabled").asBoolean(false));
            assertTrue(status.path("profilerHttpRequests").asLong() >= 2);
            assertTrue(status.path("profilerHttpAuthFailures").asLong() >= 1);

            HttpResponse<String> apiRejected = waitForResponse(
                "http://127.0.0.1:" + agentPort + "/profiler/api", null,
                response -> response.statusCode() == 401, Duration.ofSeconds(10), app, log);
            assertEquals(401, apiRejected.statusCode());

            JsonNode api = getJson(
                "http://127.0.0.1:" + agentPort + "/profiler/api", token);
            assertTrue(api.path("authRequired").asBoolean(false));
            assertTrue(apiRouteExists(api, "GET", "/profiler/api"));

            HttpResponse<String> preflight = getOptionsResponse(
                "http://127.0.0.1:" + agentPort + "/profiler/status",
                "http://localhost:3000");
            assertEquals(204, preflight.statusCode());
            assertEquals("http://localhost:3000",
                preflight.headers().firstValue("Access-Control-Allow-Origin").orElse(""));

            HttpResponse<String> dashboard = getResponse(
                "http://127.0.0.1:" + agentPort + "/profiler/dashboard?token=" + token, null);
            assertEquals(200, dashboard.statusCode());
            assertTrue(dashboard.body().contains("RequestLens"));
            assertTrue(dashboard.body().contains("API / Runtime"));
            assertTrue(dashboard.body().contains("traceMeta"));
            assertTrue(dashboard.body().contains("self CPU"));
            assertTrue(dashboard.body().contains("Line hot spots"));
            assertTrue(dashboard.body().contains("Line memory"));
            assertTrue(dashboard.body().contains("Line alloc"));
            assertTrue(dashboard.body().contains("Total dropped"));
            assertTrue(dashboard.body().contains("ahHealth"));
            assertTrue(dashboard.body().contains("traceStats"));
            assertTrue(dashboard.body().contains("traceTabLines"));
            assertTrue(dashboard.body().contains("traceTabSource"));
            assertTrue(dashboard.body().contains("Method lines"));
            assertTrue(dashboard.body().contains("ClassName:lineNumber view"));
            assertTrue(dashboard.body().contains("Line self"));
            assertTrue(dashboard.body().contains("Self wall"));
            assertTrue(dashboard.body().contains("ClassName:lineNumber metrics remain available"));
            assertTrue(dashboard.body().contains("span-kind"));
            assertTrue(dashboard.body().contains("SQL / HTTP"));
            assertTrue(dashboard.body().contains("Instrumentation"));
            assertTrue(dashboard.body().contains("Package suggestion"));
            assertTrue(dashboard.body().contains("diagTraceClasses"));
            assertTrue(dashboard.body().contains("lineHotspotPanel"));
            assertTrue(dashboard.body().contains("line-bar"));
            assertTrue(dashboard.body().contains("lineAllocatedBytes"));
            assertTrue(dashboard.body().contains("Source view"));
            assertTrue(dashboard.body().contains("source-code"));
            assertTrue(dashboard.body().contains("flame-tree"));
            assertTrue(dashboard.body().contains("flameColor"));
        } finally {
            stop(app);
        }
    }

    @Test
    void persistenceHistoryEndpointsReturnStoredSamples() throws Exception {
        int appPort = freePort();
        int agentPort = freePort();
        Path log = LOG_DIR.resolve("agent-persistence.log");
        Path db = LOG_DIR.resolve("agent-persistence-" + System.nanoTime() + ".db");
        Process app = startDemoWithPersistence("persistence", appPort, agentPort, log,
            "profiler.persistence.path=" + db.toAbsolutePath(),
            "profiler.persistence.retention.days=1");

        try {
            waitForText("http://127.0.0.1:" + appPort + "/hello",
                body -> body.contains("hello"), Duration.ofSeconds(45), app, log);

            String statusUrl = "http://127.0.0.1:" + agentPort + "/profiler/status";
            JsonNode status = waitForJson(statusUrl,
                json -> json.path("persistenceAvailable").asBoolean(false),
                Duration.ofSeconds(30), app, log);
            assertTrue(status.path("persistenceConfigured").asBoolean(false));
            assertEquals(SqliteRepository.MAX_QUERY_ROWS,
                status.path("persistenceHistoryLimit").asInt());
            assertTrue(status.path("persistenceQueueCapacity").asInt() > 0);

            long toMs = System.currentTimeMillis() + 30_000L;
            long fromMs = toMs - 120_000L;
            String heapHistoryUrl = "http://127.0.0.1:" + agentPort
                + "/profiler/history/heap?from=" + fromMs + "&to=" + toMs;
            JsonNode heapHistory = waitForJson(heapHistoryUrl,
                json -> json.path("sampleCount").asInt() > 0,
                Duration.ofSeconds(40), app, log);
            assertEquals("history.heap", heapHistory.path("resource").asText());
            assertFalse(heapHistory.path("limited").asBoolean(true));
            assertEquals(SqliteRepository.MAX_QUERY_ROWS, heapHistory.path("limit").asInt());
            assertTrue(heapHistory.path("samples").isArray());

            String gcHistoryUrl = "http://127.0.0.1:" + agentPort
                + "/profiler/history/gc?from=" + fromMs + "&to=" + toMs;
            JsonNode gcHistory = getJson(gcHistoryUrl);
            assertEquals("history.gc", gcHistory.path("resource").asText());
            assertTrue(gcHistory.has("eventCount"));
            assertFalse(gcHistory.path("limited").asBoolean(true));
            assertEquals(SqliteRepository.MAX_QUERY_ROWS, gcHistory.path("limit").asInt());

            String cpuHistoryUrl = "http://127.0.0.1:" + agentPort
                + "/profiler/history/cpu?from=" + fromMs + "&to=" + toMs;
            JsonNode cpuHistory = waitForJson(cpuHistoryUrl,
                json -> json.path("sampleCount").asInt() > 0,
                Duration.ofSeconds(20), app, log);
            assertEquals("history.cpu", cpuHistory.path("resource").asText());
            assertFalse(cpuHistory.path("limited").asBoolean(true));
            assertEquals(SqliteRepository.MAX_QUERY_ROWS, cpuHistory.path("limit").asInt());

            JsonNode finalStatus = waitForJson(statusUrl,
                json -> json.path("persistenceFlushes").asLong() > 0
                    && json.path("persistedHeapSamples").asLong() > 0
                    && json.path("persistedCpuSamples").asLong() > 0,
                Duration.ofSeconds(10), app, log);
            assertEquals(0, finalStatus.path("persistenceFlushFailures").asLong());
            assertTrue(finalStatus.path("lastPersistenceFlushTimestampMs").asLong() > 0);
            assertTrue(Files.exists(db), "SQLite database should be created: " + db);
        } finally {
            stop(app);
        }
    }

    private static Process startDemo(String name, int appPort, int agentPort, Path log)
            throws IOException {
        return startDemo(name, appPort, agentPort, log, new String[0]);
    }

    private static Process startDemo(String name, int appPort, int agentPort, Path log,
                                     String... extraAgentArgs) throws IOException {
        return startDemo(name, appPort, agentPort, log, false, extraAgentArgs);
    }

    private static Process startDemoWithPersistence(String name, int appPort, int agentPort,
                                                    Path log, String... extraAgentArgs)
            throws IOException {
        return startDemo(name, appPort, agentPort, log, true, extraAgentArgs);
    }

    private static Process startDemo(String name, int appPort, int agentPort, Path log,
                                     boolean persistenceEnabled,
                                     String... extraAgentArgs) throws IOException {
        List<String> agentArgParts = new java.util.ArrayList<>(List.of(
            "port=" + agentPort,
            "trace.enabled=true",
            "trace.packages=demo",
            "trace.sample.rate=1",
            "profiler.persistence.enabled=" + persistenceEnabled,
            "profiler.sampling.profiler.interval.ms=5"));
        for (String extra : extraAgentArgs) {
            if (extra != null && !extra.isBlank()) {
                agentArgParts.add(extra);
            }
        }
        String agentArgs = String.join(",", agentArgParts);

        List<String> command = List.of(
            javaCommand(),
            "-javaagent:" + AGENT_JAR + "=" + agentArgs,
            "-jar", DEMO_JAR.toString(),
            "--server.port=" + appPort);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(ROOT.toFile());
        pb.redirectErrorStream(true);
        pb.redirectOutput(log.toFile());
        return pb.start();
    }

    private static String getText(String url) throws IOException, InterruptedException {
        HttpResponse<String> response = getResponse(url, null);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("GET " + url + " returned HTTP " + response.statusCode()
                + ": " + response.body());
        }
        return response.body();
    }

    private static HttpResponse<String> getResponse(String url, String bearerToken)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(3))
            .GET();
        if (bearerToken != null && !bearerToken.isBlank()) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> getOptionsResponse(String url, String origin)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(3))
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody());
        if (origin != null && !origin.isBlank()) {
            builder.header("Origin", origin);
            builder.header("Access-Control-Request-Method", "GET");
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode getJson(String url) throws IOException, InterruptedException {
        return JSON.readTree(getText(url));
    }

    private static JsonNode getJson(String url, String bearerToken)
            throws IOException, InterruptedException {
        HttpResponse<String> response = getResponse(url, bearerToken);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("GET " + url + " returned HTTP " + response.statusCode()
                + ": " + response.body());
        }
        return JSON.readTree(response.body());
    }

    private static String waitForText(String url, Predicate<String> condition,
                                      Duration timeout, Process process, Path log)
            throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        Throwable lastFailure = null;
        while (System.nanoTime() < deadline) {
            assertProcessAlive(process, log);
            try {
                String body = getText(url);
                if (condition.test(body)) return body;
            } catch (Throwable t) {
                lastFailure = t;
            }
            Thread.sleep(250);
        }
        fail("Timed out waiting for " + url + "\nLast error: " + lastFailure
            + "\nProcess log:\n" + tail(log));
        return null;
    }

    private static JsonNode waitForJson(String url, Predicate<JsonNode> condition,
                                        Duration timeout, Process process, Path log)
            throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        Throwable lastFailure = null;
        JsonNode lastJson = null;
        while (System.nanoTime() < deadline) {
            assertProcessAlive(process, log);
            try {
                JsonNode json = getJson(url);
                lastJson = json;
                if (condition.test(json)) return json;
            } catch (Throwable t) {
                lastFailure = t;
            }
            Thread.sleep(500);
        }
        fail("Timed out waiting for " + url
            + "\nLast JSON: " + lastJson
            + "\nLast error: " + lastFailure
            + "\nProcess log:\n" + tail(log));
        return null;
    }

    private static HttpResponse<String> waitForResponse(
            String url, String bearerToken, Predicate<HttpResponse<String>> condition,
            Duration timeout, Process process, Path log) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        Throwable lastFailure = null;
        HttpResponse<String> lastResponse = null;
        while (System.nanoTime() < deadline) {
            assertProcessAlive(process, log);
            try {
                HttpResponse<String> response = getResponse(url, bearerToken);
                lastResponse = response;
                if (condition.test(response)) return response;
            } catch (Throwable t) {
                lastFailure = t;
            }
            Thread.sleep(500);
        }
        fail("Timed out waiting for " + url
            + "\nLast status: " + (lastResponse == null ? "(none)" : lastResponse.statusCode())
            + "\nLast body: " + (lastResponse == null ? "(none)" : lastResponse.body())
            + "\nLast error: " + lastFailure
            + "\nProcess log:\n" + tail(log));
        return null;
    }

    private static void assertProcessAlive(Process process, Path log) throws IOException {
        if (!process.isAlive()) {
            fail("Target process exited with code " + process.exitValue()
                + "\nProcess log:\n" + tail(log));
        }
    }

    private static boolean containsEndpoint(JsonNode json, String path) {
        return endpointForPath(json, path) != null;
    }

    private static JsonNode endpointForPath(JsonNode json, String path) {
        for (JsonNode endpoint : json.path("endpoints")) {
            if (path.equals(endpoint.path("path").asText())) return endpoint;
        }
        return null;
    }

    private static String traceIdForPath(JsonNode json, String path) {
        JsonNode trace = traceSummaryForPath(json, path);
        return trace == null ? null : trace.path("traceId").asText();
    }

    private static JsonNode traceSummaryForPath(JsonNode json, String path) {
        for (JsonNode trace : json.path("traces")) {
            if (path.equals(trace.path("path").asText())) {
                return trace;
            }
        }
        return null;
    }

    private static boolean apiRouteExists(JsonNode json, String method, String path) {
        for (JsonNode route : json.path("routes")) {
            if (method.equals(route.path("method").asText())
                    && path.equals(route.path("path").asText())) {
                return true;
            }
        }
        return false;
    }

    private static boolean treeContainsSpan(JsonNode node, String className, String methodName) {
        if (node == null || node.isMissingNode()) return false;
        if (className.equals(node.path("className").asText())
                && methodName.equals(node.path("methodName").asText())) {
            return true;
        }
        for (JsonNode child : node.path("children")) {
            if (treeContainsSpan(child, className, methodName)) return true;
        }
        return false;
    }

    private static boolean treeContainsSpanKind(JsonNode node, String kind) {
        if (node == null || node.isMissingNode()) return false;
        if (kind.equals(node.path("spanKind").asText())) return true;
        for (JsonNode child : node.path("children")) {
            if (treeContainsSpanKind(child, kind)) return true;
        }
        return false;
    }

    private static boolean treeContainsExternalResource(JsonNode node, String needle) {
        if (node == null || node.isMissingNode()) return false;
        if (node.path("externalResource").asText().contains(needle)) return true;
        for (JsonNode child : node.path("children")) {
            if (treeContainsExternalResource(child, needle)) return true;
        }
        return false;
    }

    private static boolean treeContainsLineStats(JsonNode node, String className, String methodName) {
        if (node == null || node.isMissingNode()) return false;
        if (className.equals(node.path("className").asText())
                && methodName.equals(node.path("methodName").asText())
                && node.path("lineStats").size() > 0) {
            for (JsonNode stat : node.path("lineStats")) {
                if (stat.path("lineNumber").asInt() > 0
                        && stat.path("hits").asLong() > 0
                        && stat.has("selfWallNs")
                        && stat.has("selfCpuNs")
                        && stat.path("selfWallNs").asLong() >= 0L
                        && stat.path("selfCpuNs").asLong() >= 0L
                        && stat.path("selfWallNs").asLong() <= stat.path("wallNs").asLong()
                        && stat.path("selfCpuNs").asLong() <= stat.path("cpuNs").asLong()) {
                    return true;
                }
            }
        }
        for (JsonNode child : node.path("children")) {
            if (treeContainsLineStats(child, className, methodName)) return true;
        }
        return false;
    }

    private static boolean treeContainsLineAllocation(JsonNode node, String className, String methodName) {
        if (node == null || node.isMissingNode()) return false;
        if (className.equals(node.path("className").asText())
                && methodName.equals(node.path("methodName").asText())
                && node.path("lineStats").size() > 0) {
            for (JsonNode stat : node.path("lineStats")) {
                if (stat.path("lineNumber").asInt() > 0
                        && stat.path("allocationCount").asLong() > 0
                        && stat.path("allocatedBytes").asLong() > 0) {
                    return true;
                }
            }
        }
        for (JsonNode child : node.path("children")) {
            if (treeContainsLineAllocation(child, className, methodName)) return true;
        }
        return false;
    }

    private static boolean lineHotspotsContain(JsonNode trace, String className, String methodName) {
        for (JsonNode hotspot : trace.path("lineHotspots")) {
            if (className.equals(hotspot.path("className").asText())
                    && methodName.equals(hotspot.path("methodName").asText())
                    && hotspot.path("lineNumber").asInt() > 0
                    && hotspot.path("samples").asLong() > 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean lineHotspotsContainAllocation(JsonNode trace, String className, String methodName) {
        for (JsonNode hotspot : trace.path("lineHotspots")) {
            if (className.equals(hotspot.path("className").asText())
                    && methodName.equals(hotspot.path("methodName").asText())
                    && hotspot.path("lineNumber").asInt() > 0
                    && hotspot.path("allocationCount").asLong() > 0
                    && hotspot.path("allocatedBytes").asLong() > 0) {
                return true;
            }
        }
        return false;
    }

    private static int lineHotspotLine(JsonNode trace, String className, String methodName) {
        for (JsonNode hotspot : trace.path("lineHotspots")) {
            if (className.equals(hotspot.path("className").asText())
                    && methodName.equals(hotspot.path("methodName").asText())
                    && hotspot.path("lineNumber").asInt() > 0) {
                return hotspot.path("lineNumber").asInt();
            }
        }
        return -1;
    }

    private static boolean sourceLinesContainHighlight(JsonNode source, int lineNumber) {
        for (JsonNode line : source.path("lines")) {
            if (line.path("lineNumber").asInt() == lineNumber
                    && line.path("highlight").asBoolean(false)) {
                return true;
            }
        }
        return false;
    }

    private static void collectAllocations(JsonNode node, Map<String, JsonNode> allocs) {
        JsonNode byType = node.path("allocByType");
        byType.fields().forEachRemaining(e -> allocs.put(e.getKey(), e.getValue()));
        for (JsonNode child : node.path("children")) {
            collectAllocations(child, allocs);
        }
    }

    private static void assertAllocationTypePresent(Map<String, JsonNode> allocs, String type) {
        JsonNode value = allocs.get(type);
        assertNotNull(value, "Expected allocation type " + type + " in " + allocs.keySet());
        assertTrue(value.path("count").asLong() > 0, "Expected count > 0 for " + type);
        assertTrue(value.path("bytes").asLong() >= 0, "Expected bytes >= 0 for " + type);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void stop(Process process) throws InterruptedException {
        if (process == null || !process.isAlive()) return;
        process.destroy();
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }

    private static String tail(Path file) throws IOException {
        if (!Files.exists(file)) return "(no log file)";
        List<String> lines = Files.readAllLines(file);
        int from = Math.max(0, lines.size() - 80);
        return lines.subList(from, lines.size()).stream().collect(Collectors.joining(System.lineSeparator()));
    }

    private static String javaCommand() {
        String exe = isWindows() ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", exe).toString();
    }

    private static String mavenCommand() {
        String fromEnv = System.getenv("MAVEN_CMD");
        if (fromEnv != null && !fromEnv.isBlank()) return fromEnv;
        return isWindows() ? "mvn.cmd" : "mvn";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}
