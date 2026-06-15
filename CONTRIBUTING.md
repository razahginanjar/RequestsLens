# Contributing

Thanks for helping improve RequestLens. This project is still alpha
software, so contributions should prioritize correctness, safety, and clear
runtime behavior over broad feature expansion.

## Prerequisites

- Java 17+
- Maven 3.9+
- PowerShell for the benchmark wrapper on Windows
- Bash and curl only if you use `stress-test.sh`

## Local Setup

Build and test the agent:

```powershell
mvn test
mvn verify
```

`mvn verify` builds the shaded agent jar, builds the Spring Boot demo app, and
runs the real `-javaagent` integration tests.

Run the demo manually:

```powershell
mvn clean package -DskipTests
mvn -q -f demo/pom.xml -DskipTests package
java "-javaagent:target/requestlens-agent-1.0.0-SNAPSHOT.jar=port=7099,auth.token=dev-token-123456789,trace.enabled=true,trace.packages=demo,trace.sample.rate=1,profiler.persistence.enabled=false" -jar demo/target/profiler-demo-app.jar --server.port=8080
```

Open:

```text
http://127.0.0.1:7099/profiler/dashboard?token=dev-token-123456789
```

## Branches

Use short-lived topic branches:

```text
fix/<problem>
feature/<capability>
docs/<topic>
hardening/<topic>
```

Before opening a pull request:

- Run `mvn test`.
- Run `mvn verify` when behavior, packaging, instrumentation, HTTP APIs, or
  persistence changes.
- Update `changelog.md` for user-visible changes.
- Update `usage.md`, `smoke_test.md`, or `integration_test.md` when commands,
  config, or endpoint contracts change.
- Do not commit generated files under `target/`, local SQLite databases, logs,
  or packaged jars.

## Code Expectations

- Keep hot-path agent code allocation-light and non-blocking.
- Never let profiler failures crash the target application.
- Prefer explicit self-metrics for dropped data, truncation, and degraded
  subsystems.
- Use reflection at Spring/servlet boundaries unless the class is guaranteed to
  be visible from the agent classloader.
- Add focused tests for behavior changes. Use integration tests for
  classloader, shading, Byte Buddy, dashboard/API, and real `-javaagent` paths.

## Security Issues

Do not report security issues in public issues. Follow `SECURITY.md`.

## License

By contributing, you agree that your contribution is licensed under the Apache
License, Version 2.0, the same license as this project.
