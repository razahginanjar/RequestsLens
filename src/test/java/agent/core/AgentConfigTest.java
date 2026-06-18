package agent.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AgentConfigTest {

    @Test
    void loadsDefaultsWhenNoArgsProvided() {
        AgentConfig config = AgentConfig.load(null);
        assertEquals(7070, config.getHttpPort());
        assertEquals("127.0.0.1", config.getHttpHost());
        assertEquals(10L,  config.getBaseIntervalMs());
        assertEquals(1000L, config.getCpuSamplingIntervalMs());
        assertNotNull(config.getInstanceId());
        assertFalse(config.isAuthEnabled());
        assertFalse(config.isCorsEnabled());
        assertTrue(config.isLocalOnlyHttpBind());
        assertFalse(config.isLineProfilingConfigured());
        assertFalse(config.isLineProfilingActive());
        assertEquals("sampled", config.getLineMode());
        assertFalse(config.isSampledLineProfilingActive());
        assertFalse(config.isDeterministicLineProfilingActive());
        assertEquals("", config.getLinePackages());
        assertEquals(5L, config.getLineSampleIntervalMs());
        assertEquals(1000, config.getLineMaxSamplesPerTrace());
        assertEquals(300, config.getLineMaxLinesPerTrace());
        assertEquals(262_144, config.getLineMaxTracePayloadBytes());
        assertFalse(config.isLineAllocationProfilingActive());
        assertFalse(config.isSourceViewConfigured());
        assertFalse(config.isSourceViewActive());
        assertEquals("", config.getSourceRoots());
        assertEquals(6, config.getSourceContextLines());
        assertFalse(config.isRequestDebugSnapshotConfigured());
        assertFalse(config.isRequestDebugSnapshotActive());
        assertTrue(config.isDebugSnapshotCaptureArgs());
        assertTrue(config.isDebugSnapshotCaptureReturn());
        assertEquals(200, config.getDebugMaxSnapshotsPerTrace());
        assertEquals(8, config.getDebugMaxSnapshotsPerSpan());
        assertEquals(120, config.getDebugMaxValueLength());
        assertFalse(config.isLogCaptureEnabled());
        assertEquals(1000, config.getLogMaxEvents());
        assertFalse(config.isJfrEnabled());
        assertEquals(1000, config.getJfrMaxEvents());
        assertEquals(10L, config.getJfrThresholdMs());
        assertFalse(config.isAsyncProfilerEnabled());
        assertEquals("cpu", config.getAsyncProfilerEvent());
        assertEquals(10_000_000L, config.getAsyncProfilerInterval());
        assertEquals(30, config.getAsyncProfilerDurationSeconds());
        assertEquals(5000, config.getAsyncProfilerMaxCollapsedLines());
        assertEquals("", config.getAsyncProfilerLibPath());
        assertFalse(config.isConfigFileLoaded());
        assertEquals("", config.getConfigFilePath());
        assertFalse(config.isConfigFileAutoDiscovered());
    }

    @Test
    void parsesPortFromArgString() {
        AgentConfig config = AgentConfig.load("port=9090");
        assertEquals(9090, config.getHttpPort());
    }

    @Test
    void parsesMultipleArgsFromArgString() {
        AgentConfig config = AgentConfig.load("port=8888,interval=20,cpu.interval=750");
        assertEquals(8888, config.getHttpPort());
        assertEquals(20L,  config.getBaseIntervalMs());
        assertEquals(750L, config.getCpuSamplingIntervalMs());
    }

    @Test
    void parsesHttpSafetyArgsFromArgString() {
        AgentConfig config = AgentConfig.load("host=0.0.0.0,auth.token=1234567890abcdef,"
            + "cors.enabled=true,cors.origins=http://localhost:3000");

        assertEquals("0.0.0.0", config.getHttpHost());
        assertFalse(config.isLocalOnlyHttpBind());
        assertTrue(config.isAuthEnabled());
        assertEquals("1234567890abcdef", config.getAuthToken());
        assertTrue(config.isCorsEnabled());
        assertEquals("http://localhost:3000", config.getCorsAllowedOrigins());
    }

    @Test
    void clampsIntervalBelowMinimum() {
        AgentConfig config = AgentConfig.load("interval=1");
        // 1ms is below the 5ms minimum — should be clamped to 5
        assertEquals(5L, config.getBaseIntervalMs());
    }

    @Test
    void clampsCpuSamplingIntervalBelowMinimum() {
        AgentConfig config = AgentConfig.load("cpu.interval=10");
        assertEquals(250L, config.getCpuSamplingIntervalMs());
    }

    @Test
    void handlesInvalidPortGracefully() {
        AgentConfig config = AgentConfig.load("port=notanumber");
        // Invalid port falls back to default
        assertEquals(7070, config.getHttpPort());
    }

    @Test
    void handlesNullArgsGracefully() {
        assertDoesNotThrow(() -> AgentConfig.load(null));
    }

    @Test
    void handlesEmptyArgsGracefully() {
        assertDoesNotThrow(() -> AgentConfig.load(""));
    }

    @Test
    void parsesLineProfilingSafetyArgsFromArgString() {
        AgentConfig config = AgentConfig.load("line.enabled=true,line.mode=deterministic,"
            + "line.packages=demo,com.example.*,"
            + "line.interval=7,line.max.samples=250,line.max.lines=80,"
            + "line.max.payload.bytes=12345,line.alloc.enabled=true,"
            + "source.enabled=true,source.roots=src/main/java,demo/src/main/java,"
            + "source.context.lines=9,"
            + "trace.enabled=true,trace.packages=demo,"
            + "debug.enabled=true,debug.capture.args=false,"
            + "debug.capture.return=false,debug.max.snapshots=25,"
            + "debug.max.snapshots.per.span=3,debug.max.value.length=64,"
            + "logs.enabled=true,logs.max.events=2500,"
            + "jfr.enabled=true,jfr.max.events=3500,jfr.threshold.ms=2,"
            + "async.enabled=true,async.event=wall,async.interval=2000000,"
            + "async.duration.seconds=15,async.max.collapsed.lines=7000,"
            + "async.lib.path=/opt/async/libasyncProfiler.so");

        assertTrue(config.isLineProfilingConfigured());
        assertTrue(config.isLineProfilingActive());
        assertEquals("deterministic", config.getLineMode());
        assertFalse(config.isSampledLineProfilingActive());
        assertTrue(config.isDeterministicLineProfilingActive());
        assertEquals("demo,com.example", config.getLinePackages());
        assertEquals(7L, config.getLineSampleIntervalMs());
        assertEquals(250, config.getLineMaxSamplesPerTrace());
        assertEquals(80, config.getLineMaxLinesPerTrace());
        assertEquals(12_345, config.getLineMaxTracePayloadBytes());
        assertTrue(config.isLineAllocationProfilingActive());
        assertTrue(config.isLineProfilingTargetClass("demo.DemoApplication"));
        assertTrue(config.isLineProfilingTargetClass("com.example.orders.OrderService"));
        assertFalse(config.isLineProfilingTargetClass("demolition.NotTarget"));
        assertTrue(config.isSourceViewConfigured());
        assertTrue(config.isSourceViewActive());
        assertEquals("src/main/java,demo/src/main/java", config.getSourceRoots());
        assertEquals(9, config.getSourceContextLines());
        assertTrue(config.isSourceViewTargetClass("demo.DemoApplication"));
        assertFalse(config.isSourceViewTargetClass("org.springframework.web.servlet.DispatcherServlet"));
        assertTrue(config.isRequestDebugSnapshotConfigured());
        assertTrue(config.isRequestDebugSnapshotActive());
        assertFalse(config.isDebugSnapshotCaptureArgs());
        assertFalse(config.isDebugSnapshotCaptureReturn());
        assertEquals(25, config.getDebugMaxSnapshotsPerTrace());
        assertEquals(3, config.getDebugMaxSnapshotsPerSpan());
        assertEquals(64, config.getDebugMaxValueLength());
        assertTrue(config.isLogCaptureEnabled());
        assertEquals(2500, config.getLogMaxEvents());
        assertTrue(config.isJfrEnabled());
        assertEquals(3500, config.getJfrMaxEvents());
        assertEquals(2L, config.getJfrThresholdMs());
        assertTrue(config.isAsyncProfilerEnabled());
        assertEquals("wall", config.getAsyncProfilerEvent());
        assertEquals(2_000_000L, config.getAsyncProfilerInterval());
        assertEquals(15, config.getAsyncProfilerDurationSeconds());
        assertEquals(7000, config.getAsyncProfilerMaxCollapsedLines());
        assertEquals("/opt/async/libasyncProfiler.so", config.getAsyncProfilerLibPath());
    }

    @Test
    void commaContinuationOnlyAppliesToListArgs() {
        AgentConfig config = AgentConfig.load("port=9090,ignored,line.enabled=true,"
            + "line.packages=demo,com.example");

        assertEquals(9090, config.getHttpPort());
        assertEquals("demo,com.example", config.getLinePackages());
    }

    @Test
    void lineProfilingStaysInactiveWithoutPackageScope() {
        AgentConfig config = AgentConfig.load("line.enabled=true");

        assertTrue(config.isLineProfilingConfigured());
        assertFalse(config.isLineProfilingActive());
        assertFalse(config.isLineProfilingTargetClass("demo.DemoApplication"));
    }

    @Test
    void lineProfilingRejectsDependencyAndAgentClasses() {
        AgentConfig config = AgentConfig.load("line.enabled=true,"
            + "line.packages=demo,org.springframework,agent");

        assertTrue(config.isLineProfilingTargetClass("demo.DemoApplication"));
        assertFalse(config.isLineProfilingTargetClass("org.springframework.web.servlet.DispatcherServlet"));
        assertFalse(config.isLineProfilingTargetClass("agent.core.AgentMain"));
        assertFalse(config.isLineProfilingTargetClass("java.util.ArrayList"));
    }

    @Test
    void lineProfilingCapsAreValidated() {
        AgentConfig config = AgentConfig.load("line.enabled=true,line.packages=demo,"
            + "line.interval=0,line.max.samples=999999,line.max.lines=999999,"
            + "line.max.payload.bytes=999999999,source.context.lines=999,"
            + "debug.max.snapshots=999999,debug.max.snapshots.per.span=999,"
            + "debug.max.value.length=999999,logs.max.events=999999,"
            + "jfr.max.events=999999,jfr.threshold.ms=999999,"
            + "async.interval=9999999999,async.duration.seconds=9999,"
            + "async.max.collapsed.lines=999999");

        assertEquals(5L, config.getLineSampleIntervalMs());
        assertEquals(100_000, config.getLineMaxSamplesPerTrace());
        assertEquals(10_000, config.getLineMaxLinesPerTrace());
        assertEquals(4 * 1024 * 1024, config.getLineMaxTracePayloadBytes());
        assertEquals(50, config.getSourceContextLines());
        assertEquals(5000, config.getDebugMaxSnapshotsPerTrace());
        assertEquals(64, config.getDebugMaxSnapshotsPerSpan());
        assertEquals(1000, config.getDebugMaxValueLength());
        assertEquals(20_000, config.getLogMaxEvents());
        assertEquals(20_000, config.getJfrMaxEvents());
        assertEquals(60_000L, config.getJfrThresholdMs());
        assertEquals(1_000_000_000L, config.getAsyncProfilerInterval());
        assertEquals(300, config.getAsyncProfilerDurationSeconds());
        assertEquals(100_000, config.getAsyncProfilerMaxCollapsedLines());
    }

    @Test
    void invalidJfrThresholdFallsBackToDefault() {
        AgentConfig config = AgentConfig.load("jfr.threshold.ms=-1");

        assertEquals(10L, config.getJfrThresholdMs());
    }

    @Test
    void invalidAsyncProfilerValuesFallBackToDefaults() {
        AgentConfig config = AgentConfig.load("async.event=bad,async.interval=0");

        assertEquals("cpu", config.getAsyncProfilerEvent());
        assertEquals(10_000_000L, config.getAsyncProfilerInterval());
    }

    @Test
    void invalidLineModeFallsBackToSampled() {
        AgentConfig config = AgentConfig.load("line.enabled=true,line.mode=bad,line.packages=demo");

        assertEquals("sampled", config.getLineMode());
        assertTrue(config.isSampledLineProfilingActive());
        assertFalse(config.isDeterministicLineProfilingActive());
    }

    @Test
    void loadsYamlConfigFromExplicitAgentPath(@TempDir Path dir) throws Exception {
        Path yaml = dir.resolve("requestlens-agent.yaml");
        Files.writeString(yaml, """
            http:
              port: 7099
              authToken: 1234567890abcdef
              cors:
                enabled: true
                origins:
                  - http://localhost:3000
            sampling:
              intervalMs: 15
              adaptive:
                enabled: false
              profiler:
                enabled: true
                intervalMs: 25
            persistence:
              enabled: true
              path: target/yaml-profiler.db
              retentionDays: 3
            trace:
              enabled: true
              packages:
                - demo
                - com.example.user
              sampleRate: 1
              allocationDetail: false
            line:
              enabled: true
              mode: deterministic
              packages:
                - demo
                - com.example.user
              intervalMs: 2
              allocation: true
            source:
              enabled: true
              roots:
                - src/main/java
                - demo/src/main/java
              contextLines: 8
            debug:
              enabled: true
              captureArgs: false
              captureReturn: true
              maxSnapshotsPerTrace: 30
              maxSnapshotsPerSpan: 4
              maxValueLength: 80
            logs:
              enabled: true
              maxEvents: 1500
            jfr:
              enabled: true
              maxEvents: 1600
              thresholdMs: 3
            asyncProfiler:
              enabled: true
              event: wall
              interval: 2000000
              durationSeconds: 20
              maxCollapsedLines: 6000
            """, StandardCharsets.UTF_8);

        AgentConfig config = AgentConfig.load("config=" + yaml);

        assertTrue(config.isConfigFileLoaded());
        assertEquals(yaml.toAbsolutePath().normalize(), Path.of(config.getConfigFilePath()));
        assertFalse(config.isConfigFileAutoDiscovered());
        assertEquals(7099, config.getHttpPort());
        assertEquals("1234567890abcdef", config.getAuthToken());
        assertTrue(config.isCorsEnabled());
        assertEquals("http://localhost:3000", config.getCorsAllowedOrigins());
        assertEquals(15L, config.getBaseIntervalMs());
        assertTrue(config.isPersistenceEnabled());
        assertEquals("target/yaml-profiler.db", config.getPersistencePath());
        assertEquals(3, config.getPersistenceRetentionDays());
        assertTrue(config.isSamplingProfilerEnabled());
        assertEquals(25L, config.getSamplingProfilerIntervalMs());
        assertTrue(config.isTraceEnabled());
        assertEquals("demo,com.example.user", config.getTracePackages());
        assertEquals(1, config.getTraceSampleRate());
        assertFalse(config.isAllocDetailEnabled());
        assertTrue(config.isLineProfilingConfigured());
        assertTrue(config.isLineProfilingActive());
        assertEquals("deterministic", config.getLineMode());
        assertEquals("demo,com.example.user", config.getLinePackages());
        assertEquals(2L, config.getLineSampleIntervalMs());
        assertTrue(config.isLineAllocationProfilingActive());
        assertTrue(config.isSourceViewActive());
        assertEquals("src/main/java,demo/src/main/java", config.getSourceRoots());
        assertEquals(8, config.getSourceContextLines());
        assertTrue(config.isRequestDebugSnapshotActive());
        assertFalse(config.isDebugSnapshotCaptureArgs());
        assertTrue(config.isDebugSnapshotCaptureReturn());
        assertEquals(30, config.getDebugMaxSnapshotsPerTrace());
        assertEquals(4, config.getDebugMaxSnapshotsPerSpan());
        assertEquals(80, config.getDebugMaxValueLength());
        assertTrue(config.isLogCaptureEnabled());
        assertEquals(1500, config.getLogMaxEvents());
        assertTrue(config.isJfrEnabled());
        assertEquals(1600, config.getJfrMaxEvents());
        assertEquals(3L, config.getJfrThresholdMs());
        assertTrue(config.isAsyncProfilerEnabled());
        assertEquals("wall", config.getAsyncProfilerEvent());
        assertEquals(2_000_000L, config.getAsyncProfilerInterval());
        assertEquals(20, config.getAsyncProfilerDurationSeconds());
        assertEquals(6000, config.getAsyncProfilerMaxCollapsedLines());
    }

    @Test
    void inlineAgentArgsOverrideYamlConfig(@TempDir Path dir) throws Exception {
        Path yaml = dir.resolve("requestlens-agent.yaml");
        Files.writeString(yaml, """
            http:
              port: 7099
            trace:
              enabled: true
              packages: demo
              sampleRate: 1
            """, StandardCharsets.UTF_8);

        AgentConfig config = AgentConfig.load("config=" + yaml
            + ",port=7100,trace.sample.rate=5");

        assertTrue(config.isConfigFileLoaded());
        assertEquals(7100, config.getHttpPort());
        assertEquals(5, config.getTraceSampleRate());
    }

    @Test
    void systemPropertiesOverrideYamlAndInlineArgs(@TempDir Path dir) throws Exception {
        Path yaml = dir.resolve("requestlens-agent.yaml");
        Files.writeString(yaml, """
            http:
              port: 7099
            """, StandardCharsets.UTF_8);

        System.setProperty("profiler.http.port", "7200");
        try {
            AgentConfig config = AgentConfig.load("config=" + yaml + ",port=7100");

            assertTrue(config.isConfigFileLoaded());
            assertEquals(7200, config.getHttpPort());
        } finally {
            System.clearProperty("profiler.http.port");
        }
    }

    @Test
    void discoversYamlConfigNamesInPreferredOrder(@TempDir Path dir) throws Exception {
        Path fallback = dir.resolve("requestlens.yaml");
        Path preferred = dir.resolve("requestlens-agent.yml");
        Files.writeString(fallback, "http:\n  port: 7099\n", StandardCharsets.UTF_8);
        Files.writeString(preferred, "http:\n  port: 7100\n", StandardCharsets.UTF_8);

        assertEquals(preferred.toAbsolutePath().normalize(),
            AgentConfig.discoverYamlConfig(dir));
    }
}
