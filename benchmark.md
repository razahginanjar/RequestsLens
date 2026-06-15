# Overhead Benchmark

This project includes an opt-in benchmark harness for measuring the RequestLens agent
overhead against the Spring Boot demo app.

The benchmark launches four scenarios:

| Scenario | Meaning |
| --- | --- |
| `baseline` | Demo app without the agent |
| `agent-live` | Agent attached, tracing disabled |
| `agent-trace-sampled` | Agent attached, method tracing enabled with sample rate 50 |
| `agent-trace-full` | Agent attached, method tracing enabled with sample rate 1 |

## Run

From the repository root:

```powershell
.\scripts\run-overhead-benchmark.ps1
```

Useful parameters:

```powershell
.\scripts\run-overhead-benchmark.ps1 -Requests 1000 -Warmup 200 -Concurrency 16 -Endpoint /hello
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

RPS overhead is calculated as:

```text
(baselineRps - scenarioRps) / baselineRps * 100
```

Negative overhead means the measured scenario was faster than baseline on that
run. Treat that as benchmark noise unless it repeats across several runs.

## Guidance

- Run on an idle machine.
- Plug in laptops and disable battery saver.
- Run the benchmark at least three times and compare medians.
- Prefer `/hello` for agent overhead because it is a fast endpoint.
- Use `/cpu` only when you specifically want CPU-bound behavior.
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

| Scenario | RPS | RPS overhead | Avg ms | P50 ms | P95 ms | Max ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `baseline` | 1200.48 | 0.00% | 3.31 | 3.21 | 4.72 | 5.08 |
| `agent-live` | 1240.85 | -3.36% | 3.08 | 2.89 | 4.80 | 8.52 |
| `agent-trace-sampled` | 1121.24 | 6.60% | 3.53 | 3.33 | 5.46 | 5.83 |
| `agent-trace-full` | 1231.08 | -2.55% | 3.21 | 3.10 | 4.83 | 5.69 |
