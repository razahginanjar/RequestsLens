# Usage Guide

This guide explains how to run and configure the profiler agent.

## Build

```powershell
mvn clean package -DskipTests
```

Agent jar:

```text
target/jvm-profiler-agent-1.0.0-SNAPSHOT.jar
```

## Attach to an App

Basic:

```powershell
java "-javaagent:target/jvm-profiler-agent-1.0.0-SNAPSHOT.jar" -jar your-app.jar
```

With common options:

```powershell
java "-javaagent:target/jvm-profiler-agent-1.0.0-SNAPSHOT.jar=port=7099,interval=10,trace.enabled=true,trace.packages=com.example,trace.sample.rate=50" -jar your-app.jar
```

## Demo App

Build:

```powershell
mvn -q -f demo/pom.xml -DskipTests package
```

Run:

```powershell
java "-javaagent:target/jvm-profiler-agent-1.0.0-SNAPSHOT.jar=port=7099,trace.enabled=true,trace.packages=demo,trace.sample.rate=1,profiler.persistence.enabled=false" -jar demo/target/profiler-demo-app.jar --server.port=8080
```

Generate traffic:

```powershell
curl http://localhost:8080/hello
curl http://localhost:8080/slow
curl http://localhost:8080/cpu
```

Dashboard:

```text
http://localhost:7099/profiler/dashboard
```

## Configuration Sources

Configuration can come from:

1. `jvm-profiler.properties` in the working directory
2. JVM system properties, for example `-Dprofiler.http.port=7099`
3. Agent args after the jar path, for example `=port=7099,interval=20`

## Common Agent Args

Short args:

| Arg | Maps To |
| --- | --- |
| `port` | `profiler.http.port` |
| `interval` | `profiler.sampling.interval.ms` |
| `alert.webhook.url` | `profiler.alert.webhook.url` |
| `max.rps` | `profiler.sampling.adaptive.max.rps` |
| `trace.enabled` | `profiler.trace.enabled` |
| `trace.packages` | `profiler.trace.packages` |
| `trace.sample.rate` | `profiler.trace.sample.rate` |

Example:

```text
port=7099,interval=10,trace.enabled=true,trace.packages=com.example,trace.sample.rate=50
```

## Full Configuration Keys

| Key | Default | Purpose |
| --- | --- | --- |
| `profiler.http.port` | `7070` | Profiler HTTP server port |
| `profiler.sampling.interval.ms` | `10` | Base heap sampling interval |
| `profiler.instance.id` | `host:port` | Agent instance id |
| `profiler.persistence.enabled` | `true` | Enable SQLite persistence |
| `profiler.persistence.path` | `./profiler-data/profiler.db` | SQLite file path |
| `profiler.persistence.retention.days` | `7` | History retention |
| `profiler.sampling.adaptive.enabled` | `true` | Enable RPS-based throttling |
| `profiler.sampling.adaptive.max.rps` | `500` | RPS threshold |
| `profiler.sampling.adaptive.multiplier` | `5` | Throttled interval multiplier |
| `profiler.alert.gc.overhead.threshold` | `15` | GC overhead alert threshold percent |
| `profiler.alert.webhook.url` | empty | Alert webhook URL |
| `profiler.leak.detection.window.ms` | `60000` | Leak detection window |
| `profiler.sampling.profiler.enabled` | `true` | Enable stack-sampling flamegraph |
| `profiler.sampling.profiler.interval.ms` | `20` | Stack sampling interval |
| `profiler.trace.enabled` | `false` | Enable method tracing |
| `profiler.trace.packages` | empty | Comma-separated app package prefixes |
| `profiler.trace.sample.rate` | `50` | Trace 1 of N requests |
| `profiler.trace.max.depth` | `40` | Max method trace depth |
| `profiler.trace.max.spans` | `5000` | Max spans per request trace |
| `profiler.trace.alloc.detail.enabled` | `true` | Enable per-type allocation detail |

## HTTP API

### Status

```text
GET /profiler/status
```

Shows agent health, sampling state, current RPS, trace status, and persistence
queue state.

### Live Heap

```text
GET /profiler/heap
```

Shows current heap and recent live samples.

### GC Events

```text
GET /profiler/gc
```

Shows recent GC events and pause summary.

### Endpoints

```text
GET /profiler/endpoints
```

Shows aggregated Spring MVC endpoint latency.

### Beans

```text
GET /profiler/beans
```

Shows estimated Spring bean memory ranking.

### History

```text
GET /profiler/history/heap?from=<epochMs>&to=<epochMs>
GET /profiler/history/gc?from=<epochMs>&to=<epochMs>
```

Requires persistence to be enabled.

### Leaks

```text
GET /profiler/leaks
```

Shows active leak warnings from the latest aggregation cycle.

### Request Traces

```text
GET /profiler/traces
GET /profiler/trace/{id}
```

Requires tracing to be enabled and package scope configured.

### Flamegraph

```text
GET /profiler/flamegraph
```

Shows folded stack-sampling data.

## Important Notes

- Do not expose the profiler port publicly.
- No auth exists yet.
- Use `trace.packages`; do not trace everything.
- Keep `trace.sample.rate` higher than `1` outside local experiments.
- Endpoint heap delta is directional, not exact retained memory.
- Method allocation data is most useful for finding allocation-heavy code paths.
