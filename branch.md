# Branch Plan

This project is now at the `v0.1.0` alpha/dev baseline on `master`. The
current codebase is suitable for local development and controlled staging
experiments, but it still needs CI results from the public repository plus
publishing metadata before it should be presented as ready for broad external
use.

## Current Baseline

- Java agent entry point: `agent.core.AgentMain`
- Build tool: Maven
- Java target: 17
- Demo target app: `demo/`
- Dashboard: `src/main/resources/dashboard/index.html`
- Full verification command: `mvn verify`
- Current milestone: `v0.1.0`
- Current integration branch target: `master`
- Current active feature branch: `feature/deterministic-line-trace`

## Completed Hardening Work

The P0 correctness work, P1 HTTP safety slice, P1 profiling-quality slice,
benchmark slice, P1 self-monitoring slice, P2 API/dashboard slice, P2
persistence slice, P2 open-source readiness slice, P2 build/release slice, P2
CPU monitoring slice, P3 feature-scope slice, dashboard trace-detail slice, P0
line-profiling safety slice, P1 request-scoped line hotspot profiling slice,
P1 dashboard update slice, P2 memory-per-line slice, P2
self-monitoring/benchmark slice, and P3 source-code view slice are complete:

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
- Added Apache-2.0 license metadata and open-source governance docs.
- Added compatibility, release, security, support, contribution, and
  third-party notice documents.
- Added issue/PR templates, `.editorconfig`, `.gitattributes`, expanded
  `.gitignore`, and a demo-focused `stress-test.sh`.
- Added CI workflow for Java 17/21 on Ubuntu and Windows.
- Added release artifact workflow, release preparation script, checksum output,
  Maven manifest metadata, build enforcer rules, release source jar profile, and
  automatic GitHub Release publishing on `v*` tag pushes.
- Added live process/system/profiler-thread CPU monitoring through
  `/profiler/cpu`.
- Added persisted CPU history through `/profiler/history/cpu` and SQLite
  `cpu_samples` retention.
- Added endpoint CPU statistics and dashboard CPU visibility.
- Added `feature_scope.md` with the Quarkus/Micronaut feasibility decision,
  adapter plan, and support boundary.
- Expanded clicked request trace dashboard details to show CPU/self CPU,
  allocation/self-allocation, span cap metadata, and redaction-aware messaging.
- Added disabled-by-default line-profiling guardrails: target package
  allow-listing, dependency/agent/JDK class exclusion, sample caps, line caps,
  payload caps, allocation-by-line gating, status/API visibility, and config
  tests.
- Added request-scoped sampled line hotspot profiling for traced requests.
- Added line hotspot metadata to `/profiler/status`, `/profiler/api`,
  `/profiler/traces`, and `/profiler/trace/{id}`.
- Added dashboard trace-detail rendering for sampled line hotspots.
- Added dashboard trace summary counters, selected-trace highlighting, and
  call-tree/line-hotspot tabs.
- Added opt-in memory-per-line profiling with shallow allocation bytes/counts
  per source line.
- Added derived self-monitoring health status, issue categories, drop/error
  totals, metric ages, and dashboard visibility.
- Expanded the overhead benchmark to cover line-hotspot and line-memory
  scenarios and to include post-scenario `/profiler/status` summaries.
- Added opt-in source-code view for captured application line hotspots through
  `/profiler/source` and a dashboard Source tab.
- Added unit and integration coverage for line hotspot collection, source-code
  lookup, and UI assets.
- Verified `mvn test` and `mvn verify` pass.

## Merged Branches

The completed feature branches have been merged into `master`. Future work
should continue to use short-lived branches and merge after `mvn verify`
passes.

| Branch | Purpose |
| --- | --- |
| `hardening/p0-agent-it` | Completed P0 integration-test and buffer hardening work |
| `hardening/auth` | Completed P1 token auth and local bind defaults |
| `hardening/profiling-quality` | Completed P1 accuracy and profiling-quality work |
| `hardening/benchmark` | Completed opt-in overhead benchmark harness |
| `hardening/self-monitoring` | Completed P1 self-monitoring metrics and tests |
| `hardening/api-dashboard` | Completed P2 API catalog and dashboard capability polish |
| `hardening/persistence` | Completed P2 persistence observability and history hardening |
| `feature/line-allocation` | Superseded by `feature/memory-per-line` |
| `docs/open-source-readiness` | Completed license, contribution, security, support, compatibility, and release docs |
| `docs/build-release` | Completed CI and release artifact scaffolding |
| `hardening/cpu-monitoring` | Completed P2 live/persisted CPU monitoring and endpoint CPU stats |
| `docs/framework-scope` | Completed P3 framework scope and Quarkus/Micronaut adapter plan |
| `hardening/trace-detail-ui` | Completed dashboard trace-detail visibility polish |
| `hardening/line-safety` | Completed P0 line-profiling safety configuration |
| `feature/request-line-hotspots` | Completed P1 request-scoped sampled line hotspot profiling |
| `feature/dashboard-line-hotspot-ui` | Completed P1 trace dashboard update |
| `feature/memory-per-line` | Completed P2 shallow allocation bytes/counts per source line |
| `hardening/self-monitoring-benchmark-p2` | Completed P2 derived self-monitoring health and benchmark status reporting |
| `feature/source-code-view-p3` | Completed P3 source-code view and tag-driven release publishing |
| `feature/deterministic-line-trace` | In progress: deterministic per-method line profiling and vertical flamegraph UI |
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

- `v0.1.0` - Current alpha/dev baseline after safety, auth, profiling quality,
  benchmark, self-monitoring, API/dashboard, persistence, open-source docs,
  build/release, CPU monitoring, framework-scope docs, line hotspots,
  memory-per-line profiling, and source-code view.
- `v0.2.0` - Next compatibility or framework-adapter milestone.
- `v1.0.0` - Only after public CI matrix results, repository/SCM metadata,
  signing/publishing credentials, maintainer contacts, auth, benchmark, and
  integration tests are stable.
