# Changelog

All notable project changes should be recorded here.

## Unreleased

### Added

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

- Replaced the old `RingBuffer` plain-array/atomic-index implementation with a bounded locked FIFO buffer.
- The buffer now supports multiple producer threads correctly, which is required for endpoint samples and request traces.

### Verified

- `mvn test` passes with 51 unit tests.
- `mvn verify` passes with 51 unit tests and 2 integration tests.

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

- No token auth yet.
- No overhead benchmark yet.
- No line-level allocation source view yet.
- No production-grade compatibility matrix yet.
- Multi-instance registry is documented in older docs but not implemented in source.
