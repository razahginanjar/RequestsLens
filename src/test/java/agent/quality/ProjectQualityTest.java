package agent.quality;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class ProjectQualityTest {

    private static final Path ROOT = Paths.get("").toAbsolutePath().normalize();

    @Test
    void productionSourcesUseStructuredFailureReporting() throws IOException {
        Path mainSources = ROOT.resolve("src/main/java");
        List<String> violations;
        try (Stream<Path> paths = Files.walk(mainSources)) {
            violations = paths
                .filter(path -> path.toString().endsWith(".java"))
                .flatMap(ProjectQualityTest::scanJavaSource)
                .toList();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }

        assertTrue(violations.isEmpty(), () ->
            "Production Java should use logging/self-metrics instead of direct console "
                + "or stack-trace output:\n" + String.join("\n", violations));
    }

    @Test
    void dashboardScriptHasValidJavaScriptSyntax() throws Exception {
        Path dashboard = ROOT.resolve("src/main/resources/dashboard/index.html");
        String html = Files.readString(dashboard, StandardCharsets.UTF_8);
        assertTrue(html.contains("<script src=\"/profiler/dashboard.js\"></script>"),
            "Dashboard HTML should load the bundled dashboard.js asset");

        Path scriptFile = ROOT.resolve("src/main/resources/dashboard/dashboard.js");
        assertFalse(Files.readString(scriptFile, StandardCharsets.UTF_8).isBlank(),
            "Dashboard JavaScript asset should not be empty");

        Process process;
        try {
            process = new ProcessBuilder("node", "--check", scriptFile.toString())
                .redirectErrorStream(true)
                .start();
        } catch (IOException e) {
            fail("Node.js must be available on PATH for the dashboard syntax gate", e);
            return;
        }

        String output = new String(process.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        assertEquals(0, exitCode, () -> "`node --check` failed:\n" + output);
    }

    private static Stream<String> scanJavaSource(Path path) {
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            List<String> violations = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.contains(".printStackTrace(")) {
                    violations.add(location(path, i + 1) + " uses printStackTrace()");
                }
                if (line.contains("System.out.") || line.contains("System.err.")) {
                    violations.add(location(path, i + 1) + " writes directly to console");
                }
            }
            return violations.stream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String location(Path path, int line) {
        return ROOT.relativize(path.toAbsolutePath().normalize()) + ":" + line;
    }
}
