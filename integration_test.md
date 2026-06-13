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
- `/hello`, `/slow`, and `/cpu` are reachable.
- `/profiler/status` reports tracing and sampling profiler state.
- `/profiler/endpoints` contains observed Spring MVC endpoints.
- `/profiler/beans` contains discovered Spring beans.
- `/profiler/traces` contains request traces.
- `/profiler/trace/{id}` contains a method call tree.
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
- Allowed CORS preflight requests receive the configured origin.
- The dashboard can load with `/profiler/dashboard?token=<token>`.

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

As of the P1 security hardening pass:

```text
mvn verify
BUILD SUCCESS
52 unit tests passed
3 integration tests passed
```
