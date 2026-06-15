# JVM Profiler Agent

An experimental Java agent for profiling Spring Boot JVM applications without
changing target application code.

The agent attaches with `-javaagent`, starts an embedded HTTP server, collects
JVM and Spring MVC profiling data, and serves a self-contained dashboard.

## Status

Current version: `v0.1.0`.
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
- Captures per-type allocation details inside traced methods.
- Captures opt-in sampled line hotspots for traced requests.
- Captures opt-in shallow allocation bytes/counts per source line.
- Builds a sampling flamegraph.
- Reports agent self-monitoring counters for drops, aggregation health, and
  profiler HTTP/persistence health.
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
java "-javaagent:target/jvm-profiler-agent-1.0.0-SNAPSHOT.jar=port=7099,auth.token=dev-token-123456789,trace.enabled=true,trace.packages=demo,trace.sample.rate=1,profiler.persistence.enabled=false" -jar demo/target/profiler-demo-app.jar --server.port=8080
```

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
  details are redacted.
- Use TLS or a trusted reverse proxy before exposing the profiler outside a
  local machine.

## Useful Endpoints

| Endpoint | Purpose |
| --- | --- |
| `/profiler/api` | API catalog, capability flags, and route metadata |
| `/profiler/status` | Agent health, self-monitoring, and sampling state |
| `/profiler/heap` | Live heap samples |
| `/profiler/gc` | Recent GC events |
| `/profiler/cpu` | Live process/system/profiler-thread CPU samples |
| `/profiler/endpoints` | Spring MVC endpoint latency and CPU stats |
| `/profiler/beans` | Top Spring beans by estimated memory |
| `/profiler/history/heap` | Persisted heap history |
| `/profiler/history/gc` | Persisted GC history |
| `/profiler/history/cpu` | Persisted CPU history |
| `/profiler/leaks` | Active leak warnings |
| `/profiler/traces` | Recent request trace summaries |
| `/profiler/trace/{id}` | Full method call tree and sampled line hotspots for one trace |
| `/profiler/flamegraph` | Sampling profiler flamegraph tree |
| `/profiler/dashboard` | HTML dashboard |

## Verification

Run all tests:

```powershell
mvn verify
```

Current `v0.1.0` baseline:

```text
82 unit tests passed
4 integration tests passed
```

The integration tests launch the real demo app with the packaged agent attached.

Run the opt-in overhead benchmark:

```powershell
.\scripts\run-overhead-benchmark.ps1
```

The benchmark compares the demo app without the agent against live-agent,
sampled-tracing, and full-tracing modes. Results are written under
`target/benchmark-results/`. See `benchmark.md`.

## Self-Monitoring

`/profiler/status` reports the agent's own health: dropped heap samples, GC
events, CPU samples, endpoint samples, request traces, line hotspot sessions,
persistence drops, sampler delays, aggregation cycles/errors/duration, profiler
HTTP request/auth-failure counts, persistence flush/purge counts, persisted
heap/GC/CPU row counts, live CPU status, and buffer capacities.

## Accuracy Notes

Endpoint latency and request-thread CPU time are useful for separating slow
I/O/waiting endpoints from CPU-heavy endpoints. The agent uses Spring's matched
route pattern when available, so `/items/101` and `/items/202` are grouped as
`/items/{id}` instead of becoming separate endpoints.

Request method tracing gives useful wall-time, CPU-time, and allocated-byte
signals for traced requests. Trace responses include `capturedSpans`,
`droppedSpans`, and `truncated` so capped traces are visible instead of looking
complete.

The dashboard trace detail panel surfaces those caps plus per-method CPU/self
CPU, allocation/self-allocation, line sample/drop counters, per-line shallow
allocation bytes/counts, and a separate line-hotspot view when a request trace
row is selected.

Line-level request profiling is available as an opt-in sampled mode for traced
requests. It requires explicit target app package prefixes, samples the active
request thread from a profiler-owned background thread, and reports aggregated
line hotspots in `/profiler/trace/{id}`. The line timings are estimates derived
from sample counts and the configured sample interval, not exact per-line
timings.

When `profiler.line.alloc.enabled=true`, allocation-site instrumentation also
records shallow allocation bytes and allocation counts per source line for traced
request methods. These are allocation sizes, not retained heap after GC.

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
- Quarkus and Micronaut endpoint/request/bean profiling are not implemented
  yet; see `feature_scope.md`.
- WebFlux tracing is not implemented.
- GraalVM native image is not supported.
- Multi-instance registry is documented in older docs but not implemented.
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
