#!/usr/bin/env bash
#
# Local end-to-end runbook for the Versatile intent deployment module.
#
# Starts three versatile-intent-boot processes (layer1 / layer2 / downstream)
# on ports 8081 / 8082 / 8083, waits for health, then sends a sample query
# to the layer1 entry point and prints the response.
#
# L2 §5.5.5 lists this script under "联调脚本 — 启动三个本地进程，发送 curl
# 请求验证全链路". Per L2 §5.5.6 边界:
#   - The dev profile disables the A2A Gateway; each layer's Versatile adapter
#     is exercised independently. Full-chain L1->L2->downstream forwarding
#     over HTTP requires either the A2A Gateway (staging/production) or a
#     local card-resolver mapping (not yet implemented in this module).
#   - For in-process full-chain verification (L1->L2 with messages append),
#     run VersatileIntentFlowIntegrationTest — it covers the forwarding path
#     without HTTP.
#
# Prerequisites:
#   - Java 17 on PATH.
#   - A reachable Versatile-compatible SSE endpoint. Set VERSATILE_URL to
#     point to it (real Versatile or a local mock such as WireMock). The
#     default points at a placeholder that will not resolve — override it.
#
# Usage:
#   VERSATILE_URL=http://localhost:9090/mock/versatile/{conversation_id} \
#     ./scripts/local-e2e.sh
#
# Override ports / jar location via env (see defaults below).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

LAYER1_PORT="${LAYER1_PORT:-8081}"
LAYER2_PORT="${LAYER2_PORT:-8082}"
DOWNSTREAM_PORT="${DOWNSTREAM_PORT:-8083}"
VERSATILE_URL="${VERSATILE_URL:-http://versatile-host:3001/v1/{project_id}/agents/agent_L1/conversations/{conversation_id}}"
HEALTH_TIMEOUT_SECONDS="${HEALTH_TIMEOUT_SECONDS:-60}"

JAR_FILE="$MODULE_DIR/target/versatile-intent-boot-0.1.0.jar"

PIDS=()

cleanup() {
    echo
    echo "==> Cleaning up processes: ${PIDS[*]:-<none>}"
    for pid in "${PIDS[@]:-}"; do
        if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
            kill "$pid" 2>/dev/null || true
        fi
    done
    wait 2>/dev/null || true
}
trap cleanup EXIT

build_if_needed() {
    if [ ! -f "$JAR_FILE" ]; then
        echo "==> Jar not found, building: $JAR_FILE"
        (cd "$MODULE_DIR" && mvn -q package -DskipTests)
    else
        echo "==> Using existing jar: $JAR_FILE"
    fi
}

wait_for_health() {
    local port="$1"
    local name="$2"
    local deadline=$(( $(date +%s) + HEALTH_TIMEOUT_SECONDS ))
    echo "==> Waiting for $name on port $port (up to ${HEALTH_TIMEOUT_SECONDS}s)"
    while [ "$(date +%s)" -lt "$deadline" ]; do
        if curl -sf "http://localhost:${port}/actuator/health" >/dev/null 2>&1; then
            echo "    $name is UP (port $port)"
            return 0
        fi
        sleep 1
    done
    echo "ERROR: $name did not become healthy on port $port within ${HEALTH_TIMEOUT_SECONDS}s" >&2
    return 1
}

start_layer() {
    local profile="$1"
    local port="$2"
    local name="$3"
    local log="$MODULE_DIR/target/${name}.log"
    echo "==> Starting $name (profile=$profile, port=$port) -> $log"
    java -jar "$JAR_FILE" \
        --spring.profiles.active="${profile},dev" \
        --server.port="$port" \
        --openjiuwen.service.versatile.url-template="$VERSATILE_URL" \
        >"$log" 2>&1 &
    local pid=$!
    PIDS+=("$pid")
    echo "    pid=$pid"
}

send_sample_query() {
    local port="$1"
    local label="$2"
    echo
    echo "==> Sending sample query to $label (port $port)"
    local body
    body='{"conversation_id":"e2e-c-1","stream":false,"messages":[{"role":"user","content":"我要订酒店"}]}'
    echo "    POST http://localhost:${port}/v1/query"
    echo "    body: $body"
    local resp
    if resp=$(curl -s -X POST "http://localhost:${port}/v1/query" \
        -H "Content-Type: application/json" \
        -d "$body" 2>&1); then
        echo "    response:"
        echo "$resp" | sed 's/^/      /'
    else
        echo "    curl failed: $resp" >&2
        return 1
    fi
}

main() {
    echo "==> VERSATILE_URL=$VERSATILE_URL"
    if [[ "$VERSATILE_URL" == *versatile-host* ]]; then
        echo "WARNING: VERSATILE_URL still points at the placeholder versatile-host." >&2
        echo "         Override with VERSATILE_URL=<reachable endpoint> for a real run." >&2
    fi

    build_if_needed

    start_layer layer1 "$LAYER1_PORT" layer1
    start_layer layer2 "$LAYER2_PORT" layer2
    start_layer downstream "$DOWNSTREAM_PORT" downstream

    wait_for_health "$LAYER1_PORT" layer1
    wait_for_health "$LAYER2_PORT" layer2
    wait_for_health "$DOWNSTREAM_PORT" downstream

    send_sample_query "$LAYER1_PORT" layer1

    echo
    echo "==> Per-layer verification complete."
    echo "==> NOTE: full-chain L1->L2->downstream HTTP forwarding is not exercised here."
    echo "    See script header (L2 §5.5.6) for why — use VersatileIntentFlowIntegrationTest"
    echo "    for in-process chain verification, or staging for end-to-end."
    echo "==> Logs: $MODULE_DIR/target/{layer1,layer2,downstream}.log"
    echo "==> Press Ctrl-C to stop the three processes."
    wait
}

main "$@"
