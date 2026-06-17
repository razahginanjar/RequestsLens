# Security Policy

RequestLens exposes runtime profiling data and can include sensitive
class, package, endpoint, and allocation information. Treat the profiler HTTP
port as a privileged diagnostic interface.

## Supported Versions

| Version | Security Support |
| --- | --- |
| `v0.1.3` alpha | Best-effort fixes only |
| `v0.1.2` alpha | Superseded by `v0.1.3` |
| Stable published releases | Not available yet |

The project has not published a stable release. Do not expose the profiler
port publicly without a bearer token, TLS, and network controls.

## Reporting a Vulnerability

Use private vulnerability reporting in the hosting platform when available. If
private reporting is not configured, contact the repository maintainer through
the private channel listed in the repository profile or organization settings.

Do not open a public issue for vulnerabilities that could expose application
data, bypass profiler auth, leak credentials, or crash attached JVMs.

Please include:

- Affected commit or release.
- Java version and operating system.
- Target framework and Spring Boot version, if relevant.
- Agent arguments and relevant `jvm-profiler.properties` entries, with secrets
  redacted.
- Reproduction steps.
- Expected impact.

## Security Boundaries

- Profiler auth is token-based only.
- There is no user management, RBAC, session management, or built-in TLS.
- The dashboard and JSON APIs use the agent's own HTTP server, not the target
  application's Spring Security chain.
- Sensitive bean, trace, flamegraph, allocation, and live-log details are
  redacted when auth is disabled and the profiler is not loopback-only.
- Live target logs can contain application data, SQL text emitted by the app,
  stack traces, or secrets. `/profiler/logs` is treated as sensitive and should
  be protected with profiler auth.
- JFR JVM events can contain file paths, host names, class names, thread names,
  and timing data. `/profiler/jfr/events` is treated as sensitive and should be
  protected with profiler auth.
- Request Debug Snapshot Mode can capture method argument, return-value, and
  exception summaries from application code. Treat `debug.enabled=true` as a
  sensitive diagnostic mode and protect the profiler endpoint with auth and a
  narrow `trace.packages` allow-list.
- SQLite history is local to the target JVM host. Protect the configured
  database path with normal filesystem permissions.
