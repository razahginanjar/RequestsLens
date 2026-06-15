# Usage Guide

This guide explains how to run and configure the profiler agent.

## Build

```powershell
mvn clean package -DskipTests
```

Agent jar:

```text
target/jvm-profiler-agent-1.0.0-SNAPSHOT.jar
```

## Attach to an App

Basic:

```powershell
java "-javaagent:target/jvm-profiler-agent-1.0.0-SNAPSHOT.jar" -jar your-app.jar
```

With common options:

```powershell
java "-javaagent:target/jvm-profiler-agent-1.0.0-SNAPSHOT.jar=port=7099,auth.token=change-me-123456,interval=10,trace.enabled=true,trace.packages=com.example,trace.sample.rate=50" -jar your-app.jar
```

## Demo App

Build:

```powershell
mvn -q -f demo/pom.xml -DskipTests package
```

Run:

```powershell
java "-javaagent:target/jvm-profiler-agent-1.0.0-SNAPSHOT.jar=port=7099,auth.token=dev-token-123456789,trace.enabled=true,trace.packages=demo,trace.sample.rate=1,profiler.persistence.enabled=false" -jar demo/target/profiler-demo-app.jar --server.port=8080
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

The dashboard token is controlled by the profiler agent config. It is unrelated
to whether the target Spring Boot app uses Spring Security.

## Configuration Sources

Configuration can come from:

1. `jvm-profiler.properties` in the working directory
2. JVM system properties, for example `-Dprofiler.http.port=7099`
3. Agent args after the jar path, for example `=port=7099,interval=20`

## Common Agent Args

Short args:

| Arg | Maps To |
| --- | --- |
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
| `line.packages` | `profiler.line.packages` |
| `line.interval` | `profiler.line.sample.interval.ms` |
| `line.max.samples` | `profiler.line.max.samples.per.trace` |
| `line.max.lines` | `profiler.line.max.lines.per.trace` |
| `line.max.payload.bytes` | `profiler.line.max.trace.payload.bytes` |
| `line.alloc.enabled` | `profiler.line.alloc.enabled` |

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
| `profiler.line.enabled` | `false` | Enable request-scoped sampled line hotspot profiling; inactive without `profiler.line.packages` |
| `profiler.line.packages` | empty | Comma-separated target app package prefixes for line profiling |
| `profiler.line.sample.interval.ms` | `5` | Request line sampler interval |
| `profiler.line.max.samples.per.trace` | `1000` | Max raw line samples per request trace |
| `profiler.line.max.lines.per.trace` | `300` | Max aggregated line entries per request trace |
| `profiler.line.max.trace.payload.bytes` | `262144` | Max processed line trace payload size |
| `profiler.line.alloc.enabled` | `false` | Enable shallow allocation bytes/counts per source line when line profiling is active |

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
frames, allocation type names, and trace package config.

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
features before calling optional routes such as history or tracing.

### Status

```text
GET /profiler/status
```

Shows agent health, sampling state, current RPS, trace status, and persistence
queue/write state.

Important self-monitoring fields:

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
- `lineProfilingConfigured`, `lineProfilingEnabled`, `lineSampleIntervalMs`,
  `lineMaxSamplesPerTrace`, `lineMaxLinesPerTrace`,
  `lineMaxTracePayloadBytes`, `lineActiveRequests`, and
  `lineCompletedRequests` show line-profiling state and guardrails.
- `bufferCapacities` shows the heap, GC, CPU, endpoint, and trace buffer
  limits.

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
```

Requires tracing to be enabled and package scope configured.

Trace summaries and details include `capturedSpans`, `droppedSpans`, and
`truncated`. If `truncated=true`, the trace hit `profiler.trace.max.depth` or
`profiler.trace.max.spans`; untracked subtrees are not charged to the parent
method's per-type allocation detail.

In the dashboard, selecting a trace row opens the call tree with request totals,
span quality metadata, per-method wall/self-wall time, CPU/self-CPU time,
allocation/self-allocation, and per-type allocation detail where available. The
trace panel also shows line sample/drop counters and separate call-tree and
line-hotspot views for the selected trace. When line allocation detail is
enabled, the line-hotspot view also shows shallow allocation bytes and allocation
counts per source line.

If line profiling is active, trace summaries also include `lineSampleCount`,
`lineHotspotCount`, `lineAllocationCount`, `lineAllocatedBytes`,
`droppedLineSamples`, `droppedLineHotspots`, and `lineHotspotsTruncated`.
Trace details include `lineHotspots`, `lineSampleCount`, `droppedLineSamples`,
`droppedLineHotspots`, `lineHotspotsTruncated`, and `lineSampleIntervalMs`.
Each line hotspot includes `allocationCount` and `allocatedBytes`.

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

The agent normalizes package prefixes, ignores known dependency/agent/JDK
classes, and exposes the active limits through `/profiler/status` and
`/profiler/api`.

Line hotspot timing is sample-based. `estimatedWallNs` is `samples *
profiler.line.sample.interval.ms`, and `estimatedCpuNs` is based on samples
where the request thread was RUNNABLE. Treat these values as hotspot direction,
not exact per-line accounting.

Line allocation detail is exact shallow allocation-site accounting for traced
methods when `profiler.line.alloc.enabled=true`. It is not retained memory and
does not include allocations made inside untraced dependency code.

### Flamegraph

```text
GET /profiler/flamegraph
```

Shows folded stack-sampling data.

## Important Notes

- Do not expose the profiler port publicly without a token, TLS, and network protection.
- Use `trace.packages` and `line.packages`; do not trace everything.
- Keep `trace.sample.rate` higher than `1` outside local experiments.
- Line hotspot timing is sampled and estimated; use it to find hot source
  lines, not as exact per-line billing.
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
.\scripts\run-overhead-benchmark.ps1 -Requests 1000 -Warmup 200 -Concurrency 16 -Endpoint /hello
```

Reports are written to `target/benchmark-results/`. The benchmark is opt-in
because throughput and latency numbers depend on local machine load.
