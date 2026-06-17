# RequestLens

An experimental Java agent for profiling Spring Boot JVM applications without
changing target application code.

The agent attaches with `-javaagent`, starts an embedded HTTP server, collects
JVM and Spring MVC profiling data, and serves a self-contained dashboard.

## Status

Current version: `v0.1.3`.
Current status: alpha/dev tool.

The project is useful for local development and controlled staging experiments.
It is not production-ready yet because CI must run in the public repository and
final publishing credentials/signing metadata are still missing.

License: Apache-2.0. See `LICENSE`.

## What It Does

- Samples JVM heap usage.
- Samples target JVM process CPU, host system CPU, and profiler-thread CPU.
- Records GC events.
- Tracks Spring MVC endpoint latency and request-thread CPU time.
- Estimates Spring bean memory usage.
- Persists heap, GC, and CPU history to SQLite.
- Adapts heap sampling interval under high RPS.
- Detects simple heap-growth leak patterns.
- Sends webhook alerts for leak/GC conditions.
- Captures request-level method traces.
- Captures JDBC SQL and Spring `RestTemplate` HTTP calls as external spans
  inside request traces.
- Captures opt-in bounded live target logs from Logback, Log4j2, and
  `java.util.logging`, alongside structured GC/JVM events.
- Captures opt-in bounded in-process JFR JVM events for GC, thread sleep/park,
  monitor waits, file/socket I/O, exception statistics, and CPU load.
- Captures per-type allocation details inside traced methods.
- Captures opt-in sampled line hotspots for traced requests.
- Captures opt-in deterministic per-method line hits, timing, allocation counts,
  shallow bytes, self wall/CPU time, and allocation types.
- Captures opt-in shallow allocation bytes/counts per source line.
- Shows source-free `ClassName:lineNumber` method-line detail in trace views,
  with an optional source-code window for captured application line hotspots.
- Falls back to source-free line details when sampled line hotspots cannot load
  `.java` source from the target machine.
- Builds a sampling flamegraph with server-side/dashboard filtering, depth
  control, child limits, and low-signal frame aggregation.
- Reports runtime instrumentation diagnostics so missing traces can be separated
  into package-scope, class-loading, transformation, and line-metadata issues.
- Suggests trace/line package prefixes from the target runtime jar.
- Reports agent self-monitoring status, issue categories, drop/error totals,
  metric ages, aggregation health, and profiler HTTP/persistence health.
- Exposes a machine-readable API catalog at `/profiler/api`.
- Serves a bundled dashboard at `/profiler/dashboard`.

## Requirements

- Java 17+
- Maven 3.9+
- Spring MVC target app for endpoint/request tracing

The agent is designed around Spring Boot MVC. WebFlux request tracing is not
currently supported. Quarkus and Micronaut are feasible future JVM-mode
targets, but they require dedicated framework adapters rather than a config-only
change.

See `COMPATIBILITY.md` for the current verified matrix and unsupported
environments.

## Quick Start

Build the agent:

```powershell
mvn clean package -DskipTests
```

Build the demo app:

```powershell
mvn -q -f demo/pom.xml -DskipTests package
```

Run demo with the agent:

```powershell
java "-javaagent:target/requestlens-agent-0.1.3-SNAPSHOT.jar=port=7099,auth.token=dev-token-123456789,trace.enabled=true,trace.packages=demo,trace.sample.rate=1,profiler.persistence.enabled=false" -jar demo/target/profiler-demo-app.jar --server.port=8080
```

Add `debug.enabled=true` when you want bounded request debug snapshots
attached to traced method spans.

Open:

```text
http://127.0.0.1:7099/profiler/dashboard?token=dev-token-123456789
```

JSON APIs use bearer auth when `auth.token` is configured:

```powershell
curl -H "Authorization: Bearer dev-token-123456789" http://127.0.0.1:7099/profiler/status
```

## Security Defaults

- The profiler HTTP server binds to `127.0.0.1` by default.
- CORS is disabled by default.
- If `profiler.auth.token` is set, all `/profiler/*` routes require a token.
- Dashboard/API auth is controlled by the agent's `profiler.auth.token`, not by
  Spring Security in the target app.
- If auth is disabled and the server is not loopback-only, sensitive bean/class
  details and live logs are redacted.
- Use TLS or a trusted reverse proxy before exposing the profiler outside a
  local machine.

## Useful Endpoints

| Endpoint | Purpose |
| --- | --- |
| `/profiler/api` | API catalog, capability flags, and route metadata |
| `/profiler/status` | Agent health, self-monitoring, and sampling state |
| `/profiler/heap` | Live heap samples |
| `/profiler/gc` | Recent GC events |
| `/profiler/logs` | Bounded live target logs and structured GC/JVM events |
| `/profiler/jfr/events` | Bounded in-process JFR JVM events |
| `/profiler/cpu` | Live process/system/profiler-thread CPU samples |
| `/profiler/endpoints` | Spring MVC endpoint latency and CPU stats |
| `/profiler/beans` | Top Spring beans by estimated memory |
| `/profiler/history/heap` | Persisted heap history |
| `/profiler/history/gc` | Persisted GC history |
| `/profiler/history/cpu` | Persisted CPU history |
| `/profiler/leaks` | Active leak warnings |
| `/profiler/traces` | Recent request trace summaries |
| `/profiler/trace/{id}` | Full method/external call tree, explanation/comparison view data, optional debug snapshots, deterministic method-line stats, and sampled line hotspots for one trace |
| `/profiler/source` | Source-code window for one configured application line hotspot |
| `/profiler/package-discovery` | Suggested app package prefixes from the runtime jar or a supplied jar path |
| `/profiler/flamegraph` | Bounded sampling profiler flamegraph tree used by the dashboard's filtered flame view |
| `/profiler/dashboard` | HTML dashboard |

## Verification

Run all tests:

```powershell
mvn verify
```

Current verification baseline:

```text
122 unit tests passed
4 integration tests passed
```

The integration tests launch the real demo app with the packaged agent attached.

Run the opt-in overhead benchmark:

```powershell
.\scripts\run-overhead-benchmark.ps1
```

The benchmark compares the demo app without the agent against live-agent,
sampled-tracing, full-tracing, line-hotspot, and line-memory modes. Results
include `/profiler/status` self-monitoring summaries and are written under
`target/benchmark-results/`. See `benchmark.md`.

## Self-Monitoring

`/profiler/status` reports the agent's own health: self-monitoring status,
issue categories, total drops, internal errors, metric ages, dropped heap
samples, GC events, CPU samples, endpoint samples, request traces, line hotspot
sessions, JFR events, persistence drops, sampler delays, aggregation cycles/errors/duration,
profiler HTTP request/auth-failure counts, persistence flush/purge counts,
persisted heap/GC/CPU row counts, live CPU status, instrumentation diagnostics,
runtime jar package discovery, and buffer capacities.

## Accuracy Notes

Endpoint latency and request-thread CPU time are useful for separating slow
I/O/waiting endpoints from CPU-heavy endpoints. The agent uses Spring's matched
route pattern when available, so `/items/101` and `/items/202` are grouped as
`/items/{id}` instead of becoming separate endpoints.

Request method tracing gives useful wall-time, CPU-time, and allocated-byte
signals for traced requests. Trace responses include `capturedSpans`,
`droppedSpans`, and `truncated` so capped traces are visible instead of looking
complete.

External dependency spans are added under the active request method for JDBC
`Statement`/`PreparedStatement` SQL calls and Spring `RestTemplate` HTTP calls.
SQL text is shape-sanitized before storage, and HTTP URLs are stored without
query strings. Trace summaries and details expose `externalSpanCount`,
`sqlSpanCount`, and `httpSpanCount`; span nodes include `spanKind`,
`externalOperation`, and `externalResource`.

The dashboard trace detail panel surfaces those caps plus per-method CPU/self
CPU, allocation/self-allocation, line sample/drop counters, per-line shallow
allocation bytes/counts, deterministic method-line rows with self wall/CPU
time, and a separate
line-hotspot view when a request trace row is selected. Deterministic line rows
use `ClassName:lineNumber` and do not require `.java` files on disk. If source
view is enabled and source roots are configured, the dashboard can still show a
small source window around a captured application line hotspot. If source files
are unavailable, the Source tab keeps showing source-free line metrics instead
of becoming empty.

Request Debug Snapshot Mode is disabled by default. When
`profiler.debug.enabled=true` and method tracing is active, instrumented method
spans can include bounded argument, return-value, and exception summaries in
`debugSnapshots`. Summaries are strings, not retained object references, and are
limited by per-trace, per-span, and value-length caps. Trace summaries/details
also expose `debugSnapshotCount` and `droppedDebugSnapshots`; the dashboard
shows compact snapshot rows under the relevant call-tree node. This mode can
include application data from `toString()` output, so use a narrow
`trace.packages` scope and protect the profiler HTTP endpoint with auth.

Trace detail responses also include `traceExplanation` and `traceComparison`.
The explanation is a derived summary of captured bottleneck signals such as
external dependency time, self wall time, CPU ratio, allocations, line hot
spots, and trace caps. The comparison baseline uses recent traces for the same
HTTP method and route, so it is useful for "why was this request different from
nearby requests?" rather than long-term performance history.

For jar-only deployments, `/profiler/package-discovery` can suggest a package
prefix for `trace.packages` and `line.packages`. `/profiler/status` also reports
`instrumentationDiagnostics` so users can see whether configured app classes
were discovered, already loaded, transformed, had line-number metadata, or hit
recent instrumentation errors.

Sampling flamegraph responses are bounded before JSON serialization. The
endpoint accepts `minPct`, `maxDepth`, and `maxChildren` query parameters, and
aggregates pruned frames into synthetic `Other frames` nodes. Profiler
control-plane frames from the embedded HTTP server, shaded Jackson, Javalin,
and Jetty are excluded from collected flamegraph samples.

Live Logs are disabled by default. When `profiler.logs.enabled=true`, the agent
captures current target JVM log events from Logback, Log4j2, and
`java.util.logging` into a bounded ring buffer. `/profiler/logs` also includes
structured GC events from the existing JVM GC listener. It does not read old log
files, rotated files, native JVM log files, or raw native stdout/stderr output.

Line-level request profiling is available as opt-in sampled or deterministic
mode for traced requests. Sampled mode requires explicit target app package
prefixes, samples the active request thread from a profiler-owned background
thread, and reports aggregated line hotspots in `/profiler/trace/{id}`. The
sampled line timings are estimates derived from sample counts and the configured
sample interval, not exact per-line timings. Deterministic mode injects line
probes into traced application methods and is more reliable for fast requests,
but has higher overhead and should stay narrowly scoped.

When `profiler.line.alloc.enabled=true`, allocation-site instrumentation also
records shallow allocation bytes and allocation counts per source line for traced
request methods. These are allocation sizes, not retained heap after GC.

Line self time subtracts traced child method spans and external SQL/HTTP spans
from the parent line that was active at the call site. Work inside untraced
dependency code remains charged to the application line that called it.

Memory values should be interpreted carefully:

- Endpoint heap delta is directional, not exact per-request memory ownership.
- Method allocated bytes are request-thread allocation deltas.
- Per-type allocation detail covers instrumented packages.
- Bean memory is an estimate, not exact retained size.
- For exact retained memory, use a heap dump and a heap analyzer.

Persisted history endpoints are bounded to 10,000 rows per response. History
responses include `limited` and `limit`, so API clients can detect when a time
range was truncated and retry with a smaller window.

## Known Limitations

- Auth is token-based only; there is no user management, RBAC, or built-in TLS.
- Spring MVC only for endpoint tracing.
- External spans currently cover JDBC `Statement`/`PreparedStatement` calls and
  Spring `RestTemplate`; other HTTP clients, R2DBC, ORM internals, and async
  clients are not instrumented yet.
- Live log capture covers Logback, Log4j2, and `java.util.logging` events while
  the agent is attached; historical log files and native JVM stdout/stderr logs
  are not captured.
- Quarkus and Micronaut endpoint/request/bean profiling are not implemented
  yet; see `feature_scope.md`.
- WebFlux tracing is not implemented.
- GraalVM native image is not supported.
- Multi-instance registry is documented in older docs but not implemented.
- Package discovery is heuristic and should be reviewed before copying the
  suggested package into broad staging or production runs.
- Not safe to expose publicly without a token, TLS, and network protection.

## Contributing And Release Readiness

External contribution and release process docs are included:

- `CONTRIBUTING.md` - local setup, branch workflow, and test expectations
- `SECURITY.md` - private vulnerability reporting and security boundaries
- `SUPPORT.md` - support scope and issue triage guidance
- `CODE_OF_CONDUCT.md` - collaboration expectations
- `COMPATIBILITY.md` - verified and unsupported runtime matrix
- `RELEASE_CHECKLIST.md` - release blockers and publishing checklist
- `build_release.md` - CI, release profile, and artifact preparation guide
- `THIRD_PARTY_NOTICES.md` - dependency review scope for binary releases

## Documentation

- `usage.md` - configuration and API usage
- `smoke_test.md` - manual smoke test
- `integration_test.md` - automated integration-test details
- `benchmark.md` - overhead benchmark guide
- `build_release.md` - build and release guide
- `feature_scope.md` - framework scope and Quarkus/Micronaut adapter plan
- `branch.md` - recommended branch plan
- `changelog.md` - change history
