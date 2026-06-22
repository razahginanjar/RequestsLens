# Usage Guide

This guide explains how to run and configure the RequestLens agent.

Current milestone: `v0.1.7`.

## Build

```powershell
mvn clean package -DskipTests
```

Agent jar:

```text
target/requestlens-agent-0.1.7-SNAPSHOT.jar
```

## Attach to an App

Basic:

```powershell
java "-javaagent:target/requestlens-agent-0.1.7-SNAPSHOT.jar" -jar your-app.jar
```

With common options:

```powershell
java "-javaagent:target/requestlens-agent-0.1.7-SNAPSHOT.jar=port=7099,auth.token=change-me-123456,interval=10,trace.enabled=true,trace.packages=com.example,trace.sample.rate=50" -jar your-app.jar
```

## Demo App

Build:

```powershell
mvn -q -f demo/pom.xml -DskipTests package
```

Run:

```powershell
java "-javaagent:target/requestlens-agent-0.1.7-SNAPSHOT.jar=port=7099,auth.token=dev-token-123456789,trace.enabled=true,trace.packages=demo,trace.sample.rate=1,profiler.persistence.enabled=false" -jar demo/target/profiler-demo-app.jar --server.port=8080
```

Generate traffic:

```powershell
curl http://localhost:8080/hello
curl http://localhost:8080/slow
curl http://localhost:8080/cpu
```

Dashboard:

```text
http://127.0.0.1:7099/profiler/dashboard?token=dev-token-123456789
```

The dashboard token is controlled by the RequestLens agent config. It is unrelated
to whether the target Spring Boot app uses Spring Security.

## Configuration Sources

Configuration can come from:

1. `jvm-profiler.properties` in the working directory
2. RequestLens YAML config, preferably passed explicitly with `config=...`
3. Agent args after the jar path, for example `=port=7099,interval=20`
4. JVM system properties, for example `-Dprofiler.http.port=7099`

Precedence is deterministic:

```text
defaults < jvm-profiler.properties < YAML config < inline -javaagent args < -Dprofiler.* system properties
```

## YAML Config

For long configurations, put agent settings beside the target app launch and
pass the file path explicitly:

```powershell
java "-javaagent:target/requestlens-agent-0.1.7-SNAPSHOT.jar=config=.\requestlens-agent.yaml" -jar your-app.jar
```

If no explicit path is provided, the agent checks the Java launch working
directory in this order:

```text
requestlens-agent.yaml
requestlens-agent.yml
requestlens.yaml
requestlens.yml
```

Example:

```yaml
http:
  port: 7099
  authToken: change-me-123456
trace:
  enabled: true
  packages:
    - com.example.user
  sampleRate: 1
line:
  enabled: true
  mode: deterministic
  packages:
    - com.example.user
  intervalMs: 1
  allocation: true
source:
  enabled: true
  roots:
    - src/main/java
logs:
  enabled: true
  maxEvents: 1000
jfr:
  enabled: true
  maxEvents: 1000
  thresholdMs: 0
asyncProfiler:
  enabled: true
  event: cpu
  durationSeconds: 30
persistence:
  enabled: false
```

## Common Agent Args

Short args:

| Arg | Maps To |
| --- | --- |
| `config` | Explicit RequestLens YAML config path |
| `config.file` | Explicit RequestLens YAML config path |
| `port` | `profiler.http.port` |
| `host` | `profiler.http.host` |
| `auth.token` | `profiler.auth.token` |
| `cors.enabled` | `profiler.http.cors.enabled` |
| `cors.origins` | `profiler.http.cors.allowed.origins` |
| `interval` | `profiler.sampling.interval.ms` |
| `cpu.interval` | `profiler.cpu.sampling.interval.ms` |
| `alert.webhook.url` | `profiler.alert.webhook.url` |
| `max.rps` | `profiler.sampling.adaptive.max.rps` |
| `trace.enabled` | `profiler.trace.enabled` |
| `trace.packages` | `profiler.trace.packages` |
| `trace.sample.rate` | `profiler.trace.sample.rate` |
| `line.enabled` | `profiler.line.enabled` |
| `line.mode` | `profiler.line.mode` |
| `line.packages` | `profiler.line.packages` |
| `line.interval` | `profiler.line.sample.interval.ms` |
| `line.max.samples` | `profiler.line.max.samples.per.trace` |
| `line.max.lines` | `profiler.line.max.lines.per.trace` |
| `line.max.payload.bytes` | `profiler.line.max.trace.payload.bytes` |
| `line.alloc.enabled` | `profiler.line.alloc.enabled` |
| `source.enabled` | `profiler.source.enabled` |
| `source.roots` | `profiler.source.roots` |
| `source.context.lines` | `profiler.source.context.lines` |
| `debug.enabled` | `profiler.debug.enabled` |
| `debug.capture.args` | `profiler.debug.capture.args` |
| `debug.capture.return` | `profiler.debug.capture.return` |
| `debug.max.snapshots` | `profiler.debug.max.snapshots.per.trace` |
| `debug.max.snapshots.per.trace` | `profiler.debug.max.snapshots.per.trace` |
| `debug.max.snapshots.per.span` | `profiler.debug.max.snapshots.per.span` |
| `debug.max.value.length` | `profiler.debug.max.value.length` |
| `logs.enabled` | `profiler.logs.enabled` |
| `logs.max.events` | `profiler.logs.max.events` |
| `jfr.enabled` | `profiler.jfr.enabled` |
| `jfr.max.events` | `profiler.jfr.max.events` |
| `jfr.threshold.ms` | `profiler.jfr.threshold.ms` |
| `async.enabled` | `profiler.async.enabled` |
| `async.event` | `profiler.async.event` |
| `async.interval` | `profiler.async.interval` |
| `async.duration.seconds` | `profiler.async.duration.seconds` |
| `async.max.collapsed.lines` | `profiler.async.max.collapsed.lines` |
| `async.lib.path` | `profiler.async.lib.path` |

Example:

```text
port=7099,auth.token=change-me-123456,interval=10,trace.enabled=true,trace.packages=com.example,trace.sample.rate=50
```

## Full Configuration Keys

| Key | Default | Purpose |
| --- | --- | --- |
| `profiler.http.port` | `7070` | Profiler HTTP server port |
| `profiler.http.host` | `127.0.0.1` | Profiler HTTP bind host |
| `profiler.auth.token` | empty | Bearer token required for `/profiler/*` when set |
| `profiler.http.cors.enabled` | `false` | Enable restricted CORS headers |
| `profiler.http.cors.allowed.origins` | empty | Comma-separated allowed origins |
| `profiler.sampling.interval.ms` | `10` | Base heap sampling interval |
| `profiler.cpu.sampling.interval.ms` | `1000` | CPU sampling interval; minimum `250` |
| `profiler.instance.id` | `host:port` | Agent instance id |
| `profiler.persistence.enabled` | `true` | Enable SQLite persistence |
| `profiler.persistence.path` | `./profiler-data/profiler.db` | SQLite file path |
| `profiler.persistence.retention.days` | `7` | History retention |
| `profiler.sampling.adaptive.enabled` | `true` | Enable RPS-based throttling |
| `profiler.sampling.adaptive.max.rps` | `500` | RPS threshold |
| `profiler.sampling.adaptive.multiplier` | `5` | Throttled interval multiplier |
| `profiler.alert.gc.overhead.threshold` | `15` | GC overhead alert threshold percent |
| `profiler.alert.webhook.url` | empty | Alert webhook URL |
| `profiler.leak.detection.window.ms` | `60000` | Leak detection window |
| `profiler.sampling.profiler.enabled` | `true` | Enable stack-sampling flamegraph |
| `profiler.sampling.profiler.interval.ms` | `20` | Stack sampling interval |
| `profiler.trace.enabled` | `false` | Enable method tracing |
| `profiler.trace.packages` | empty | Comma-separated app package prefixes |
| `profiler.trace.sample.rate` | `50` | Trace 1 of N requests |
| `profiler.trace.max.depth` | `40` | Max method trace depth |
| `profiler.trace.max.spans` | `5000` | Max spans per request trace |
| `profiler.trace.alloc.detail.enabled` | `true` | Enable per-type allocation detail |
| `profiler.line.enabled` | `false` | Enable request-scoped line profiling; inactive without `profiler.line.packages` |
| `profiler.line.mode` | `sampled` | `sampled` for low-overhead probabilistic hotspots, or `deterministic` for source-line probes inside traced methods |
| `profiler.line.packages` | empty | Comma-separated target app package prefixes for line profiling |
| `profiler.line.sample.interval.ms` | `5` | Request line sampler interval |
| `profiler.line.max.samples.per.trace` | `1000` | Max raw line samples per request trace |
| `profiler.line.max.lines.per.trace` | `300` | Max aggregated line entries per request trace |
| `profiler.line.max.trace.payload.bytes` | `262144` | Max processed line trace payload size |
| `profiler.line.alloc.enabled` | `false` | Enable shallow allocation bytes/counts per source line when line profiling is active |
| `profiler.source.enabled` | `false` | Enable source-code windows for captured application line hotspots |
| `profiler.source.roots` | empty | Comma-separated source directories on the target machine |
| `profiler.source.context.lines` | `6` | Context lines before/after the selected source line; max `50` |
| `profiler.debug.enabled` | `false` | Enable bounded request debug snapshots on traced method spans |
| `profiler.debug.capture.args` | `true` | Capture method argument summaries when debug snapshots are enabled |
| `profiler.debug.capture.return` | `true` | Capture non-void method return-value summaries when debug snapshots are enabled |
| `profiler.debug.max.snapshots.per.trace` | `200` | Max debug snapshots stored for one request trace; max `5000` |
| `profiler.debug.max.snapshots.per.span` | `8` | Max debug snapshots stored on one method span; max `64` |
| `profiler.debug.max.value.length` | `120` | Max characters per debug snapshot value; max `1000` |
| `profiler.logs.enabled` | `false` | Enable bounded live target log capture for Logback, Log4j2, and `java.util.logging` |
| `profiler.logs.max.events` | `1000` | Max app log events kept in memory; max `20000` |
| `profiler.jfr.enabled` | `false` | Enable bounded in-process JFR JVM event capture |
| `profiler.jfr.max.events` | `1000` | Max JFR events kept in memory; max `20000` |
| `profiler.jfr.threshold.ms` | `10` | Minimum duration for noisy JFR duration events; `0` captures all enabled events |
| `profiler.async.enabled` | `false` | Enable embedded async-profiler native backend controls |
| `profiler.async.event` | `cpu` | Default async-profiler event: `cpu`, `wall`, `alloc`, `lock`, or `itimer` |
| `profiler.async.interval` | `10000000` | Async-profiler interval. CPU/wall/lock are nanosecond intervals; allocation uses async-profiler's allocation interval semantics |
| `profiler.async.duration.seconds` | `30` | Max duration for one async-profiler session; max `300` |
| `profiler.async.max.collapsed.lines` | `5000` | Max collapsed stack lines parsed from one native profile; max `100000` |
| `profiler.async.lib.path` | empty | Optional explicit native `libasyncProfiler` path; empty uses the embedded platform resource |

## HTTP Safety

The profiler HTTP server binds to `127.0.0.1` by default. Use `host=0.0.0.0`
only when you also configure `auth.token` and protect the port at the network
layer.

Bearer-token example:

```powershell
curl -H "Authorization: Bearer dev-token-123456789" http://127.0.0.1:7099/profiler/status
```

Dashboard with token:

```text
http://127.0.0.1:7099/profiler/dashboard?token=dev-token-123456789
```

If `auth.token` is not configured, the dashboard and JSON APIs do not require a
token while the server is loopback-only. This is true even if the target app has
Spring Security enabled, because the profiler runs on its own HTTP port.

If auth is disabled and `profiler.http.host` is not loopback-only, sensitive
bean/class details are redacted from bean rankings, full traces, flamegraph
frames, allocation type names, trace package config, source-code views, and
live logs.

CORS is disabled by default. To allow a browser app from one explicit origin:

```text
cors.enabled=true,cors.origins=http://localhost:3000
```

## HTTP API

Map-shaped API responses include `apiVersion`, `generatedAtMs`, and `resource`
metadata while keeping their existing fields at the top level.

### API Catalog

```text
GET /profiler/api
```

Lists profiler routes, capability flags, auth/redaction state, and dashboard/API
links. This is the best endpoint for clients that want to discover available
features before calling optional routes such as history or tracing. Capability
flags include `sourceFreeMethodLines`, `deterministicLineSelfTime`,
`externalSqlSpans`, `externalHttpSpans`, and `requestDebugSnapshots` for
clients that render deterministic method-line views, external dependency spans,
debug snapshot rows, and request explanation/comparison panels. The
`requestExplanationComparison` flag indicates that trace detail responses
include derived explanation and same-route comparison data. Log-related
capabilities include `liveLogsConfigured`, `liveLogsAvailable`,
`liveLogMaxEvents`, `structuredJvmEvents`, `jfrConfigured`, `jfrAvailable`,
`jfrRunning`, `jfrEvents`, `jfrMaxEvents`, `jfrThresholdMs`,
`asyncProfilerConfigured`, `asyncProfilerEmbedded`,
`asyncProfilerAvailable`, `asyncProfilerRunning`, and
`asyncProfilerEvents`.

### Status

```text
GET /profiler/status
```

Shows agent health, sampling state, current RPS, trace status, and persistence
queue/write state. It also includes instrumentation diagnostics and runtime jar
package discovery to help explain empty trace, line-hotspot, or method-line
views.

Important self-monitoring fields:

- `selfMonitoringStatus`, `selfMonitoringIssues`,
  `selfMonitoringIssueCount`, `totalDroppedSamples`, and
  `totalInternalErrors` provide a compact health summary for dashboards,
  automation, and benchmark reports.
- `lastSampleAgeMs`, `lastCpuSampleAgeMs`, `lastAggregationAgeMs`,
  `lastPersistenceFlushAgeMs`, and `lastProfilerHttpRequestAgeMs` show how
  stale recent self-monitoring signals are.
- `droppedSamples`, `droppedGcEvents`, `droppedEndpointSamples`,
  `droppedCpuSamples`, `droppedTraces`, and `droppedPersistence` show
  bounded-buffer or queue loss.
- `aggregationCycles`, `aggregationErrors`, `lastAggregationTimestampMs`, and
  `lastAggregationDurationMs` show whether the background aggregation loop is
  alive and healthy.
- `profilerHttpRequests` and `profilerHttpAuthFailures` show access to the
  profiler control plane.
- `persistenceConfigured`, `persistenceAvailable`,
  `persistenceRetentionDays`, `persistenceHistoryLimit`,
  `persistenceQueueCapacity`, and `persistenceQueueDepth` show whether SQLite
  history is configured and able to accept data.
- `persistenceFlushes`, `persistenceFlushFailures`,
  `lastPersistenceFlushTimestampMs`, `lastPersistenceFlushDurationMs`,
  `persistedHeapSamples`, `persistedGcEvents`, and `persistedCpuSamples` show
  persistence writer health.
- `persistencePurgeRuns`, `persistencePurgeFailures`,
  `lastPersistencePurgeTimestampMs`, and
  `lastPersistencePurgeDeletedRows` show retention cleanup health.
- `processCpuLoadPercent`, `systemCpuLoadPercent`,
  `agentThreadCpuLoadPercent`, and `lastCpuSampleTimestampMs` show live CPU
  monitoring state.
- `lineProfilingConfigured`, `lineProfilingEnabled`, `lineMode`,
  `sampledLineProfilingEnabled`, `deterministicLineProfilingEnabled`,
  `lineSampleIntervalMs`, `lineMaxSamplesPerTrace`, `lineMaxLinesPerTrace`,
  `lineMaxTracePayloadBytes`, `lineActiveRequests`, and `lineCompletedRequests`
  show line-profiling state and guardrails.
- `sourceViewConfigured`, `sourceViewEnabled`, `sourceRootCount`, and
  `sourceContextLines` show source-view state and source-root availability.
- `debugSnapshotConfigured`, `debugSnapshotEnabled`,
  `debugSnapshotCaptureArgs`, `debugSnapshotCaptureReturn`,
  `debugMaxSnapshotsPerTrace`, `debugMaxSnapshotsPerSpan`, and
  `debugMaxValueLength` show request debug snapshot mode and its bounds.
- `logCaptureConfigured`, `logCaptureEnabled`, `logMaxEvents`,
  `recentLogEventCount`, `capturedLogEvents`, and `droppedLogEvents` show live
  target-log capture state and bounded-buffer overwrite counts.
- `jfrConfigured`, `jfrAvailable`, `jfrRunning`, `jfrMaxEvents`,
  `jfrThresholdMs`, `recentJfrEventCount`, `capturedJfrEvents`,
  `droppedJfrEvents`, `jfrErrors`, and `jfrUnsupportedEvents` show
  in-process JFR capture state, event bounds, and stream health.
- `asyncProfilerConfigured`, `asyncProfilerEmbedded`,
  `asyncProfilerInitialized`, `asyncProfilerAvailable`,
  `asyncProfilerRunning`, `asyncProfilerVersion`,
  `asyncProfilerPlatform`, `asyncProfilerEvent`, `asyncProfilerInterval`,
  `asyncProfilerDurationSeconds`, `asyncProfilerStartCount`,
  `asyncProfilerStopCount`, `asyncProfilerErrors`,
  `asyncProfilerSampleCount`, and `asyncProfilerStackCount` show embedded
  native profiler state.
- `instrumentationDiagnostics` shows whether configured trace-package classes
  were discovered, transformed, already loaded, missing line-number metadata, or
  recently failed transformation.
- `packageDiscovery` shows the runtime jar package suggestion, class/package
  counts, top packages, and warnings when the runtime jar can be inspected.
- `bufferCapacities` shows the heap, GC, CPU, logs, JFR, endpoint, and trace
  buffer limits.

### Package Discovery

```text
GET /profiler/package-discovery
GET /profiler/package-discovery?jar=/path/to/target-app.jar
```

Suggests package prefixes for `trace.packages` and `line.packages` by scanning
compiled `.class` entries in the target jar. The no-query form scans the
runtime jar detected from `sun.java.command` or `java.class.path`; the `jar`
query form scans a specific jar path visible to the target JVM process.

Example:

```powershell
curl -H "Authorization: Bearer dev-token-123456789" "http://127.0.0.1:7099/profiler/package-discovery"
curl -H "Authorization: Bearer dev-token-123456789" "http://127.0.0.1:7099/profiler/package-discovery?jar=C:/apps/example-app.jar"
```

Important response fields:

- `suggestedTracePackages` and `suggestedLinePackages` are convenience copies
  of the suggested package prefix.
- `topPackages` lists the strongest package candidates with class counts.
- `warnings` flags broad or unavailable suggestions.

This endpoint exposes jar paths and application package names, so it follows
the profiler sensitive-detail policy. Use an auth token or keep the profiler
bound to loopback.

### Live Heap

```text
GET /profiler/heap
```

Shows current heap and recent live samples.

### GC Events

```text
GET /profiler/gc
```

Shows recent GC events and pause summary.

### Live Logs

```text
GET /profiler/logs
GET /profiler/logs?limit=200&kind=app-log
GET /profiler/logs?limit=200&kind=gc
```

Shows a bounded, current timeline of live target logs and structured JVM GC
events. App log capture is disabled by default and is enabled with:

```text
logs.enabled=true,logs.max.events=1000
```

Supported app log sources are Logback, Log4j2, and `java.util.logging`. Rows
include `kind`, `source`, `level`, `loggerName`, `threadName`, `message`, and
optional `throwable`. GC rows include `gcName`, `gcCause`, `durationMs`,
`heapBeforeBytes`, and `heapAfterBytes`.

The endpoint is sensitive because app logs can contain business data or
secrets. It follows the same redaction rule as trace/source details. It does
not read historical log files, rotated files, native JVM log files, or raw
native stdout/stderr output.

### JFR JVM Events

```text
GET /profiler/jfr/events
GET /profiler/jfr/events?limit=200&category=gc
GET /profiler/jfr/events?limit=200&category=io
```

Shows a bounded, current timeline from an in-process JFR `RecordingStream`.
JFR capture is disabled by default and is enabled with:

```text
jfr.enabled=true,jfr.max.events=1000,jfr.threshold.ms=10
```

Captured categories include `gc`, `thread`, `lock`, `io`, `exception`, `cpu`,
and `jvm`. Rows include `eventType`, `category`, `name`, `durationMs`,
`durationNs`, `threadName`, `message`, and selected event `attributes`.

This endpoint is sensitive because JFR rows can contain file paths, hosts,
class names, and thread names. It follows the same redaction rule as
trace/source details. It is not a full `.jfr` recording export and it does not
replace the sampling flamegraph.

### Live CPU

```text
GET /profiler/cpu
```

Shows current and recent CPU samples for the target JVM process, the host
system, and profiler-owned daemon threads. Unsupported JVM/OS counters are
returned as `-1`.

### Endpoints

```text
GET /profiler/endpoints
```

Shows aggregated Spring MVC endpoint latency, heap delta, and request-thread
CPU time.

The agent prefers Spring's matched route pattern when available. For example,
requests to `/items/101` and `/items/202` are grouped as `/items/{id}`.
`requestCount` is the total observed count for that endpoint; latency, CPU, and
heap delta statistics use a rolling window for recent behavior. CPU fields
include `avgCpuMs`, `maxCpuMs`, and `avgCpuToWallPercent`.

### Beans

```text
GET /profiler/beans
```

Shows estimated Spring bean memory ranking.

### History

```text
GET /profiler/history/heap?from=<epochMs>&to=<epochMs>
GET /profiler/history/gc?from=<epochMs>&to=<epochMs>
GET /profiler/history/cpu?from=<epochMs>&to=<epochMs>
```

Requires persistence to be enabled.

Responses include `sampleCount` or `eventCount`, `limited`, `limit`, and the
returned rows. `limited=true` means more than `limit` rows matched the time
range; query a smaller range to avoid truncation. Query failures return an
explicit API-shaped error instead of looking like an empty result.

### Leaks

```text
GET /profiler/leaks
```

Shows active leak warnings from the latest aggregation cycle.

### Request Traces

```text
GET /profiler/traces
GET /profiler/trace/{id}
GET /profiler/investigate?traceId={id}
GET /profiler/source?className=com.example.Controller&line=42
```

Requires tracing to be enabled and package scope configured.

Trace summaries and details include `capturedSpans`, `droppedSpans`, and
`truncated`. If `truncated=true`, the trace hit `profiler.trace.max.depth` or
`profiler.trace.max.spans`; untracked subtrees are not charged to the parent
method's per-type allocation detail.

Trace summaries and details also include `externalSpanCount`, `sqlSpanCount`,
and `httpSpanCount`. Full trace span nodes include `spanKind` (`request`,
`method`, `sql`, or `http`), `externalOperation`, and `externalResource`.
External resources are sanitized before storage: SQL literals and numbers are
replaced with `?`, and HTTP query strings are removed.

When `profiler.debug.enabled=true`, trace summaries and details include
`debugSnapshotCount` and `droppedDebugSnapshots`. Full trace method spans can
also include `debugSnapshots` rows with `kind`, `name`, `type`, `value`, and
`truncated`. This mode captures bounded string summaries for method arguments,
non-void return values, and thrown exceptions. It does not pause the JVM, keep
object references, capture local variables, or record every loop iteration.
Value summaries may include application data from `toString()` output, so keep
`trace.packages` narrow and use profiler auth.

Trace detail responses include `traceExplanation` with a compact summary,
dominant signal, issue list, and top captured contributors. They also include
`traceComparison`, which compares the selected request with recent traces for
the same HTTP method and route. The comparison is an in-memory recent-trace
baseline, not persisted history.

In the dashboard, selecting a trace row opens the call tree with request totals,
span quality metadata, per-method wall/self-wall time, CPU/self-CPU time,
allocation/self-allocation, and per-type allocation detail where available. The
trace panel also shows line sample/drop counters and separate call-tree and
line-hotspot views for the selected trace. When line allocation detail is
enabled, the line-hotspot view also shows shallow allocation bytes and allocation
counts per source line. The Method lines tab shows deterministic
`ClassName:lineNumber` rows without requiring source files. When source view is
enabled, the same tab can load a small source window for a selected sampled line
hotspot; when source files are unavailable, it falls back to source-free line
details. SQL and HTTP external spans appear in the call tree with badges and the
sanitized SQL shape or URL. Debug snapshots appear as compact rows under the
method span that captured them. The Explain tab shows the derived explanation,
top contributors, and same-route comparison deltas. The Investigation tab and
`/profiler/investigate` endpoint correlate the selected request with nearby JFR
events, live target logs, SQL/HTTP spans, line/method hotspots, and the latest
async-profiler snapshot. Trace data is exact for the request; JFR/log evidence
is time-window correlation, and async-profiler evidence is process-wide unless
its session overlaps the request window.

If `/profiler/traces` is empty or a trace only shows controller-level spans,
check `/profiler/status.instrumentationDiagnostics`:

- `discoveredTraceClasses = 0` usually means `trace.packages` does not match
  the compiled target app package. Use `/profiler/package-discovery`.
- `discoveredTraceClasses > 0` and `transformedTraceClasses = 0` usually means
  matching classes were already loaded before the agent could transform them,
  or the matcher excluded them.
- `transformedTraceClasses > 0` and `capturedSpans = 0` usually means the
  request was not sampled, did not execute transformed methods, or executed
  outside the selected package.
- `classesWithoutLineNumbers > 0` means the compiled classes have missing or
  incomplete line-number tables, so deterministic method-line rows may be
  limited even when method tracing works.

If line profiling is active, trace summaries also include `lineSampleCount`,
`lineHotspotCount`, `deterministicLineCount`,
`deterministicLineSelfWallNs`, `deterministicLineSelfCpuNs`,
`lineAllocationCount`, `lineAllocatedBytes`, `droppedLineSamples`,
`droppedLineHotspots`, and `lineHotspotsTruncated`.
Trace details include `lineHotspots`, `lineSampleCount`, `droppedLineSamples`,
`droppedLineHotspots`, `lineHotspotsTruncated`, and `lineSampleIntervalMs`.
Each line hotspot includes `allocationCount` and `allocatedBytes`. External
spans are currently emitted for JDBC `Statement`/`PreparedStatement` calls and
Spring `RestTemplate`; other clients require additional adapters.

Request Debug Snapshot Mode is intentionally separate from line profiling. It
can show method boundary values, but it cannot tell the exact value held by an
arbitrary local variable on an arbitrary source line unless that value crosses a
captured method boundary.

### Request Debug Snapshots

Request debug snapshots are disabled by default and require active method
tracing:

```text
trace.enabled=true
trace.packages=com.example
trace.sample.rate=1
debug.enabled=true
debug.max.snapshots=200
debug.max.snapshots.per.span=8
debug.max.value.length=120
```

Use `debug.capture.args=false` or `debug.capture.return=false` to reduce value
capture. Thrown exception summaries are captured while snapshot mode is active.

### Line Hotspot Profiling

Line-level request profiling is disabled by default. When enabled, it samples
active traced request threads from a profiler-owned daemon and attaches
aggregated source-line hotspots to the trace after the response path has
completed.

Line profiling stays inactive unless tracing and line profiling are both scoped
to application packages:

```text
profiler.trace.enabled=true
profiler.trace.packages=com.example
profiler.line.enabled=true
profiler.line.packages=com.example
profiler.line.alloc.enabled=true
```

Short-argument form:

```text
trace.enabled=true,trace.packages=com.example,line.enabled=true,line.packages=com.example,line.interval=5,line.alloc.enabled=true
```

Deterministic method-line mode:

```text
trace.enabled=true,trace.packages=com.example,line.enabled=true,line.mode=deterministic,line.packages=com.example,line.alloc.enabled=true
```

Deterministic mode injects source-line probes into traced application methods.
It is more reliable for fast requests than sampled mode and `/profiler/trace/{id}`
can show per-method `ClassName:lineNumber` rows without requiring `.java` files
on disk. It is heavier than sampled mode, especially in tight loops and high-RPS
endpoints, so keep `trace.packages` and `line.packages` narrow.

The agent normalizes package prefixes, ignores known dependency/agent/JDK
classes, and exposes the active limits through `/profiler/status` and
`/profiler/api`.

Line hotspot timing is sample-based. `estimatedWallNs` is `samples *
profiler.line.sample.interval.ms`, and `estimatedCpuNs` is based on samples
where the request thread was RUNNABLE. Treat these values as hotspot direction,
not exact per-line accounting.

Deterministic method-line timing is event-based. Each line probe records hits,
inclusive elapsed time, and self elapsed time between line transitions inside
the active method span. Line self time subtracts traced child method spans and
external SQL/HTTP spans from the parent line that was active at the call site.
Treat line timing as a practical debugging signal: Java line metadata can be
coarse or missing, and untraced dependency work still remains charged to the
application line that called it.

Line allocation detail is exact shallow allocation-site accounting for traced
methods when `profiler.line.alloc.enabled=true`. It is not retained memory and
does not include allocations made inside untraced dependency code.

### Source Code View

Source view is disabled by default because it reads `.java` files from the
target machine. It only serves classes inside `profiler.line.packages`, ignores
known dependency/JDK/agent packages, and only searches under
`profiler.source.roots`.

Example:

```text
trace.enabled=true,trace.packages=com.example,line.enabled=true,line.packages=com.example,source.enabled=true,source.roots=/srv/app/src/main/java
```

For the bundled demo from the repository root:

```text
source.enabled=true,source.roots=demo/src/main/java
```

If the deployed target only has compiled jars and no source files on disk,
`/profiler/source` returns `sourceAvailable: false`.

Source view is optional. Deterministic method-line rows and line allocation
metrics use bytecode line-number metadata from the jar and can work without
`.java` files on disk, as long as the classes were compiled with line numbers.
The dashboard also shows source-free line details for sampled line hotspots when
source lookup is disabled or cannot find a matching `.java` file.

### Flamegraph

```text
GET /profiler/flamegraph
GET /profiler/flamegraph?minPct=1&maxDepth=6&maxChildren=40
```

Shows folded stack-sampling data. The response is bounded before JSON
serialization using `minPct`, `maxDepth`, and `maxChildren`; frames hidden by
those limits are aggregated into synthetic `Other frames` nodes with
`hiddenFrameCount`, `hiddenReason`, and percentage metadata. The dashboard
renders this as a filtered flame view with minimum-percent, depth, child-count,
and frame-search controls so tiny or deep low-signal stacks do not dominate
the view.

Profiler control-plane stacks from RequestLens HTTP handling, shaded Jackson,
Javalin, and Jetty are not recorded as target flamegraph samples.

### Embedded async-profiler

```text
GET  /profiler/async/status
POST /profiler/async/start?event=cpu&durationSeconds=30
POST /profiler/async/stop
GET  /profiler/async/collapsed?limit=100
GET  /profiler/async/flamegraph?minPct=1&maxDepth=6&maxChildren=40
```

The async-profiler backend is disabled by default:

```text
async.enabled=true,async.event=cpu,async.duration.seconds=30
```

When enabled on a supported Linux/macOS JVM, RequestLens loads the embedded
async-profiler native backend and exposes bounded start/stop controls through
the profiler API and dashboard. The latest stopped profile is kept in memory as
collapsed stacks and as a bounded flamegraph tree. Supported event values are
`cpu`, `wall`, `alloc`, `lock`, and `itimer`.

This is separate from `/profiler/flamegraph`: the existing flamegraph is a pure
Java thread sampler that works everywhere; async-profiler uses a native backend
for more accurate CPU/native/JVM stack sampling where supported. Windows and
custom runtimes without a compatible native backend report
`asyncProfilerAvailable: false`.

The async-profiler endpoints are sensitive because native stacks can expose
class names, native symbols, file paths, host/runtime details, and workload
shape. Protect them with profiler auth.

## Important Notes

- Do not expose the profiler port publicly without a token, TLS, and network protection.
- Use `trace.packages` and `line.packages`; do not trace everything.
- Keep `source.roots` narrow and point it only at application source
  directories.
- Keep `trace.sample.rate` higher than `1` outside local experiments.
- Line hotspot timing is sampled and estimated; use it to find hot source
  lines, not as exact per-line billing.
- Review `/profiler/package-discovery` output before copying a suggested
  package into a broad trace or line-profiling configuration.
- Check `/profiler/status.instrumentationDiagnostics` before assuming a fast
  request is the reason trace, line-hotspot, or method-line data is empty.
- Use async-profiler sessions as bounded investigations. Do not leave native
  profiling running continuously in shared environments.
- Line allocation bytes are shallow allocation sizes at traced allocation sites,
  not retained heap after GC.
- Endpoint heap delta is directional, not exact retained memory.
- Method allocation data is most useful for finding allocation-heavy code paths.
- Quarkus and Micronaut can use generic JVM metrics only today; endpoint,
  request-trace, and bean profiling need future framework adapters.

## Overhead Benchmark

Run the local benchmark harness with:

```powershell
.\scripts\run-overhead-benchmark.ps1
```

Example with a larger run:

```powershell
.\scripts\run-overhead-benchmark.ps1 -Requests 1000 -Warmup 200 -Concurrency 16 -Endpoint /hello -LineIntervalMs 5
```

Reports are written to `target/benchmark-results/`. The benchmark includes
live, sampled-trace, full-trace, line-hotspot, and line-memory scenarios plus a
post-scenario `/profiler/status` self-monitoring summary. It is opt-in because
throughput and latency numbers depend on local machine load.
