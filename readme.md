# JVM Profiler Agent

An experimental Java agent for profiling Spring Boot JVM applications without
changing target application code.

The agent attaches with `-javaagent`, starts an embedded HTTP server, collects
JVM and Spring MVC profiling data, and serves a self-contained dashboard.

## Status

Current status: alpha/dev tool.

The project is useful for local development and controlled staging experiments.
It is not production-ready yet because compatibility matrix and release
packaging work are still missing.

## What It Does

- Samples JVM heap usage.
- Records GC events.
- Tracks Spring MVC endpoint latency.
- Estimates Spring bean memory usage.
- Persists heap and GC history to SQLite.
- Adapts heap sampling interval under high RPS.
- Detects simple heap-growth leak patterns.
- Sends webhook alerts for leak/GC conditions.
- Captures request-level method traces.
- Captures per-type allocation details inside traced methods.
- Builds a sampling flamegraph.
- Reports agent self-monitoring counters for drops, aggregation health, and
  profiler HTTP access.
- Exposes a machine-readable API catalog at `/profiler/api`.
- Serves a bundled dashboard at `/profiler/dashboard`.

## Requirements

- Java 17+
- Maven 3.9+
- Spring MVC target app for endpoint/request tracing

The agent is designed around Spring Boot MVC. WebFlux request tracing is not
currently supported.

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
| `/profiler/endpoints` | Spring MVC endpoint latency stats |
| `/profiler/beans` | Top Spring beans by estimated memory |
| `/profiler/history/heap` | Persisted heap history |
| `/profiler/history/gc` | Persisted GC history |
| `/profiler/leaks` | Active leak warnings |
| `/profiler/traces` | Recent request trace summaries |
| `/profiler/trace/{id}` | Full method call tree for one trace |
| `/profiler/flamegraph` | Sampling profiler flamegraph tree |
| `/profiler/dashboard` | HTML dashboard |

## Verification

Run all tests:

```powershell
mvn verify
```

Current baseline:

```text
63 unit tests passed
3 integration tests passed
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
events, endpoint samples, request traces, persistence drops, sampler delays,
aggregation cycles/errors/duration, profiler HTTP request/auth-failure counts,
and buffer capacities.

## Accuracy Notes

Endpoint latency is useful for finding slow Spring MVC endpoints. The agent uses
Spring's matched route pattern when available, so `/items/101` and `/items/202`
are grouped as `/items/{id}` instead of becoming separate endpoints.

Request method tracing gives useful wall-time, CPU-time, and allocated-byte
signals for traced requests. Trace responses include `capturedSpans`,
`droppedSpans`, and `truncated` so capped traces are visible instead of looking
complete.

Memory values should be interpreted carefully:

- Endpoint heap delta is directional, not exact per-request memory ownership.
- Method allocated bytes are request-thread allocation deltas.
- Per-type allocation detail covers instrumented packages.
- Bean memory is an estimate, not exact retained size.
- For exact retained memory, use a heap dump and a heap analyzer.

## Known Limitations

- Auth is token-based only; there is no user management, RBAC, or built-in TLS.
- Spring MVC only for endpoint tracing.
- WebFlux tracing is not implemented.
- GraalVM native image is not supported.
- Multi-instance registry is documented in older docs but not implemented.
- Not safe to expose publicly without a token, TLS, and network protection.

## Documentation

- `usage.md` - configuration and API usage
- `smoke_test.md` - manual smoke test
- `integration_test.md` - automated integration-test details
- `benchmark.md` - overhead benchmark guide
- `branch.md` - recommended branch plan
- `changelog.md` - change history
