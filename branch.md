# Branch Plan

This project is now in the hardening stage. The current codebase is suitable as
an alpha/dev profiler, but it still needs security, benchmark, and documentation
work before it should be presented as ready for broad external use.

## Current Baseline

- Java agent entry point: `agent.core.AgentMain`
- Build tool: Maven
- Java target: 17
- Demo target app: `demo/`
- Dashboard: `src/main/resources/dashboard/index.html`
- Full verification command: `mvn verify`

## Completed Hardening Work

The P0 correctness work has been started and the first slice is complete:

- Replaced the weak ring buffer implementation with a bounded locked FIFO buffer.
- Added concurrent producer coverage for the buffer.
- Added Maven Failsafe integration test wiring.
- Added a real `-javaagent` Spring Boot integration test.
- Expanded the demo app to exercise request tracing, allocation capture, and flamegraph sampling.
- Verified `mvn test` and `mvn verify` pass.

## Recommended Branches

Use short-lived branches and merge after `mvn verify` passes.

| Branch | Purpose |
| --- | --- |
| `hardening/p0-agent-it` | Current P0 integration-test and buffer hardening work |
| `hardening/auth` | Add token auth and local bind defaults |
| `hardening/benchmark` | Add overhead benchmark and publish results |
| `feature/line-allocation` | Add line-level allocation profiling |
| `docs/open-source-readiness` | README, usage, compatibility, license, screenshots |
| `feature/multi-instance-registry` | Only if multi-instance support remains in scope |

## Merge Rule

Before merging a branch:

```powershell
mvn verify
```

The branch should not be merged if:

- Unit tests fail.
- Integration tests fail.
- The demo app cannot launch with `-javaagent`.
- The dashboard or public API behavior changes without documentation updates.

## Release Labels

Recommended labels:

- `v0.1.0-alpha` - Current alpha after P0 correctness work.
- `v0.2.0-alpha` - After auth and benchmark are added.
- `v0.3.0-alpha` - After line-level allocation profiling is added.
- `v1.0.0` - Only after docs, compatibility matrix, auth, benchmark, and integration tests are stable.
