# Overhead Benchmark

This project includes an opt-in benchmark harness for measuring the RequestLens agent
overhead against the Spring Boot demo app.

The benchmark launches six scenarios:

| Scenario | Meaning |
| --- | --- |
| `baseline` | Demo app without the agent |
| `agent-live` | Agent attached, tracing disabled |
| `agent-trace-sampled` | Agent attached, method tracing enabled with sample rate 50 |
| `agent-trace-full` | Agent attached, method tracing enabled with sample rate 1 |
| `agent-line-hotspots` | Agent attached, full tracing plus sampled line hotspots |
| `agent-line-memory` | Agent attached, full tracing plus line hotspots and per-line allocation detail |

## Run

From the repository root:

```powershell
.\scripts\run-overhead-benchmark.ps1
```

Useful parameters:

```powershell
.\scripts\run-overhead-benchmark.ps1 -Requests 1000 -Warmup 200 -Concurrency 16 -Endpoint /hello -LineIntervalMs 5
```

The script builds the agent, builds the demo app, compiles the benchmark runner,
and then runs:

```text
agent.benchmark.AgentOverheadBenchmark
```

## Output

Reports are written to:

```text
target/benchmark-results/overhead-benchmark.md
target/benchmark-results/overhead-benchmark.csv
```

Process logs are written to:

```text
target/benchmark-logs/
```

## Metrics

| Metric | Meaning |
| --- | --- |
| `RPS` | Completed requests per second for the measured endpoint |
| `RPS overhead` | Percent throughput loss versus `baseline` |
| `Avg ms` | Mean request latency |
| `P50 ms` | Median request latency |
| `P95 ms` | 95th percentile request latency |
| `Max ms` | Slowest measured request |
| `Self status` | `/profiler/status` self-monitoring status after the scenario |
| `Issues` | Count of self-monitoring issue categories reported by the agent |
| `Issue names` | Comma-separated self-monitoring issue categories |
| `Drops` | Total dropped heap/GC/endpoint/CPU/trace/persistence samples |
| `Errors` | Total aggregation and persistence internal errors |
| `Agg cycles` | Aggregation daemon cycles observed by the agent |
| `Agg ms` | Last aggregation duration in milliseconds |
| `HTTP req` | Profiler HTTP requests observed by the agent |

RPS overhead is calculated as:

```text
(baselineRps - scenarioRps) / baselineRps * 100
```

Negative overhead means the measured scenario was faster than baseline on that
run. Treat that as benchmark noise unless it repeats across several runs.

Self-monitoring columns are collected from `/profiler/status` after each agent
scenario. They make it visible when a benchmark result was collected while the
agent was dropping data, reporting internal errors, or failing to serve the
profiler status endpoint.

## Guidance

- Run on an idle machine.
- Plug in laptops and disable battery saver.
- Run the benchmark at least three times and compare medians.
- Prefer `/hello` for agent overhead because it is a fast endpoint.
- Use `/cpu` only when you specifically want CPU-bound behavior.
- Use `-LineIntervalMs` to change the sampled line-hotspot interval for the
  `agent-line-hotspots` and `agent-line-memory` scenarios.
- Do not compare numbers across different machines.

The benchmark is not part of `mvn verify` because performance measurements are
host-load dependent and would make correctness CI flaky.

## Local Reference Run

This is a small smoke run on the current development machine, recorded only as
a reference that the harness works. Do not treat these numbers as portable.

```text
Command: .\scripts\run-overhead-benchmark.ps1 -Requests 100 -Warmup 20 -Concurrency 4 -Endpoint /hello
Date: 2026-06-14
Java: 17
OS: Windows 10
```

The benchmark report now includes additional line-hotspot, line-memory, and
self-monitoring columns; rerun the command above to produce current local
numbers for this machine.
