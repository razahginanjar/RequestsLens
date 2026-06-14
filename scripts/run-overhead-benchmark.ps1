param(
    [int]$Requests = 500,
    [int]$Warmup = 100,
    [int]$Concurrency = 8,
    [string]$Endpoint = "/hello"
)

$ErrorActionPreference = "Stop"

mvn -q -DskipTests package
mvn -q -f demo/pom.xml -DskipTests package
mvn -q -DskipTests test-compile

java `
  "-Dbenchmark.requests=$Requests" `
  "-Dbenchmark.warmup=$Warmup" `
  "-Dbenchmark.concurrency=$Concurrency" `
  "-Dbenchmark.endpoint=$Endpoint" `
  -cp target/test-classes `
  agent.benchmark.AgentOverheadBenchmark
