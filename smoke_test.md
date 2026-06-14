# Smoke Test Guide

Use this guide for a quick manual check that the agent works outside the test
runner.

## 1. Build the Agent

```powershell
mvn clean package -DskipTests
```

Expected artifact:

```text
target/jvm-profiler-agent-1.0.0-SNAPSHOT.jar
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
java "-javaagent:target/jvm-profiler-agent-1.0.0-SNAPSHOT.jar=port=7099,auth.token=$token,trace.enabled=true,trace.packages=demo,trace.sample.rate=1,profiler.persistence.enabled=false" -jar demo/target/profiler-demo-app.jar --server.port=8080
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
curl -H "Authorization: Bearer $token" http://127.0.0.1:7099/profiler/flamegraph
```

Open the dashboard:

```text
http://127.0.0.1:7099/profiler/dashboard?token=dev-token-123456789
```

## Expected Results

- `/profiler/status` returns JSON and shows `traceEnabled: true`.
- `/profiler/status` shows self-monitoring fields such as
  `aggregationCycles`, `profilerHttpRequests`, `droppedEndpointSamples`,
  `droppedCpuSamples`, `droppedTraces`, `persistenceQueueCapacity`,
  `persistenceFlushes`, and `bufferCapacities`.
- `/profiler/status` shows CPU fields such as `processCpuLoadPercent`,
  `systemCpuLoadPercent`, `agentThreadCpuLoadPercent`, and
  `lastCpuSampleTimestampMs`.
- `/profiler/api` shows `apiVersion`, `routeCount`, `capabilities`, and route
  entries for `/profiler/status` and `/profiler/dashboard`.
- `/profiler/cpu` returns `resource: cpu`, `sampleCount`, `current`, and recent
  CPU samples.
- `/profiler/endpoints` includes `/slow` and `/cpu`.
- `/profiler/endpoints` includes CPU fields such as `avgCpuMs` and
  `avgCpuToWallPercent`.
- `/profiler/endpoints` groups item requests as `/items/{id}`, not separate raw
  paths.
- `/profiler/beans` has a positive `beanCount`.
- `/profiler/traces` has at least one trace.
- `/profiler/flamegraph` has `samples > 0` after CPU traffic.
- Dashboard loads without external dependencies.
- Dashboard shows the API / Runtime panel.
- In Request Traces, clicking a trace shows self CPU, self allocation, span
  counts, and trace cap status.

## Optional Persistence Smoke

Run the demo with persistence enabled:

```powershell
$token = "dev-token-123456789"
java "-javaagent:target/jvm-profiler-agent-1.0.0-SNAPSHOT.jar=port=7099,auth.token=$token,trace.enabled=true,trace.packages=demo,trace.sample.rate=1,profiler.persistence.enabled=true,profiler.persistence.path=target/smoke-profiler.db" -jar demo/target/profiler-demo-app.jar --server.port=8080
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
java "-javaagent:target/jvm-profiler-agent-1.0.0-SNAPSHOT.jar=port=7100,auth.token=$token,trace.enabled=true,trace.packages=demo,trace.sample.rate=1" -jar demo/target/profiler-demo-app.jar --server.port=8080
```

### App Port Already Used

Use a different Spring Boot port:

```powershell
java "-javaagent:target/jvm-profiler-agent-1.0.0-SNAPSHOT.jar=port=7099,auth.token=$token,trace.enabled=true,trace.packages=demo,trace.sample.rate=1" -jar demo/target/profiler-demo-app.jar --server.port=8081
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

### Empty Flamegraph

Hit `/cpu`; sleeping endpoints like `/slow` may not be sampled because the
sampler records RUNNABLE threads.
