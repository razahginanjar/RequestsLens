# Changelog

All notable project changes should be recorded here.

## Unreleased

No unreleased changes.

## v0.1.6 - 2026-06-18

### Added

- Added RequestLens YAML agent configuration with explicit `config=...`,
  `config.file=...`, or `config.path=...` agent args.
- Added working-directory auto-discovery for `requestlens-agent.yaml`,
  `requestlens-agent.yml`, `requestlens.yaml`, and `requestlens.yml`.
- Added `/profiler/status` and `/profiler/api` metadata that reports whether
  YAML config was loaded, auto-discovered, or absent.
- Added YAML parser tests for explicit config loading, precedence, and
  auto-discovery order.

### Changed

- Bumped the development artifact to `0.1.6-SNAPSHOT`.
- Documented the config precedence order:
  defaults < `jvm-profiler.properties` < YAML < inline `-javaagent` args
  < `-Dprofiler.*` system properties.
- Added Jackson YAML/SnakeYAML to the shaded runtime dependency notice.

### Verified

- `mvn -B clean verify` passes with 131 unit tests and 4 integration tests.

## v0.1.5 - 2026-06-17

### Added

- Added `/profiler/investigate`, a request-centered investigation endpoint that
  correlates one request trace with nearby JFR events, live target logs,
  external SQL/HTTP spans, trace/line hotspots, and async-profiler profile
  metadata.
- Added a dashboard Investigation tab for selected request traces with summary
  signals, correlated findings, unified hotspots, external spans, timeline rows,
  and native stack matches.
- Added frame-type color mode to the vertical flamegraph so users can separate
  app, framework, JVM, native, and agent frames from percentage-based load
  coloring.
- Added unit and integration coverage for investigation correlation, API route
  metadata, dashboard wiring, and flamegraph color-mode UI.

### Changed

- Bumped the development artifact to `0.1.5-SNAPSHOT`.

### Verified

- `mvn -B clean verify` passes with 127 unit tests and 4 integration tests.

## v0.1.4 - 2026-06-17

### Added

- Added embedded async-profiler native backend support using the official
  `tools.profiler:async-profiler` platform artifacts.
- Added opt-in async-profiler controls and read APIs:
  `/profiler/async/status`, `/profiler/async/start`,
  `/profiler/async/stop`, `/profiler/async/collapsed`, and
  `/profiler/async/flamegraph`.
- Added dashboard Native Profiler controls for bounded CPU, wall, allocation,
  lock, and itimer profiling sessions.
- Added async-profiler config, status/API capability fields, collapsed-stack
  parsing, control-plane stack exclusion, and unit/integration coverage.

### Changed

- Bumped the development artifact to `0.1.4-SNAPSHOT`.

### Verified

- `mvn verify` passes with 125 unit tests and 4 integration tests.

## v0.1.3 - 2026-06-17

### Added

- Added opt-in self-contained JFR integration using an in-process
  `RecordingStream`, exposed through `/profiler/jfr/events`.
- Added bounded JFR event capture for GC, thread sleep/park, monitor waits,
  file/socket I/O, exception statistics, and CPU load with category filtering.
- Added JFR status/API capability fields, buffer capacity reporting, and a
  dashboard JVM Events panel.
- Added unit and integration coverage for JFR config, endpoint response shape,
  dashboard assets, and real packaged-agent runtime capture.

### Changed

- Bumped the development artifact to `0.1.3-SNAPSHOT`.

### Verified

- `mvn verify` passes with 122 unit tests and 4 integration tests.

## v0.1.2 - 2026-06-16

### Added

- Added opt-in bounded live target log capture for Logback, Log4j2, and
  `java.util.logging`, exposed through `/profiler/logs` and the dashboard Live
  Logs panel.
- Added structured GC/JVM event rows to `/profiler/logs`, sharing the same
  bounded timeline as captured app logs.

### Changed

- Cleaned up the dashboard sampling flamegraph with minimum-percent and depth
  controls, frame search, hotness-sorted frames, compact labels, and aggregated
  hidden low-signal frames.
- `/profiler/flamegraph` now returns a bounded server-side flamegraph response
  using `minPct`, `maxDepth`, and `maxChildren`, with hidden frames aggregated
  as synthetic `Other frames`.

### Fixed

- Made the integration test and benchmark harness resolve the packaged
  `requestlens-agent-*.jar` dynamically so version bumps do not break clean CI
  builds.
- Excluded RequestLens HTTP, shaded Jackson, Javalin, Jetty, and agent advice
  frames from collected flamegraph stacks so refreshing the flamegraph does not
  sample its own JSON serialization/control-plane path.

### Verified

- `mvn verify` passes with 119 unit tests and 4 integration tests.

## v0.1.1 - 2026-06-16

### Added

- Added derived `/profiler/status` self-monitoring fields:
  `selfMonitoringStatus`, `selfMonitoringIssues`,
  `selfMonitoringIssueCount`, `totalDroppedSamples`, `totalInternalErrors`,
  and recent metric age fields.
- Added Agent Health dashboard visibility for health, issue count, total drops,
  and internal errors.
- Added benchmark scenarios for request line hotspots and line allocation
  memory.
- Added benchmark report columns that capture `/profiler/status`
  self-monitoring summaries after each agent scenario.
- Added benchmark parser/unit coverage for self-monitoring status snapshots.
- Added opt-in source-code view through `/profiler/source`, scoped by
  `profiler.source.roots` and `profiler.line.packages`.
- Added dashboard Source tab for captured request line hotspots.
- Added source-view unit coverage and integration coverage against the bundled
  demo source file.
- Added opt-in deterministic line profiling through `profiler.line.mode=deterministic`,
  attaching per-method `lineStats` to request traces with line hits, wall/CPU
  time, allocation counts, shallow bytes, and allocation types.
- Added source-free dashboard method-line drilldown using `ClassName:lineNumber`
  so deployed apps can inspect heavy lines without `.java` files on disk.
- Added vertical flamegraph rendering with percentage-based frame colors.
- Added runtime instrumentation diagnostics in `/profiler/status`, including
  discovered/transformed trace classes, transformed method counts, already
  loaded classes, line-number metadata, and recent instrumentation errors.
- Added `/profiler/package-discovery` to suggest `trace.packages` and
  `line.packages` from the runtime jar or an explicitly supplied jar path.
- Added dashboard instrumentation diagnostics and package-suggestion visibility.
- Added unit and integration coverage for instrumentation diagnostics, package
  discovery, and the dashboard instrumentation panel.
- Added deterministic method-line self wall/CPU time, including aggregate
  `deterministicLineSelfWallNs` and `deterministicLineSelfCpuNs` fields.
- Added dashboard no-source-code fallback line details for sampled line
  hotspots when `.java` source files are unavailable.
- Added API capability flags for source-free method lines and deterministic
  line self time.
- Added external request-tree spans for JDBC SQL and Spring `RestTemplate` HTTP
  calls, including sanitized SQL/URL resources and SQL/HTTP span counters in
  trace summary/detail APIs.
- Added dashboard call-tree badges and trace metadata counters for SQL/HTTP
  external spans.
- Added demo `/external` traffic and integration coverage for SQL/HTTP external
  spans.
- Added opt-in Request Debug Snapshot Mode for traced methods, including
  bounded argument, return-value, and exception summaries on method spans.
- Added debug snapshot status/API capability fields, trace summary/detail
  counters, dashboard call-tree rows, and unit/integration coverage.
- Added request trace explanation and same-route comparison data in
  `/profiler/trace/{id}`, plus a dashboard Explain tab with bottleneck signals,
  top contributors, and peer deltas.

### Changed

- `scripts/run-overhead-benchmark.ps1` now accepts `-LineIntervalMs` for the
  line-hotspot and line-memory benchmark scenarios.
- `.github/workflows/release-artifacts.yml` now publishes release artifacts
  automatically when a `v*` tag is pushed.
- `/profiler/status` and `/profiler/api` now report line profiling mode and
  sampled/deterministic line profiling state.
- The dashboard trace table now summarizes deterministic method lines when
  available instead of depending only on sampled line hotspots.
- `/profiler/api` now reports instrumentation diagnostics and package discovery
  capabilities, route metadata, and links.
- Troubleshooting docs now direct jar-only users to package discovery and
  instrumentation diagnostics before assuming a request is too fast.
- The dashboard Method lines tab now prioritizes source-free
  `ClassName:lineNumber` rows and sorts them by self wall time.
- Deterministic line self time now subtracts traced external SQL/HTTP spans
  from the application line active at the call site.

### Verified

- `mvn verify` passes with 111 unit tests and 4 integration tests.

## v0.1.0 - 2026-06-15

### Added

- Added token-based auth for profiler HTTP routes. When `profiler.auth.token`
  is set, `/profiler/*` requires `Authorization: Bearer <token>` or a
  dashboard `?token=<token>` query parameter.
- Added `profiler.http.host`, defaulting to `127.0.0.1`, so the embedded HTTP
  server is loopback-only unless explicitly configured otherwise.
- Added disabled-by-default CORS controls through
  `profiler.http.cors.enabled` and `profiler.http.cors.allowed.origins`.
- Added restricted OPTIONS preflight handlers for profiler routes when CORS is
  explicitly enabled.
- Added startup warnings when profiler HTTP auth is disabled.
- Added status metadata for `httpHost`, `authEnabled`, `corsEnabled`, and
  `sensitiveDetailsRedacted`.
- Added integration coverage that verifies authenticated profiler APIs reject
  unauthenticated requests and accept bearer tokens.
- Added integration coverage for an allowed CORS preflight request.
- Added endpoint-pattern integration coverage so path-variable requests are
  grouped by Spring's matched route pattern.
- Added trace quality metadata: `capturedSpans`, `droppedSpans`, `truncated`,
  `depthLimitExceeded`, and `spanLimitExceeded`.
- Added unit coverage for route-pattern extraction, total endpoint request
  counts, rolling heap deltas, and trace truncation behavior.
- Added an opt-in overhead benchmark harness that compares `baseline`,
  `agent-live`, `agent-trace-sampled`, and `agent-trace-full` modes.
- Added `scripts/run-overhead-benchmark.ps1` and `benchmark.md` for repeatable
  local benchmark runs.
- Added agent self-monitoring counters for dropped GC events, endpoint samples,
  request traces, aggregation cycles/errors/duration, profiler HTTP requests,
  profiler HTTP auth failures, and buffer capacities.
- Added dashboard Agent Health metrics for endpoint, GC, trace, aggregation,
  and profiler HTTP self-monitoring.
- Added `/profiler/api`, a machine-readable API catalog with route metadata,
  capability flags, auth/redaction state, and dashboard/API links.
- Added `apiVersion`, `generatedAtMs`, and `resource` metadata to map-shaped
  profiler API responses.
- Added dashboard API/runtime capability display and clearer profiler HTTP
  error state handling.
- Added persistence health metadata to `/profiler/status`, including queue
  capacity, history limit, flush counts/failures, persisted heap/GC row counts,
  purge counts/failures, and last purge/flush timestamps.
- Added bounded history query metadata: `/profiler/history/heap` and
  `/profiler/history/gc` now return `limited` and `limit` fields.
- Added composite SQLite indexes on `(instance_id, ts_ms)` for heap and GC
  history queries.
- Added integration coverage that starts the real demo app with SQLite
  persistence enabled and verifies persisted heap history through the packaged
  agent.
- Added Apache-2.0 licensing files and Maven license metadata.
- Added open-source governance docs: `CONTRIBUTING.md`, `SECURITY.md`,
  `SUPPORT.md`, `CODE_OF_CONDUCT.md`, `COMPATIBILITY.md`,
  `RELEASE_CHECKLIST.md`, and `THIRD_PARTY_NOTICES.md`.
- Added GitHub issue templates and pull request template.
- Added `.editorconfig`, `.gitattributes`, and expanded `.gitignore` for
  common local/build artifacts and predictable line endings.
- Added GitHub Actions CI workflow for Java 17/21 on Ubuntu and Windows.
- Added manual release artifact workflow that packages `target/release/`.
- Added `scripts/prepare-release.ps1` for local release artifact collection,
  source jar generation, checksum creation, and release summary output.
- Added `build_release.md` with CI, release profile, artifact, and publishing
  guidance.
- Added live CPU monitoring through `/profiler/cpu`, including process CPU,
  system CPU, cumulative profiler-thread CPU, and profiler-thread CPU load.
- Added `profiler.cpu.sampling.interval.ms` and short arg `cpu.interval` for
  tuning CPU sample cadence independently from heap sampling.
- Added persisted CPU history through `/profiler/history/cpu` and a
  `cpu_samples` SQLite table with instance/time indexes.
- Added endpoint CPU statistics (`avgCpuMs`, `maxCpuMs`,
  `avgCpuToWallPercent`) alongside latency and heap delta data.
- Added CPU status/self-monitoring fields for CPU sample timestamps, CPU buffer
  capacity, dropped CPU samples, and persisted CPU row counts.
- Added dashboard CPU usage panel and endpoint CPU columns.
- Added dashboard request trace detail metadata for clicked traces, including
  total CPU, self CPU, self allocation, trace cap state, and redaction-aware
  messaging.
- Added disabled-by-default line-profiling safety configuration, including
  target package allow-listing, dependency/agent/JDK class exclusion, sample
  caps, line caps, payload caps, allocation-by-line gating, and status/API
  visibility.
- Added opt-in request-scoped line hotspot profiling for traced requests,
  sampled from a profiler-owned background thread and attached to trace detail
  after the request response path completes.
- Added line hotspot fields to `/profiler/traces`, `/profiler/trace/{id}`,
  `/profiler/status`, and `/profiler/api`.
- Added dashboard trace-detail UI for sampled line hotspots, including sample
  counts, estimated wall time, and estimated CPU time per source line.
- Added dashboard trace summary counters, selected-trace highlighting, and
  call-tree/line-hotspot tabs for clicked request traces.
- Added opt-in memory-per-line profiling through `profiler.line.alloc.enabled`,
  reporting shallow allocation bytes and allocation counts per source line.
- Added line allocation totals to `/profiler/traces` and redacted
  `/profiler/trace/{id}` responses.
- Added unit coverage for line hotspot aggregation, sample caps, and disabled
  line profiling behavior.
- Added `feature_scope.md` to define the current Spring MVC boundary and the
  adapter work required for Quarkus and Micronaut JVM-mode support.
- Added unit coverage for self-monitoring counters plus endpoint/trace buffer
  overwrite drop accounting.
- Added Maven Failsafe integration-test wiring so `mvn verify` can run external agent tests after packaging.
- Added `AgentSpringBootIT`, a real `-javaagent` integration test that:
  - builds the Spring Boot demo fat jar,
  - launches it with the packaged RequestLens agent,
  - verifies `/profiler/status`,
  - verifies `/profiler/api`,
  - verifies `/profiler/endpoints`,
  - verifies `/profiler/beans`,
  - verifies `/profiler/traces`,
  - verifies `/profiler/trace/{id}`,
  - verifies sampled request line hotspots,
  - verifies `/profiler/flamegraph`,
  - verifies app startup continues when the agent HTTP port is unavailable.
- Added integration coverage for allocation instrumentation across:
  - primitive arrays,
  - app objects,
  - app object arrays,
  - `Object[]`,
  - multidimensional primitive arrays.
- Added a concurrent producer regression test for `RingBuffer`.
- Added `/cpu` endpoint to the demo app to produce RUNNABLE work for flamegraph sampling.
- Expanded `/slow` in the demo app to allocate several object kinds for trace/allocation verification.

### Changed

- Renamed the project and Maven artifact branding to RequestLens
  (`requestlens-agent`) for the `v0.1.0` baseline.
- Sensitive bean/class details are now hidden unless auth is enabled or the
  HTTP server is bound to a loopback host. This covers bean names/classes, full
  trace call trees, allocation type names, flamegraph frames, and trace package
  config.
- The bundled dashboard now forwards a token from `/profiler/dashboard?token=...`
  to JSON API calls.
- Endpoint stats now prefer Spring matched route patterns over raw request URIs.
- Endpoint `requestCount` now represents total observed requests instead of the
  capped p95 rolling-window size.
- Endpoint heap delta now uses a rolling window and no longer resets to zero on
  aggregation cycles with no new requests.
- Method trace cap handling now suppresses allocation attribution inside
  untracked subtrees, avoiding false parent-method allocation detail.
- Agent argument parsing now keeps comma-separated continuation values so
  package-list settings such as `trace.packages` and `line.packages` can be
  provided through `-javaagent:...`.
- Replaced the old `RingBuffer` plain-array/atomic-index implementation with a bounded locked FIFO buffer.
- The buffer now supports multiple producer threads correctly, which is required for endpoint samples and request traces.
- Persistence writes now return persisted row counts and surface SQLite write
  failures to self-monitoring instead of silently swallowing them.
- Persistence history queries now return explicit API errors on SQLite query
  failure instead of looking like an empty history result.
- `stress-test.sh` now targets the bundled demo app and authenticated profiler
  APIs instead of an old external example app.
- Maven builds now enforce Java 17+ and Maven 3.9+, use compiler release mode,
  include implementation manifest metadata, and support a `release-artifacts`
  profile for source jars.

### Verified

- `mvn test` passes with 82 unit tests.
- `mvn verify` passes with 82 unit tests and 4 integration tests.
- `scripts/run-overhead-benchmark.ps1 -Requests 100 -Warmup 20 -Concurrency 4`
  runs successfully and writes Markdown/CSV reports under `target/benchmark-results/`.

## Existing Project Capabilities

The project already includes:

- Java agent startup with `premain` and `agentmain`.
- Embedded Javalin HTTP server.
- Live heap sampling.
- GC event listener.
- Spring MVC endpoint tracking via Byte Buddy.
- Spring bean memory estimation.
- SQLite persistence for heap and GC history.
- Live and persisted CPU monitoring.
- Adaptive sampling based on endpoint RPS.
- Leak warning detection.
- Webhook alert dispatch.
- Dashboard served from the agent jar.
- Phase 6 method-level request tracing.
- Allocation-site instrumentation for per-type allocation detail.
- Always-on sampling flamegraph data.

## Known Missing Work

- No CI-backed production compatibility matrix yet.
- Repository URL, SCM metadata, issue tracker metadata, signing keys, publishing
  target, and maintainer contact must be finalized before publishing public
  binaries.
- Multi-instance registry is documented in older docs but not implemented in source.
- Quarkus and Micronaut endpoint/request/bean profiling require new framework
  adapters; generic JVM metrics can run, but first-class framework integration
  is not implemented yet.
