# Smoke Test Guide

Use this guide for a quick manual check that the agent works outside the test
runner.

Current milestone: `v0.1.0`.

## 1. Build the Agent

```powershell
mvn clean package -DskipTests
```

Expected artifact:

```text
target/requestlens-agent-1.0.0-SNAPSHOT.jar
```

## 2. Build the Demo App

```powershell
mvn -q -f demo/pom.xml -DskipTests package
```

Expected artifact:

```text
demo/target/profiler-demo-app.jar
```

## 3. Run the Demo App With the Agent

```powershell
$token = "dev-token-123456789"
java "-javaagent:target/requestlens-agent-1.0.0-SNAPSHOT.jar=port=7099,auth.token=$token,trace.enabled=true,trace.packages=demo,trace.sample.rate=1,line.enabled=true,line.mode=deterministic,line.packages=demo,line.interval=1,line.alloc.enabled=true,source.enabled=true,source.roots=demo/src/main/java,profiler.persistence.enabled=false" -jar demo/target/profiler-demo-app.jar --server.port=8080
```

This quick command disables persistence so repeated smoke runs do not create a
SQLite file. To smoke-test history, use the optional persistence command below.

The app runs on:

```text
http://localhost:8080
```

The profiler runs on:

```text
http://127.0.0.1:7099
```

## 4. Generate Traffic

Open a second terminal:

```powershell
curl http://localhost:8080/hello
curl http://localhost:8080/slow
curl http://localhost:8080/cpu
curl http://localhost:8080/items/101
curl http://localhost:8080/items/202
curl http://localhost:8080/external
```

For better endpoint and trace data:

```powershell
1..5 | ForEach-Object { curl http://localhost:8080/slow }
1..5 | ForEach-Object { curl http://localhost:8080/cpu }
```

Wait at least 6 seconds so the aggregation daemon can publish metrics.

## 5. Check Profiler Endpoints

```powershell
curl -H "Authorization: Bearer $token" http://127.0.0.1:7099/profiler/api
curl -H "Authorization: Bearer $token" http://127.0.0.1:7099/profiler/status
curl -H "Authorization: Bearer $token" http://127.0.0.1:7099/profiler/heap
curl -H "Authorization: Bearer $token" http://127.0.0.1:7099/profiler/gc
curl -H "Authorization: Bearer $token" http://127.0.0.1:7099/profiler/cpu
curl -H "Authorization: Bearer $token" http://127.0.0.1:7099/profiler/endpoints
curl -H "Authorization: Bearer $token" http://127.0.0.1:7099/profiler/beans
curl -H "Authorization: Bearer $token" http://127.0.0.1:7099/profiler/traces
curl -H "Authorization: Bearer $token" http://127.0.0.1:7099/profiler/package-discovery
curl -H "Authorization: Bearer $token" "http://127.0.0.1:7099/profiler/source?className=demo.DemoApplication&line=30"
curl -H "Authorization: Bearer $token" http://127.0.0.1:7099/profiler/flamegraph
```

Open the dashboard:

```text
http://127.0.0.1:7099/profiler/dashboard?token=dev-token-123456789
```

## Expected Results

- `/profiler/status` returns JSON and shows `traceEnabled: true`,
  `lineProfilingEnabled: true`, `lineMode: deterministic`,
  `deterministicLineProfilingEnabled: true`, `lineAllocEnabled: true`, and
  `sourceViewEnabled: true`.
- `/profiler/status` shows self-monitoring fields such as
  `aggregationCycles`, `profilerHttpRequests`, `droppedEndpointSamples`,
  `droppedCpuSamples`, `droppedTraces`, `persistenceQueueCapacity`,
  `persistenceFlushes`, and `bufferCapacities`.
- `/profiler/status` shows derived health fields such as
  `selfMonitoringStatus`, `selfMonitoringIssueCount`, `totalDroppedSamples`,
  `totalInternalErrors`, and recent metric ages.
- `/profiler/status` shows CPU fields such as `processCpuLoadPercent`,
  `systemCpuLoadPercent`, `agentThreadCpuLoadPercent`, and
  `lastCpuSampleTimestampMs`.
- `/profiler/status` shows `instrumentationDiagnostics` with discovered and
  transformed trace classes, transformed method counts, line-number metadata,
  and recent instrumentation errors.
- `/profiler/status` shows `packageDiscovery` with `suggestedPackage: demo`.
- `/profiler/api` shows `apiVersion`, `routeCount`, `capabilities`, and route
  entries for `/profiler/status`, `/profiler/package-discovery`,
  `/profiler/source`, and
  `/profiler/dashboard`.
- `/profiler/api` shows `externalSqlSpans: true` and
  `externalHttpSpans: true`.
- `/profiler/status` and `/profiler/api` show line profiling as disabled by
  default with sample, line, and payload caps.
- `/profiler/cpu` returns `resource: cpu`, `sampleCount`, `current`, and recent
  CPU samples.
- `/profiler/endpoints` includes `/slow`, `/cpu`, and `/external`.
- `/profiler/endpoints` includes CPU fields such as `avgCpuMs` and
  `avgCpuToWallPercent`.
- `/profiler/endpoints` groups item requests as `/items/{id}`, not separate raw
  paths.
- `/profiler/beans` has a positive `beanCount`.
- `/profiler/traces` has at least one trace and includes deterministic method
  line summary fields such as `deterministicLineCount`,
  `deterministicLineSelfWallNs`, `deterministicLineSelfCpuNs`,
  `deterministicLineAllocationCount`, `deterministicLineAllocatedBytes`,
  `externalSpanCount`, `sqlSpanCount`, and `httpSpanCount`.
- `/profiler/package-discovery` returns `resource: package-discovery`,
  `available: true`, and `suggestedTracePackages: demo`.
- `/profiler/trace/{id}` includes `lineStats` under instrumented method nodes,
  so the dashboard can show `ClassName:lineNumber` rows without source files.
- The `/external` trace includes `sql` and `http` span kinds with sanitized
  SQL/URL resources.
- `/profiler/source?className=demo.DemoApplication&line=30` returns
  `sourceAvailable: true` and a highlighted source line.
- `/profiler/flamegraph` has `samples > 0` after CPU traffic.
- Dashboard loads without external dependencies.
- Dashboard shows the API / Runtime panel.
- Dashboard shows Agent Health summary fields such as Health, Issues, Total
  dropped, and Internal errors.
- In Request Traces, clicking a trace shows self CPU, self allocation, span
  counts, trace cap status, line sample/drop counters, line allocation bytes,
  SQL/HTTP external span counters, call-tree, line-hotspot, method-line self
  time, and source-free line-detail views.

## Optional Persistence Smoke

Run the demo with persistence enabled:

```powershell
$token = "dev-token-123456789"
java "-javaagent:target/requestlens-agent-1.0.0-SNAPSHOT.jar=port=7099,auth.token=$token,trace.enabled=true,trace.packages=demo,trace.sample.rate=1,profiler.persistence.enabled=true,profiler.persistence.path=target/smoke-profiler.db" -jar demo/target/profiler-demo-app.jar --server.port=8080
```

After generating traffic, wait at least 10 seconds for aggregation and
persistence flush cycles. Then query a recent time window:

```powershell
$to = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$from = $to - 120000
curl -H "Authorization: Bearer $token" "http://127.0.0.1:7099/profiler/history/heap?from=$from&to=$to"
curl -H "Authorization: Bearer $token" "http://127.0.0.1:7099/profiler/history/gc?from=$from&to=$to"
curl -H "Authorization: Bearer $token" "http://127.0.0.1:7099/profiler/history/cpu?from=$from&to=$to"
```

Expected persistence results:

- `/profiler/status` shows `persistenceConfigured: true` and
  `persistenceAvailable: true`.
- `/profiler/status` shows `persistenceFlushes > 0` and
  `persistedHeapSamples > 0` plus `persistedCpuSamples > 0`.
- `/profiler/history/heap` includes `sampleCount`, `limited`, `limit`, and
  `samples`.
- `/profiler/history/cpu` includes `sampleCount`, `limited`, `limit`, and
  `samples`.

## Common Failures

### Profiler Port Already Used

Use a different agent port:

```powershell
java "-javaagent:target/requestlens-agent-1.0.0-SNAPSHOT.jar=port=7100,auth.token=$token,trace.enabled=true,trace.packages=demo,trace.sample.rate=1" -jar demo/target/profiler-demo-app.jar --server.port=8080
```

### App Port Already Used

Use a different Spring Boot port:

```powershell
java "-javaagent:target/requestlens-agent-1.0.0-SNAPSHOT.jar=port=7099,auth.token=$token,trace.enabled=true,trace.packages=demo,trace.sample.rate=1" -jar demo/target/profiler-demo-app.jar --server.port=8081
```

### Profiler API Returns 401

Set the same token in the request header:

```powershell
curl -H "Authorization: Bearer $token" http://127.0.0.1:7099/profiler/status
```

### No Traces

Make sure tracing is enabled and package scope is set:

```text
trace.enabled=true,trace.packages=demo,trace.sample.rate=1
```

Then inspect `/profiler/status.instrumentationDiagnostics`:

- `discoveredTraceClasses = 0` usually means the package does not match the
  compiled jar.
- `transformedTraceClasses = 0` with discovered classes usually means the
  matching classes were already loaded too early or transformation failed.
- `recentErrors` should be empty; if not, inspect the error message and target
  class.

For a jar-only target, ask the agent for a package suggestion:

```powershell
curl -H "Authorization: Bearer $token" http://127.0.0.1:7099/profiler/package-discovery
```

### No Line Hot Spots

Make sure line profiling is also enabled and scoped to the app package:

```text
line.enabled=true,line.packages=demo,line.interval=1
```

Line hotspots are sampled, so very fast requests may show zero line samples.
Use `/slow` traffic for the smoke test.

For deterministic method lines, use:

```text
line.enabled=true,line.mode=deterministic,line.packages=demo
```

Deterministic method lines are attached to method spans as `ClassName:lineNumber`
rows and do not require source files.
They include inclusive wall/CPU and self wall/CPU time; self time subtracts
traced child method spans from the parent line.

If deterministic method lines are still empty, check
`/profiler/status.instrumentationDiagnostics.classesWithoutLineNumbers`. A
positive value means some transformed classes lack usable line-number metadata.

### No Line Memory

Make sure line allocation detail is enabled:

```text
line.alloc.enabled=true
```

Line memory is shallow allocation-site memory for traced application methods,
not retained heap.

### No Source View

Make sure source view is enabled and points at source directories visible from
the target process working directory:

```text
source.enabled=true,source.roots=demo/src/main/java
```

Production jars usually do not contain `.java` source files, so source view
requires a matching source checkout or mounted source directory on the target
machine. Deterministic method-line metrics still work without source view.

### Empty Flamegraph

Hit `/cpu`; sleeping endpoints like `/slow` may not be sampled because the
sampler records RUNNABLE threads.
