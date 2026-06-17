# Integration Test Guide

Integration tests verify the real runtime deployment shape:

```text
Spring Boot fat jar + -javaagent:target/requestlens-agent-*.jar
```

This is critical because unit tests cannot catch Spring Boot classloader issues,
Byte Buddy advice binding problems, shading mistakes, or embedded HTTP startup
failures.

## Command

Run all unit and integration tests:

```powershell
mvn verify
```

Run only unit tests:

```powershell
mvn test
```

## What `mvn verify` Does

1. Compiles the RequestLens agent.
2. Runs unit tests through Surefire.
3. Packages the shaded Java agent jar.
4. Builds the demo Spring Boot fat jar.
5. Runs Failsafe integration tests.
6. Launches the demo app with `-javaagent`.
7. Calls real HTTP endpoints and validates profiler output.

## Integration Test File

Main integration test:

```text
src/test/java/agent/integration/AgentSpringBootIT.java
```

## Current Integration Coverage

The integration test validates:

- The target Spring Boot app starts with the agent attached.
- `/hello`, `/slow`, `/cpu`, `/items/{id}`, and `/external` are reachable.
- `/profiler/status` reports tracing and sampling profiler state.
- `/profiler/status` reports live CPU sampling state and CPU buffer capacity.
- `/profiler/status` reports self-monitoring fields such as buffer capacities,
  aggregation cycles, and profiler HTTP request counters.
- `/profiler/status` reports derived self-monitoring health fields, including
  status, issue categories, total drops, internal errors, and metric ages.
- `/profiler/status` reports persistence availability, queue capacity, flush
  counts, persisted row counts, and retention purge health.
- `/profiler/api` reports route metadata, API version metadata, and capability
  flags, including CPU monitoring, instrumentation diagnostics, and package
  discovery capability.
- `/profiler/status` and `/profiler/api` report line-profiling configuration,
  line mode, active sampled/deterministic state, and enforced caps.
- `/profiler/status` and `/profiler/api` report request debug snapshot mode,
  capture settings, and enforced caps.
- `/profiler/status` reports live log capture state, buffer capacity, captured
  log count, and dropped log count.
- `/profiler/api` reports the request explanation/comparison capability.
- `/profiler/api` reports live log and structured JVM event capabilities.
- `/profiler/status` reports instrumentation diagnostics for discovered trace
  classes, transformed trace classes, transformed trace methods, line-number
  metadata, and recent transformation errors.
- `/profiler/status` reports runtime jar package discovery with a suggested
  package for trace and line profiling.
- `/profiler/package-discovery` returns suggested package prefixes for the demo
  runtime jar.
- `/profiler/logs` returns the demo app's SLF4J/Logback request log when live
  logs are enabled.
- `/profiler/cpu` reports live process/system/profiler-thread CPU samples.
- `/profiler/endpoints` contains observed Spring MVC endpoints.
- `/profiler/endpoints` reports request-thread CPU fields for observed
  endpoints.
- `/profiler/endpoints` groups path-variable requests under the Spring route
  pattern `/items/{id}` instead of raw paths like `/items/101`.
- `/profiler/beans` contains discovered Spring beans.
- `/profiler/traces` contains request traces.
- `/profiler/trace/{id}` contains a method call tree.
- `/profiler/trace/{id}` reports trace quality metadata and is not truncated in
  the demo happy path.
- `/profiler/traces` and `/profiler/trace/{id}` include deterministic
  method-line counts and deterministic line self wall/CPU fields when
  deterministic line profiling is enabled.
- `/profiler/trace/{id}` includes per-method `lineStats` for
  `ClassName:lineNumber` drilldown without requiring source files.
- Per-method `lineStats` include inclusive wall/CPU time and self wall/CPU time.
- `/profiler/traces` and `/profiler/trace/{id}` include shallow per-line
  allocation bytes/counts when line allocation detail is enabled.
- `/profiler/traces` and `/profiler/trace/{id}` include `externalSpanCount`,
  `sqlSpanCount`, and `httpSpanCount` for the `/external` request.
- `/profiler/trace/{id}` contains `sql` and `http` span kinds with sanitized
  SQL/URL resources for the demo JDBC and `RestTemplate` calls.
- `/profiler/traces` and `/profiler/trace/{id}` include debug snapshot counts
  and drop counts when request debug snapshots are enabled.
- `/profiler/trace/{id}` contains method-span debug snapshots for
  `/items/{id}` argument and return summaries.
- `/profiler/trace/{id}` includes `traceExplanation` and `traceComparison`
  for selected request traces.
- `/profiler/source` returns a source-code window for a configured demo
  application line hotspot.
- The deterministic method-line stats contain `demo.DemoApplication.slow`.
- The deterministic method-line stats include allocation data for
  `demo.DemoApplication.slow`.
- The trace tree contains `demo.DemoApplication.slow`.
- Allocation instrumentation records:
  - `byte[]`,
  - `demo.DemoApplication.Item`,
  - `demo.DemoApplication.Item[]`,
  - `java.lang.Object[]`,
  - `int[][]`.
- `/profiler/flamegraph` produces samples.
- `/profiler/flamegraph` honors bounded response controls for minimum percent,
  maximum depth, and maximum child frames.
- If the profiler HTTP port is already in use, the target application still starts.
- When `auth.token` is configured, `/profiler/status` rejects unauthenticated
  requests with HTTP 401.
- Bearer-token requests can read `/profiler/status`.
- Bearer-token requests can read `/profiler/api`.
- Allowed CORS preflight requests receive the configured origin.
- The dashboard can load with `/profiler/dashboard?token=<token>` and includes
  the API/runtime panel, Agent Health summary fields, trace summary counters,
  trace-detail tabs, line hotspot UI assets, method-line UI assets, source-view
  fallback assets, instrumentation diagnostics panel, package suggestion
  fields, line self-time assets, source-free line-detail fallback assets,
  SQL/HTTP external span badges/counters, debug snapshot rows, the Explain tab
  and comparison assets, Live Logs assets, and bounded vertical flamegraph UI
  assets.
- With persistence enabled, `/profiler/history/heap` returns stored heap samples
  from SQLite and includes `limited`/`limit` metadata.
- With persistence enabled, `/profiler/history/gc` returns API-shaped persisted
  history metadata even when no GC event occurred during the test window.
- With persistence enabled, `/profiler/history/cpu` returns stored CPU samples
  from SQLite and includes `limited`/`limit` metadata.

## Why This Test Matters

The highest-risk part of this project is not ordinary Java logic. It is runtime
instrumentation:

- the agent is loaded by the system classloader,
- Spring Boot classes are loaded by a child classloader,
- advice code is inlined into application/framework methods,
- shaded dependencies must not leak into target app signatures.

This test catches those failures in the real shape users will run.

## Logs

Integration test process logs are written under:

```text
target/it-logs/
```

If an integration test fails, inspect the corresponding log file first.

## Requirements

- Java 17+
- Maven available on PATH, or set `MAVEN_CMD`
- Free local ports for the demo app and profiler HTTP server

## Current Result

As of the v0.1.5 request investigation slice:

```text
mvn verify
BUILD SUCCESS
127 unit tests passed
4 integration tests passed
```

The main packaged-agent integration test enables JFR with
`jfr.enabled=true,jfr.threshold.ms=0` and verifies `/profiler/jfr/events`,
status/capability fields, and dashboard JVM Events assets.

The same test verifies async-profiler route discovery, disabled/default status
fields, and dashboard Native Profiler assets without starting native profiling,
so the packaged-agent test remains portable on Windows and CI runners where the
native backend may be unavailable.
