#!/usr/bin/env bash
#
# stress-test.sh — load-test the example Spring Boot app (user CRUD) with the
# JVM Profiler Agent attached, then print the Phase 6 deep-profiling results.
#
# What it does:
#   1. Launches example-app-*.jar with -javaagent:<profiler> and method tracing
#      enabled for the app's package.
#   2. Waits for the app's HTTP port to come up.
#   3. Drives concurrent CRUD traffic against the user controller for a fixed
#      duration (create / list / get / update / delete cycles).
#   4. Queries the agent's /profiler/* endpoints and prints a summary
#      (endpoints, sampling state, request traces, flame graph).
#   5. Shuts the app down cleanly.
#
# Prerequisites:
#   - bash + curl. (jq is optional — output is prettier if present.)
#   - Java 17+ on PATH (or set JAVA_BIN).
#   - The example app needs its database (MySQL on :3306). If the DB is down the
#     app will fail to start and this script will tell you.
#
# Everything below the CONFIG block is generic; tweak CONFIG for your app.

set -u

# ──────────────────────────────────────────────────────────────────────────
# CONFIG — adjust these to match your environment / controller
# ──────────────────────────────────────────────────────────────────────────
JAVA_BIN="${JAVA_BIN:-java}"
AGENT_JAR="${AGENT_JAR:-target/jvm-profiler-agent-1.0.0-SNAPSHOT.jar}"
APP_JAR="${APP_JAR:-example-app-0.0.1-SNAPSHOT.jar}"

APP_PORT="${APP_PORT:-8080}"          # where the Spring app serves
AGENT_PORT="${AGENT_PORT:-7099}"      # where the profiler serves /profiler/*
APP_BASE="http://localhost:${APP_PORT}"
AGENT_BASE="http://localhost:${AGENT_PORT}"

# REST base path of the user CRUD controller and a sample create payload.
USERS_PATH="${USERS_PATH:-/users}"
USER_BODY="${USER_BODY:-{\"name\":\"Stress User\",\"email\":\"stress@example.com\"}}"

# Deep-profiling: instrument the app's own package(s). Adjust to your group id.
TRACE_PACKAGES="${TRACE_PACKAGES:-com.example}"
TRACE_SAMPLE_RATE="${TRACE_SAMPLE_RATE:-20}"   # fully trace 1 of N requests

# Load shape.
CONCURRENCY="${CONCURRENCY:-20}"       # parallel workers
DURATION="${DURATION:-30}"             # seconds of load
STARTUP_TIMEOUT="${STARTUP_TIMEOUT:-90}"  # seconds to wait for the app

APP_LOG="${APP_LOG:-stress-app.log}"

# ──────────────────────────────────────────────────────────────────────────
# Helpers
# ──────────────────────────────────────────────────────────────────────────
APP_PID=""

log()  { printf '\033[1;36m[stress]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[stress]\033[0m %s\n' "$*" >&2; }
err()  { printf '\033[1;31m[stress]\033[0m %s\n' "$*" >&2; }

have_jq() { command -v jq >/dev/null 2>&1; }

# Pretty-print JSON from a /profiler endpoint (uses jq if available).
show_json() {
  local url="$1"
  if have_jq; then
    curl -s --max-time 5 "$url" | jq '.' 2>/dev/null || curl -s --max-time 5 "$url"
  else
    curl -s --max-time 5 "$url"
  fi
  echo
}

cleanup() {
  if [[ -n "$APP_PID" ]] && kill -0 "$APP_PID" 2>/dev/null; then
    log "Stopping app (pid $APP_PID)…"
    kill "$APP_PID" 2>/dev/null
    # Give it a moment, then force if needed.
    for _ in 1 2 3 4 5; do kill -0 "$APP_PID" 2>/dev/null || break; sleep 1; done
    kill -9 "$APP_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT INT TERM

# ──────────────────────────────────────────────────────────────────────────
# Pre-flight checks
# ──────────────────────────────────────────────────────────────────────────
command -v curl >/dev/null 2>&1 || { err "curl is required."; exit 1; }
[[ -f "$AGENT_JAR" ]] || { err "Agent jar not found: $AGENT_JAR (build it: mvn -q clean package -DskipTests)"; exit 1; }
[[ -f "$APP_JAR"   ]] || { err "App jar not found: $APP_JAR"; exit 1; }

# ──────────────────────────────────────────────────────────────────────────
# 1. Launch the app with the agent + deep tracing
# ──────────────────────────────────────────────────────────────────────────
log "Launching $APP_JAR with profiler agent (trace packages: $TRACE_PACKAGES)…"
AGENT_ARGS="port=${AGENT_PORT},trace.enabled=true,trace.packages=${TRACE_PACKAGES},trace.sample.rate=${TRACE_SAMPLE_RATE}"
"$JAVA_BIN" "-javaagent:${AGENT_JAR}=${AGENT_ARGS}" -jar "$APP_JAR" >"$APP_LOG" 2>&1 &
APP_PID=$!
log "App pid: $APP_PID  (logs → $APP_LOG)"

# ──────────────────────────────────────────────────────────────────────────
# 2. Wait for the app to come up
# ──────────────────────────────────────────────────────────────────────────
log "Waiting up to ${STARTUP_TIMEOUT}s for ${APP_BASE} …"
up=0
for ((i=0; i<STARTUP_TIMEOUT; i++)); do
  if ! kill -0 "$APP_PID" 2>/dev/null; then
    err "App process exited during startup. Last log lines:"
    tail -n 25 "$APP_LOG" >&2
    err "If you see a database/connection error, start the app's DB (e.g. MySQL on :3306) and retry."
    exit 1
  fi
  # Any HTTP response (even 404) means the server is listening.
  if curl -s -o /dev/null --max-time 2 "${APP_BASE}${USERS_PATH}"; then up=1; break; fi
  sleep 1
done
[[ "$up" -eq 1 ]] || { err "App did not become reachable within ${STARTUP_TIMEOUT}s."; tail -n 25 "$APP_LOG" >&2; exit 1; }
log "App is up."

# ──────────────────────────────────────────────────────────────────────────
# 3. Concurrent CRUD load
# ──────────────────────────────────────────────────────────────────────────
# One worker: repeatedly create → list → get → update → delete until deadline.
worker() {
  local deadline=$1
  while [[ "$(date +%s)" -lt "$deadline" ]]; do
    local id=$(( (RANDOM % 100) + 1 ))

    # CREATE — try to capture the new id from the response for the read/update.
    local created
    created=$(curl -s --max-time 5 -X POST "${APP_BASE}${USERS_PATH}" \
      -H 'Content-Type: application/json' -d "$USER_BODY")
    local newid
    newid=$(printf '%s' "$created" | grep -o '"id"[ ]*:[ ]*[0-9]*' | head -1 | grep -o '[0-9]*$')
    [[ -n "$newid" ]] && id="$newid"

    curl -s -o /dev/null --max-time 5 "${APP_BASE}${USERS_PATH}"               # LIST
    curl -s -o /dev/null --max-time 5 "${APP_BASE}${USERS_PATH}/${id}"         # GET
    curl -s -o /dev/null --max-time 5 -X PUT "${APP_BASE}${USERS_PATH}/${id}" \
      -H 'Content-Type: application/json' -d "$USER_BODY"                      # UPDATE
    curl -s -o /dev/null --max-time 5 -X DELETE "${APP_BASE}${USERS_PATH}/${id}" # DELETE
  done
}

log "Driving load: ${CONCURRENCY} workers for ${DURATION}s against ${USERS_PATH} …"
deadline=$(( $(date +%s) + DURATION ))
pids=()
for ((w=0; w<CONCURRENCY; w++)); do
  worker "$deadline" &
  pids+=("$!")
done

# Progress dots while workers run.
while [[ "$(date +%s)" -lt "$deadline" ]]; do printf '.'; sleep 2; done
echo
for p in "${pids[@]}"; do wait "$p" 2>/dev/null; done
log "Load finished."

# Give the aggregation daemon a cycle to publish endpoint stats + traces.
log "Letting the agent aggregate (6s)…"
sleep 6

# ──────────────────────────────────────────────────────────────────────────
# 4. Profiler results
# ──────────────────────────────────────────────────────────────────────────
echo
log "================  PROFILER RESULTS  ================"

echo; log "── Agent status (/profiler/status) ──"
show_json "${AGENT_BASE}/profiler/status"

echo; log "── Per-endpoint latency (/profiler/endpoints) ──"
show_json "${AGENT_BASE}/profiler/endpoints"

echo; log "── Recent request traces (/profiler/traces) ──"
show_json "${AGENT_BASE}/profiler/traces"

# Deep-dive the slowest captured trace's method call tree, if jq is present.
if have_jq; then
  slow_id=$(curl -s --max-time 5 "${AGENT_BASE}/profiler/traces" \
    | jq -r '.traces | sort_by(-.totalWallMs) | .[0].traceId // empty' 2>/dev/null)
  if [[ -n "$slow_id" ]]; then
    echo; log "── Slowest trace call tree (/profiler/trace/${slow_id}) ──"
    curl -s --max-time 5 "${AGENT_BASE}/profiler/trace/${slow_id}" \
      | jq '{method, path, totalWallMs: (.totalWallNs/1000000),
             tree: .root}' 2>/dev/null
  fi
fi

echo; log "── Flame graph size (/profiler/flamegraph) ──"
fg_samples=$(curl -s --max-time 5 "${AGENT_BASE}/profiler/flamegraph" \
  | (have_jq && jq -r '.samples' || grep -o '"samples":[0-9]*' | head -1))
log "Flame graph root samples: ${fg_samples:-unknown}"
log "Dashboard: ${AGENT_BASE}/profiler/dashboard"

echo; log "Done. (App will be stopped now.)"
