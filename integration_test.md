# Integration Test Guide

Integration tests verify the real runtime deployment shape:

```text
Spring Boot fat jar + -javaagent:target/jvm-profiler-agent-*.jar
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

1. Compiles the profiler agent.
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
- `/hello`, `/slow`, `/cpu`, and `/items/{id}` are reachable.
- `/profiler/status` reports tracing and sampling profiler state.
- `/profiler/status` reports live CPU sampling state and CPU buffer capacity.
- `/profiler/status` reports self-monitoring fields such as buffer capacities,
  aggregation cycles, and profiler HTTP request counters.
- `/profiler/status` reports persistence availability, queue capacity, flush
  counts, persisted row counts, and retention purge health.
- `/profiler/api` reports route metadata, API version metadata, and capability
  flags, including CPU monitoring capability.
- `/profiler/status` and `/profiler/api` report line-profiling configuration,
  active sampler state, and enforced caps.
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
- `/profiler/traces` and `/profiler/trace/{id}` include request-scoped sampled
  line hotspot counts when line profiling is enabled.
- `/profiler/traces` and `/profiler/trace/{id}` include shallow per-line
  allocation bytes/counts when line allocation detail is enabled.
- The trace line hotspots contain `demo.DemoApplication.slow`.
- The trace line hotspots include allocation data for `demo.DemoApplication.slow`.
- The trace tree contains `demo.DemoApplication.slow`.
- Allocation instrumentation records:
  - `byte[]`,
  - `demo.DemoApplication.Item`,
  - `demo.DemoApplication.Item[]`,
  - `java.lang.Object[]`,
  - `int[][]`.
- `/profiler/flamegraph` produces samples.
- If the profiler HTTP port is already in use, the target application still starts.
- When `auth.token` is configured, `/profiler/status` rejects unauthenticated
  requests with HTTP 401.
- Bearer-token requests can read `/profiler/status`.
- Bearer-token requests can read `/profiler/api`.
- Allowed CORS preflight requests receive the configured origin.
- The dashboard can load with `/profiler/dashboard?token=<token>` and includes
  the API/runtime panel, trace summary counters, trace-detail tabs, and line
  hotspot UI assets.
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

As of the P2 memory-per-line pass:

```text
mvn verify
BUILD SUCCESS
82 unit tests passed
4 integration tests passed
```
