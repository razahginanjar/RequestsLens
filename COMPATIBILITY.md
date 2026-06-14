# Compatibility

This document records what is verified today and what still needs testing
before a broad public release.

Current date: 2026-06-14.

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

## Not Supported

- Java 8, 11, or any runtime below Java 17.
- Spring WebFlux request tracing.
- GraalVM native image.
- Non-Spring HTTP frameworks for endpoint/request instrumentation.
- Multi-instance registry in one dashboard process.
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
