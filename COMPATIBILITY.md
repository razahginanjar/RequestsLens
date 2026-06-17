# Compatibility

This document records what is verified today and what still needs testing
before a broad public release.

Current date: 2026-06-17.
Current milestone: `v0.1.4`.

## Verified In This Repository

| Area | Verified |
| --- | --- |
| Java | Java 17 build and test target |
| Build | Maven build with Surefire, Shade, and Failsafe |
| Build metadata | Maven enforcer requires Java 17+ and Maven 3.9+ |
| Target app | Spring Boot 3.3.4 demo app |
| Web stack | Spring MVC / DispatcherServlet |
| Agent startup | `-javaagent` against a Spring Boot fat jar |
| HTTP API | Embedded Javalin 7.2.2 / Jetty 12.1.8 |
| Persistence | SQLite via xerial sqlite-jdbc 3.45.3.0 |
| JFR events | In-process Java Flight Recorder `RecordingStream` on Java 17 |
| async-profiler API | Embedded async-profiler Java API and packaged Linux/macOS native artifacts; unavailable on Windows |
| Platform | Verified by current local CI/dev run on Windows |

## CI Matrix Configured

The repository includes a GitHub Actions CI workflow for:

- Ubuntu latest with Java 17 and 21.
- Windows latest with Java 17 and 21.

These entries should move into the verified table after the workflow runs in
the public repository.

## Expected But Not Fully Certified

| Area | Notes |
| --- | --- |
| Java 21 | Expected to work, but not yet part of the automated matrix |
| Linux x64 | Expected to work; needs CI coverage |
| macOS x64/arm64 | Expected to work; needs CI coverage |
| Spring Boot 3.2+ / 3.3+ | Expected for Spring MVC; needs matrix coverage |
| Alternative servlet containers | Expected through Spring MVC hooks; needs testing |
| async-profiler on Linux/macOS | Expected through embedded platform artifacts; needs CI/manual validation on each OS/arch |
| Quarkus JVM mode | Feasible future adapter; not implemented |
| Micronaut JVM mode | Feasible future adapter; not implemented |

## Not Supported

- Java 8, 11, or any runtime below Java 17.
- Spring WebFlux request tracing.
- Quarkus endpoint/request/bean profiling.
- Micronaut endpoint/request/bean profiling.
- GraalVM native image.
- Non-Spring HTTP frameworks for endpoint/request instrumentation.
- Multi-instance registry in one dashboard process.
- JFR event capture on custom Java runtimes that omit the `jdk.jfr` module.
- async-profiler native profiling on Windows or unsupported OS/CPU combinations.
- Public exposure without token auth, TLS, and network controls.

## Release Matrix Needed Before Stable Release

Before calling the project production-ready, add CI or documented manual
coverage for:

- Java 17 and 21.
- Windows, Linux, and macOS.
- Spring Boot 3.2, 3.3, and the current supported Spring Boot line.
- Tomcat, Jetty, and Undertow backed Spring MVC apps where practical.
- Persistence enabled and disabled.
- Auth enabled and disabled.
- Trace disabled, sampled tracing, and full local tracing.
