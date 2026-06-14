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
- Added unit coverage for self-monitoring counters plus endpoint/trace buffer
  overwrite drop accounting.
- Added Maven Failsafe integration-test wiring so `mvn verify` can run external agent tests after packaging.
- Added `AgentSpringBootIT`, a real `-javaagent` integration test that:
  - builds the Spring Boot demo fat jar,
  - launches it with the packaged profiler agent,
  - verifies `/profiler/status`,
  - verifies `/profiler/endpoints`,
  - verifies `/profiler/beans`,
  - verifies `/profiler/traces`,
  - verifies `/profiler/trace/{id}`,
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
- Replaced the old `RingBuffer` plain-array/atomic-index implementation with a bounded locked FIFO buffer.
- The buffer now supports multiple producer threads correctly, which is required for endpoint samples and request traces.

### Verified

- `mvn test` passes with 63 unit tests.
- `mvn verify` passes with 63 unit tests and 3 integration tests.
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
- Adaptive sampling based on endpoint RPS.
- Leak warning detection.
- Webhook alert dispatch.
- Dashboard served from the agent jar.
- Phase 6 method-level request tracing.
- Allocation-site instrumentation for per-type allocation detail.
- Always-on sampling flamegraph data.

## Known Missing Work

- No line-level allocation source view yet.
- No production-grade compatibility matrix yet.
- Multi-instance registry is documented in older docs but not implemented in source.
