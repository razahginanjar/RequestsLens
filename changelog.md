# Changelog

All notable project changes should be recorded here.

## Unreleased

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
- Added unit coverage for line hotspot aggregation, sample caps, and disabled
  line profiling behavior.
- Added `feature_scope.md` to define the current Spring MVC boundary and the
  adapter work required for Quarkus and Micronaut JVM-mode support.
- Added unit coverage for self-monitoring counters plus endpoint/trace buffer
  overwrite drop accounting.
- Added Maven Failsafe integration-test wiring so `mvn verify` can run external agent tests after packaging.
- Added `AgentSpringBootIT`, a real `-javaagent` integration test that:
  - builds the Spring Boot demo fat jar,
  - launches it with the packaged profiler agent,
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

- `mvn test` passes with 79 unit tests.
- `mvn verify` passes with 79 unit tests and 4 integration tests.
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

- No line-level allocation source view yet.
- No CI-backed production compatibility matrix yet.
- Repository URL, SCM metadata, issue tracker metadata, signing keys, publishing
  target, and maintainer contact must be finalized before publishing public
  binaries.
- Multi-instance registry is documented in older docs but not implemented in source.
- Quarkus and Micronaut endpoint/request/bean profiling require new framework
  adapters; generic JVM metrics can run, but first-class framework integration
  is not implemented yet.
