#!/usr/bin/env bash
#
# Drives the bundled Spring Boot demo app with the RequestLens agent attached and
# prints a compact profiler summary. This is a developer smoke/stress helper,
# not a benchmark. Use scripts/run-overhead-benchmark.ps1 for overhead numbers.

set -euo pipefail

JAVA_BIN="${JAVA_BIN:-java}"
AGENT_JAR="${AGENT_JAR:-target/requestlens-agent-1.0.0-SNAPSHOT.jar}"
DEMO_JAR="${DEMO_JAR:-demo/target/profiler-demo-app.jar}"
APP_PORT="${APP_PORT:-8080}"
AGENT_PORT="${AGENT_PORT:-7099}"
TOKEN="${TOKEN:-dev-token-123456789}"
TRACE_PACKAGES="${TRACE_PACKAGES:-demo}"
TRACE_SAMPLE_RATE="${TRACE_SAMPLE_RATE:-1}"
ROUNDS="${ROUNDS:-25}"
APP_LOG="${APP_LOG:-target/stress-demo.log}"

APP_BASE="http://127.0.0.1:${APP_PORT}"
AGENT_BASE="http://127.0.0.1:${AGENT_PORT}"
APP_PID=""

log() { printf '[stress] %s\n' "$*"; }
err() { printf '[stress] %s\n' "$*" >&2; }

cleanup() {
  if [[ -n "${APP_PID}" ]] && kill -0 "${APP_PID}" 2>/dev/null; then
    log "Stopping demo app pid ${APP_PID}"
    kill "${APP_PID}" 2>/dev/null || true
    for _ in 1 2 3 4 5; do
      kill -0 "${APP_PID}" 2>/dev/null || return 0
      sleep 1
    done
    kill -9 "${APP_PID}" 2>/dev/null || true
  fi
}
trap cleanup EXIT INT TERM

command -v curl >/dev/null 2>&1 || { err "curl is required"; exit 1; }
[[ -f "${AGENT_JAR}" ]] || {
  err "Agent jar not found: ${AGENT_JAR}"
  err "Build it with: mvn clean package -DskipTests"
  exit 1
}
[[ -f "${DEMO_JAR}" ]] || {
  err "Demo jar not found: ${DEMO_JAR}"
  err "Build it with: mvn -q -f demo/pom.xml -DskipTests package"
  exit 1
}

mkdir -p "$(dirname "${APP_LOG}")"

AGENT_ARGS="port=${AGENT_PORT},auth.token=${TOKEN},trace.enabled=true,trace.packages=${TRACE_PACKAGES},trace.sample.rate=${TRACE_SAMPLE_RATE},profiler.persistence.enabled=false"

log "Launching demo app with RequestLens agent"
"${JAVA_BIN}" "-javaagent:${AGENT_JAR}=${AGENT_ARGS}" \
  -jar "${DEMO_JAR}" --server.port="${APP_PORT}" >"${APP_LOG}" 2>&1 &
APP_PID=$!

log "Waiting for ${APP_BASE}/hello"
for _ in $(seq 1 60); do
  if ! kill -0 "${APP_PID}" 2>/dev/null; then
    err "Demo app exited early. Last log lines:"
    tail -n 80 "${APP_LOG}" >&2 || true
    exit 1
  fi
  if curl -fsS --max-time 2 "${APP_BASE}/hello" >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

curl -fsS --max-time 2 "${APP_BASE}/hello" >/dev/null
log "Driving ${ROUNDS} rounds of demo traffic"
for i in $(seq 1 "${ROUNDS}"); do
  curl -fsS --max-time 5 "${APP_BASE}/hello" >/dev/null
  curl -fsS --max-time 5 "${APP_BASE}/slow" >/dev/null
  curl -fsS --max-time 5 "${APP_BASE}/cpu" >/dev/null
  curl -fsS --max-time 5 "${APP_BASE}/items/${i}" >/dev/null
done

log "Waiting for profiler aggregation"
sleep 8

auth_header=("Authorization: Bearer ${TOKEN}")

show() {
  local path="$1"
  echo
  log "${path}"
  curl -fsS --max-time 5 -H "${auth_header[0]}" "${AGENT_BASE}${path}"
  echo
}

show "/profiler/status"
show "/profiler/endpoints"
show "/profiler/traces"
show "/profiler/flamegraph"

echo
log "Dashboard: ${AGENT_BASE}/profiler/dashboard?token=${TOKEN}"
