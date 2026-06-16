package agent.benchmark;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Repeatable local overhead benchmark for the packaged RequestLens agent.
 *
 * <p>This is intentionally not a JUnit test and is not run by {@code mvn verify}.
 * Benchmark numbers are sensitive to host load, CPU power mode, antivirus, and
 * thermal throttling, so the normal test suite should only verify correctness.
 */
public final class AgentOverheadBenchmark {

    private static final Path ROOT = Path.of("").toAbsolutePath();
    private static final Path AGENT_JAR = resolveAgentJar();
    private static final Path DEMO_JAR =
        ROOT.resolve("demo/target/profiler-demo-app.jar");
    private static final Path LOG_DIR = ROOT.resolve("target/benchmark-logs");
    private static final Path RESULT_DIR = ROOT.resolve("target/benchmark-results");

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .version(HttpClient.Version.HTTP_1_1)
        .build();

    private AgentOverheadBenchmark() {}

    public static void main(String[] args) throws Exception {
        BenchmarkConfig config = BenchmarkConfig.fromSystemProperties();
        requireFile(AGENT_JAR, "Agent jar does not exist. Run `mvn package` first.");
        requireFile(DEMO_JAR, "Demo jar does not exist. Run `mvn -f demo/pom.xml package` first.");
        Files.createDirectories(LOG_DIR);
        Files.createDirectories(RESULT_DIR);

        List<Scenario> scenarios = List.of(
            new Scenario("baseline", null),
            new Scenario("agent-live", baseAgentArgs(config, false, 50)),
            new Scenario("agent-trace-sampled", baseAgentArgs(config, true, 50)),
            new Scenario("agent-trace-full", baseAgentArgs(config, true, 1)),
            new Scenario("agent-line-hotspots", lineAgentArgs(config, false)),
            new Scenario("agent-line-memory", lineAgentArgs(config, true))
        );

        List<BenchmarkResult> results = new ArrayList<>();
        System.out.println("Running overhead benchmark: requests=" + config.requests
            + " warmup=" + config.warmupRequests
            + " concurrency=" + config.concurrency
            + " endpoint=" + config.endpoint);

        for (Scenario scenario : scenarios) {
            results.add(runScenario(scenario, config));
        }

        writeReports(results, config);
        System.out.println();
        System.out.println(toMarkdown(results, config));
        System.out.println("Wrote benchmark report to "
            + RESULT_DIR.resolve("overhead-benchmark.md"));
    }

    private static BenchmarkResult runScenario(Scenario scenario, BenchmarkConfig config)
            throws Exception {
        int appPort = freePort();
        int agentPort = freePort();
        Path log = LOG_DIR.resolve(scenario.name + ".log");
        Process app = startDemo(scenario, appPort, agentPort, log);
        String baseUrl = "http://127.0.0.1:" + appPort;
        String url = baseUrl + config.endpoint;

        try {
            waitForText(baseUrl + "/hello", body -> body.contains("hello"),
                Duration.ofSeconds(45), app, log);

            if (config.warmupRequests > 0) {
                runLoad(url, config.warmupRequests, config.concurrency);
            }

            LoadResult load = runLoad(url, config.requests, config.concurrency);
            StatusSnapshot status = scenario.agentArgs == null
                ? StatusSnapshot.unavailable("baseline")
                : readAgentStatus(agentPort, config.token);
            return new BenchmarkResult(
                scenario.name,
                scenario.agentArgs != null,
                config.requests,
                config.concurrency,
                load.elapsedMs,
                load.rps,
                load.avgMs,
                load.p50Ms,
                load.p95Ms,
                load.maxMs,
                status,
                log);
        } finally {
            stop(app);
        }
    }

    private static Process startDemo(Scenario scenario, int appPort, int agentPort, Path log)
            throws IOException {
        List<String> command = new ArrayList<>();
        command.add(javaCommand());
        if (scenario.agentArgs != null) {
            command.add("-javaagent:" + AGENT_JAR + "="
                + scenario.agentArgs.replace("{agentPort}", String.valueOf(agentPort)));
        }
        command.add("-jar");
        command.add(DEMO_JAR.toString());
        command.add("--server.port=" + appPort);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(ROOT.toFile());
        pb.redirectErrorStream(true);
        pb.redirectOutput(log.toFile());
        return pb.start();
    }

    private static LoadResult runLoad(String url, int requests, int concurrency)
            throws Exception {
        long[] latencies = new long[requests];
        AtomicInteger next = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        List<Future<?>> futures = new ArrayList<>();

        long startNs = System.nanoTime();
        for (int worker = 0; worker < concurrency; worker++) {
            futures.add(pool.submit(() -> {
                int index;
                while ((index = next.getAndIncrement()) < requests) {
                    long requestStart = System.nanoTime();
                    HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();
                    HttpResponse<String> response = HTTP.send(
                        req, HttpResponse.BodyHandlers.ofString());
                    long elapsed = System.nanoTime() - requestStart;
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        throw new IOException("GET " + url + " returned HTTP "
                            + response.statusCode() + ": " + response.body());
                    }
                    latencies[index] = elapsed;
                }
                return null;
            }));
        }

        try {
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }

        long elapsedNs = System.nanoTime() - startNs;
        return LoadResult.from(latencies, elapsedNs);
    }

    private static String baseAgentArgs(BenchmarkConfig config, boolean trace, int sampleRate) {
        List<String> args = new ArrayList<>();
        args.add("port={agentPort}");
        args.add("auth.token=" + config.token);
        args.add("profiler.persistence.enabled=false");
        args.add("profiler.sampling.profiler.interval.ms=" + config.samplingProfilerIntervalMs);
        args.add("trace.enabled=" + trace);
        if (trace) {
            args.add("trace.packages=demo");
            args.add("trace.sample.rate=" + sampleRate);
        }
        return String.join(",", args);
    }

    private static String lineAgentArgs(BenchmarkConfig config, boolean lineAllocation) {
        List<String> args = new ArrayList<>();
        args.add(baseAgentArgs(config, true, 1));
        args.add("line.enabled=true");
        args.add("line.packages=demo");
        args.add("line.interval=" + config.lineSampleIntervalMs);
        args.add("line.alloc.enabled=" + lineAllocation);
        return String.join(",", args);
    }

    private static StatusSnapshot readAgentStatus(int agentPort, String token) {
        try {
            HttpRequest req = HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + agentPort + "/profiler/status"))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
            HttpResponse<String> response = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return StatusSnapshot.unavailable("http-" + response.statusCode());
            }
            return StatusSnapshot.fromJson(response.body());
        } catch (Exception e) {
            return StatusSnapshot.unavailable(e.getClass().getSimpleName());
        }
    }

    private static void writeReports(List<BenchmarkResult> results, BenchmarkConfig config)
            throws IOException {
        Files.writeString(RESULT_DIR.resolve("overhead-benchmark.md"),
            toMarkdown(results, config));
        Files.writeString(RESULT_DIR.resolve("overhead-benchmark.csv"),
            toCsv(results));
    }

    private static String toMarkdown(List<BenchmarkResult> results, BenchmarkConfig config) {
        BenchmarkResult baseline = results.get(0);
        StringBuilder out = new StringBuilder();
        out.append("# Agent Overhead Benchmark\n\n");
        out.append("- Timestamp: ").append(Instant.now()).append('\n');
        out.append("- Java: ").append(System.getProperty("java.version")).append('\n');
        out.append("- OS: ").append(System.getProperty("os.name"))
            .append(' ').append(System.getProperty("os.version")).append('\n');
        out.append("- Endpoint: `").append(config.endpoint).append("`\n");
        out.append("- Requests: ").append(config.requests).append('\n');
        out.append("- Warmup requests: ").append(config.warmupRequests).append('\n');
        out.append("- Concurrency: ").append(config.concurrency).append('\n');
        out.append("- Line sample interval: ").append(config.lineSampleIntervalMs).append("ms\n\n");
        out.append("| Scenario | RPS | RPS overhead | Avg ms | P50 ms | P95 ms | Max ms | Self status | Issues | Issue names | Drops | Errors | Agg cycles | Agg ms | HTTP req | Log |\n");
        out.append("| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |\n");
        for (BenchmarkResult r : results) {
            StatusSnapshot status = r.status;
            out.append("| ").append(r.scenario).append(" | ")
                .append(format(r.rps)).append(" | ")
                .append(format(overheadPercent(baseline.rps, r.rps))).append("% | ")
                .append(format(r.avgMs)).append(" | ")
                .append(format(r.p50Ms)).append(" | ")
                .append(format(r.p95Ms)).append(" | ")
                .append(format(r.maxMs)).append(" | `")
                .append(status.status).append("` | ")
                .append(status.issueCount).append(" | ")
                .append(status.issues).append(" | ")
                .append(status.totalDroppedSamples).append(" | ")
                .append(status.totalInternalErrors).append(" | ")
                .append(status.aggregationCycles).append(" | ")
                .append(status.lastAggregationDurationMs).append(" | ")
                .append(status.profilerHttpRequests).append(" | `")
                .append(ROOT.relativize(r.log)).append("` |\n");
        }
        out.append("\nRPS overhead is calculated against the baseline process without the agent.\n");
        out.append("Self status is read from `/profiler/status` after each agent scenario.\n");
        return out.toString();
    }

    static String toCsv(List<BenchmarkResult> results) {
        BenchmarkResult baseline = results.get(0);
        StringBuilder out = new StringBuilder();
        out.append("scenario,agentEnabled,requests,concurrency,elapsedMs,rps,rpsOverheadPercent,avgMs,p50Ms,p95Ms,maxMs,selfMonitoringStatus,selfMonitoringIssueCount,selfMonitoringIssues,totalDroppedSamples,totalInternalErrors,aggregationCycles,lastAggregationDurationMs,profilerHttpRequests,statusNote,log\n");
        for (BenchmarkResult r : results) {
            StatusSnapshot status = r.status;
            out.append(r.scenario).append(',')
                .append(r.agentEnabled).append(',')
                .append(r.requests).append(',')
                .append(r.concurrency).append(',')
                .append(r.elapsedMs).append(',')
                .append(format(r.rps)).append(',')
                .append(format(overheadPercent(baseline.rps, r.rps))).append(',')
                .append(format(r.avgMs)).append(',')
                .append(format(r.p50Ms)).append(',')
                .append(format(r.p95Ms)).append(',')
                .append(format(r.maxMs)).append(',')
                .append(csvField(status.status)).append(',')
                .append(status.issueCount).append(',')
                .append(csvField(status.issues)).append(',')
                .append(status.totalDroppedSamples).append(',')
                .append(status.totalInternalErrors).append(',')
                .append(status.aggregationCycles).append(',')
                .append(status.lastAggregationDurationMs).append(',')
                .append(status.profilerHttpRequests).append(',')
                .append(csvField(status.note)).append(',')
                .append(csvField(r.log.toString())).append('\n');
        }
        return out.toString();
    }

    private static double overheadPercent(double baselineRps, double scenarioRps) {
        if (baselineRps <= 0) return 0.0;
        return ((baselineRps - scenarioRps) / baselineRps) * 100.0;
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String csvField(String value) {
        String safe = value == null ? "" : value;
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\n")
                || safe.contains("\r")) {
            return "\"" + safe.replace("\"", "\"\"") + "\"";
        }
        return safe;
    }

    private static long longField(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(field)
            + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : 0L;
    }

    private static String stringField(String json, String field, String defaultValue) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(field)
            + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return matcher.find() ? matcher.group(1) : defaultValue;
    }

    private static String arrayField(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(field)
            + "\"\\s*:\\s*\\[(.*?)\\]").matcher(json);
        if (!matcher.find()) return "-";
        String value = matcher.group(1)
            .replace("\"", "")
            .replace(" ", "")
            .trim();
        return value.isBlank() ? "-" : value;
    }

    private static void waitForText(String url, Predicate<String> condition,
                                    Duration timeout, Process process, Path log)
            throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        Throwable lastFailure = null;
        while (System.nanoTime() < deadline) {
            assertProcessAlive(process, log);
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
                HttpResponse<String> response = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300
                        && condition.test(response.body())) {
                    return;
                }
            } catch (Throwable t) {
                lastFailure = t;
            }
            Thread.sleep(250);
        }
        throw new IllegalStateException("Timed out waiting for " + url
            + "\nLast error: " + lastFailure + "\nProcess log:\n" + tail(log));
    }

    private static void assertProcessAlive(Process process, Path log) throws IOException {
        if (!process.isAlive()) {
            throw new IllegalStateException("Target process exited with code "
                + process.exitValue() + "\nProcess log:\n" + tail(log));
        }
    }

    private static String tail(Path file) throws IOException {
        if (!Files.exists(file)) return "(no log file)";
        List<String> lines = Files.readAllLines(file);
        int from = Math.max(0, lines.size() - 80);
        return lines.subList(from, lines.size()).stream()
            .collect(Collectors.joining(System.lineSeparator()));
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

    private static void requireFile(Path file, String message) {
        if (!Files.exists(file)) {
            throw new IllegalStateException(message + " Missing: " + file);
        }
    }

    private static String javaCommand() {
        String exe = isWindows() ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", exe).toString();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    private static Path resolveAgentJar() {
        Path target = ROOT.resolve("target");
        if (Files.isDirectory(target)) {
            try (java.util.stream.Stream<Path> files = Files.list(target)) {
                return files
                    .filter(Files::isRegularFile)
                    .filter(AgentOverheadBenchmark::isRuntimeAgentJar)
                    .max(java.util.Comparator.comparingLong(AgentOverheadBenchmark::lastModifiedMs))
                    .orElse(target.resolve("requestlens-agent.jar"));
            } catch (IOException ignored) {
                // The benchmark startup check reports the resolved fallback path.
            }
        }
        return target.resolve("requestlens-agent.jar");
    }

    private static boolean isRuntimeAgentJar(Path path) {
        String name = path.getFileName().toString();
        return name.startsWith("requestlens-agent-")
            && name.endsWith(".jar")
            && !name.endsWith("-sources.jar")
            && !name.endsWith("-shaded.jar");
    }

    private static long lastModifiedMs(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private record Scenario(String name, String agentArgs) {}

    record BenchmarkResult(
        String scenario,
        boolean agentEnabled,
        int requests,
        int concurrency,
        long elapsedMs,
        double rps,
        double avgMs,
        double p50Ms,
        double p95Ms,
        double maxMs,
        StatusSnapshot status,
        Path log
    ) {}

    record StatusSnapshot(
        String status,
        long issueCount,
        String issues,
        long totalDroppedSamples,
        long totalInternalErrors,
        long aggregationCycles,
        long lastAggregationDurationMs,
        long profilerHttpRequests,
        String note
    ) {
        static StatusSnapshot unavailable(String note) {
            return new StatusSnapshot("unavailable", 0L, "-", 0L, 0L, 0L, 0L, 0L, note);
        }

        static StatusSnapshot fromJson(String json) {
            return new StatusSnapshot(
                stringField(json, "selfMonitoringStatus", "unknown"),
                longField(json, "selfMonitoringIssueCount"),
                arrayField(json, "selfMonitoringIssues"),
                longField(json, "totalDroppedSamples"),
                longField(json, "totalInternalErrors"),
                longField(json, "aggregationCycles"),
                longField(json, "lastAggregationDurationMs"),
                longField(json, "profilerHttpRequests"),
                "");
        }
    }

    private record LoadResult(
        long elapsedMs,
        double rps,
        double avgMs,
        double p50Ms,
        double p95Ms,
        double maxMs
    ) {
        static LoadResult from(long[] latenciesNs, long elapsedNs) {
            long[] sorted = Arrays.copyOf(latenciesNs, latenciesNs.length);
            Arrays.sort(sorted);
            double avgMs = Arrays.stream(latenciesNs).average().orElse(0.0) / 1_000_000.0;
            return new LoadResult(
                elapsedNs / 1_000_000L,
                latenciesNs.length / (elapsedNs / 1_000_000_000.0),
                avgMs,
                percentile(sorted, 50),
                percentile(sorted, 95),
                sorted.length == 0 ? 0.0 : sorted[sorted.length - 1] / 1_000_000.0);
        }

        private static double percentile(long[] sorted, int percentile) {
            if (sorted.length == 0) return 0.0;
            int index = (int) Math.ceil((percentile / 100.0) * sorted.length) - 1;
            return sorted[Math.max(0, index)] / 1_000_000.0;
        }
    }

    private record BenchmarkConfig(
        int requests,
        int warmupRequests,
        int concurrency,
        String endpoint,
        String token,
        long samplingProfilerIntervalMs,
        long lineSampleIntervalMs
    ) {
        static BenchmarkConfig fromSystemProperties() {
            return new BenchmarkConfig(
                intProp("benchmark.requests", 500),
                intProp("benchmark.warmup", 100),
                intProp("benchmark.concurrency", 8),
                System.getProperty("benchmark.endpoint", "/hello"),
                System.getProperty("benchmark.token", "benchmark-token-123456"),
                longProp("benchmark.sampling.profiler.interval.ms", 20L),
                longProp("benchmark.line.interval.ms", 5L));
        }

        private static int intProp(String key, int defaultValue) {
            return Integer.parseInt(System.getProperty(key, String.valueOf(defaultValue)));
        }

        private static long longProp(String key, long defaultValue) {
            return Long.parseLong(System.getProperty(key, String.valueOf(defaultValue)));
        }
    }
}
