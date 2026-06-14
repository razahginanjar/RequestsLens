# Branch Plan

This project is now in the hardening stage. The current codebase is suitable as
an alpha/dev profiler, but it still needs compatibility and packaging
work before it should be presented as ready for broad external use.

## Current Baseline

- Java agent entry point: `agent.core.AgentMain`
- Build tool: Maven
- Java target: 17
- Demo target app: `demo/`
- Dashboard: `src/main/resources/dashboard/index.html`
- Full verification command: `mvn verify`

## Completed Hardening Work

The P0 correctness work, P1 HTTP safety slice, P1 profiling-quality slice,
benchmark slice, P1 self-monitoring slice, P2 API/dashboard slice, and P2
persistence slice are complete:

- Replaced the weak ring buffer implementation with a bounded locked FIFO buffer.
- Added concurrent producer coverage for the buffer.
- Added Maven Failsafe integration test wiring.
- Added a real `-javaagent` Spring Boot integration test.
- Expanded the demo app to exercise request tracing, allocation capture, and flamegraph sampling.
- Added loopback-only HTTP binding by default.
- Added bearer-token auth for `/profiler/*`.
- Added restricted, disabled-by-default CORS configuration.
- Added redaction for sensitive bean/class details when auth is disabled and
  the HTTP server is not loopback-only.
- Added integration coverage for authenticated profiler API access.
- Added Spring route-pattern grouping for endpoint stats.
- Fixed endpoint request count semantics so totals are not capped by the p95
  rolling window.
- Added trace truncation metadata and prevented allocation attribution inside
  untracked trace subtrees.
- Added integration coverage for endpoint route-pattern grouping and trace
  quality metadata.
- Added an opt-in overhead benchmark harness and wrapper script.
- Added agent self-monitoring for endpoint, GC, trace, persistence,
  aggregation, and profiler HTTP control-plane health.
- Added dashboard visibility and tests for the new self-monitoring fields.
- Added `/profiler/api`, response metadata, and dashboard API/runtime
  capability visibility.
- Added persistence status metrics for queue capacity, flush counts/failures,
  persisted heap/GC rows, purge counts/failures, and history response limits.
- Added bounded history metadata (`limited`, `limit`) and explicit query
  failure responses for persisted heap/GC history.
- Added composite SQLite indexes for instance/time history queries.
- Added integration coverage for a real persistence-enabled Spring Boot demo
  launch and persisted history reads.
- Verified `mvn test` and `mvn verify` pass.

## Recommended Branches

Use short-lived branches and merge after `mvn verify` passes.

| Branch | Purpose |
| --- | --- |
| `hardening/p0-agent-it` | Completed P0 integration-test and buffer hardening work |
| `hardening/auth` | Completed P1 token auth and local bind defaults |
| `hardening/profiling-quality` | Completed P1 accuracy and profiling-quality work |
| `hardening/benchmark` | Completed opt-in overhead benchmark harness |
| `hardening/self-monitoring` | Completed P1 self-monitoring metrics and tests |
| `hardening/api-dashboard` | Completed P2 API catalog and dashboard capability polish |
| `hardening/persistence` | Completed P2 persistence observability and history hardening |
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
- `v0.2.0-alpha` - After auth, profiling-quality, and benchmark harness are added.
- `v0.3.0-alpha` - After line-level allocation profiling is added.
- `v1.0.0` - Only after docs, compatibility matrix, auth, benchmark, and integration tests are stable.
