# Support

RequestLens is currently at `v0.1.6` and remains an alpha/dev tool.
Support is community best-effort only.

## Good Places To Start

- `readme.md` for project status and quick start.
- `usage.md` for configuration and API details.
- `smoke_test.md` for manual validation.
- `integration_test.md` for automated verification details.
- `COMPATIBILITY.md` for tested and unsupported environments.
- `benchmark.md` for local overhead measurements.

## Before Filing an Issue

Please include:

- Java version.
- Operating system.
- Spring Boot and Spring Framework versions.
- Agent version or commit hash.
- Full `-javaagent` argument string with tokens and secrets redacted.
- Whether `mvn verify` passes locally.
- Relevant profiler logs and target app logs.

For runtime failures, confirm whether the target app still starts without the
agent attached. For performance reports, include benchmark settings and local
machine context.
