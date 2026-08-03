#!/usr/bin/env bash
#
# Local end-to-end runbook for the Versatile intent deployment module.
# Implements L2 §5.5.3 方案 B (multi-port local runtime) with the
# mock-versatile profile, and exercises three of the L2 §6.2 scenarios.
#
# Architecture:
#   - Each layer runs as a separate versatile-intent-boot process.
#   - The mock-versatile profile activates MockVersatileController inside
#     every process, serving canned SSE keyed by (agentId, query content).
#   - Cross-layer forwarding uses DefaultRemoteAgentCaller over HTTP, routed
#     by LocalMappingCardRegistrar (card-resolver.local-mapping → localhost).
#
# Scenarios (L2 §6.2):
#   Round 1:
#     §6.2.1  L1→L2→downstream  curl L1 "订酒店"      → "酒店预订成功"
#     §6.2.3  explicit interrupt  curl L1 "中断"        → _interrupt payload
#   Round 2:
#     §6.2.4  L2 ambiguous self-heal  curl L1 "意图不明" → "默认工作流兜底"
#
# Each round starts the processes it needs with the right mode (three-field
# vs legacy) and stops them before the next round begins.
#
# Usage:
#   ./scripts/local-e2e.sh            # build if needed, run all scenarios
#   SKIP_BUILD=1 ./scripts/local-e2e.sh   # skip build (use existing jar)
#
# Prerequisites: Java 17 on PATH, and the prerequisite artifacts
# (agent-service-app / agent-service-adapters-versatile) installed in the
# local Maven repository — see README.md「前置依赖 → 首次构建」.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

L1_PORT="${L1_PORT:-8081}"
L2_PORT="${L2_PORT:-8082}"
DOWNSTREAM_PORT="${DOWNSTREAM_PORT:-8083}"
DEFAULT_WF_PORT="${DEFAULT_WF_PORT:-8085}"
HEALTH_TIMEOUT_SECONDS="${HEALTH_TIMEOUT_SECONDS:-90}"
JAR_FILE="$MODULE_DIR/target/versatile-intent-boot-0.1.0.jar"

# Local Maven repository (override via M2_REPO). Used only to give a helpful
# early error when prerequisite artifacts are missing — not a build input.
M2_REPO="${M2_REPO:-$HOME/.m2/repository}"

# Prerequisite artifacts this example depends on. They are NOT built by this
# script — they must be installed first (see README.md「前置依赖 → 首次构建」).
PREREQ_JARS=(
    "com/openjiuwen/agent-service-app/0.1.0/agent-service-app-0.1.0.jar"
    "com/openjiuwen/agent-service-adapters-versatile/0.1.0/agent-service-adapters-versatile-0.1.0.jar"
)

PIDS=()
LOG_DIR="$MODULE_DIR/target"

mkdir -p "$LOG_DIR"

cleanup() {
    echo
    echo "==> Stopping processes: ${PIDS[*]:-<none>}"
    for pid in "${PIDS[@]:-}"; do
        if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
            kill "$pid" 2>/dev/null || true
        fi
    done
    PIDS=()
    wait 2>/dev/null || true
}
trap cleanup EXIT

build_if_needed() {
    if [ "${SKIP_BUILD:-0}" = "1" ]; then
        echo "==> SKIP_BUILD=1, using existing jar"
        return
    fi
    if [ ! -f "$JAR_FILE" ]; then
        echo "==> Building jar: $JAR_FILE"
        (cd "$MODULE_DIR" && mvn -q package -DskipTests)
    else
        echo "==> Using existing jar: $JAR_FILE"
    fi
}

# Verifies the prerequisite artifacts exist in the local Maven repository.
# This script only packages this module's jar — it does NOT build the
# prerequisites. Failing fast here avoids a confusing 73-error compile failure.
check_dependencies() {
    local missing=()
    for rel in "${PREREQ_JARS[@]}"; do
        if [ ! -f "$M2_REPO/$rel" ]; then
            missing+=("$rel")
        fi
    done
    if [ ${#missing[@]} -ne 0 ]; then
        echo "ERROR: 缺少前置依赖，无法编译本模块：" >&2
        for rel in "${missing[@]}"; do
            echo "  - $M2_REPO/$rel" >&2
        done
        cat >&2 <<'EOF'
本脚本仅构建 versatile-intent-boot 自身 jar，不构建前置依赖。
请先按 README.md「前置依赖 → 首次构建」安装前置依赖，例如：
  # 1. 构建外部 runtime 核心（提供 agent-service-app）
  cd <agent-runtime-java> && mvn clean install -DskipTests
  # 2. 构建本仓 extension 模块（提供 agent-service-adapters-versatile）
  mvn -f common/agent-runtime-ext-java/pom.xml clean install -DskipTests
详见仓库根 CONTRIBUTING.md「Development Setup」。
EOF
        return 1
    fi
    echo "==> Prerequisite artifacts present in $M2_REPO"
}

wait_for_health() {
    local port="$1"
    local name="$2"
    local deadline=$(( $(date +%s) + HEALTH_TIMEOUT_SECONDS ))
    printf "    %-12s " "$name:"
    while [ "$(date +%s)" -lt "$deadline" ]; do
        if curl -sf "http://localhost:${port}/health" >/dev/null 2>&1; then
            echo "UP (port $port)"
            return 0
        fi
        printf "."
        sleep 1
    done
    echo " TIMEOUT"
    echo "ERROR: $name did not become healthy on port $port" >&2
    tail -30 "$LOG_DIR/${name}.log" 2>/dev/null || true
    return 1
}

# Starts a process. Args: name port profiles agent_segment [extra java args...]
start_process() {
    local name="$1" port="$2" profiles="$3" agent_segment="$4"
    shift 4
    local log="$LOG_DIR/${name}.log"
    echo "==> Starting $name (profiles=$profiles, port=$port)"
    java -jar "$JAR_FILE" \
        --spring.profiles.active="$profiles" \
        --server.port="$port" \
        --openjiuwen.service.versatile.url-template="http://localhost:${port}/v1/proj/agents/${agent_segment}/conversations/{conversation_id}" \
        "$@" \
        >"$log" 2>&1 &
    PIDS+=("$!")
    echo "    pid=${PIDS[-1]} log=$log"
}

stop_all() {
    cleanup
}

send_query() {
    local port="$1" conv_id="$2" content="$3"
    curl -s -X POST "http://localhost:${port}/v1/query" \
        -H "Content-Type: application/json" \
        -d "{\"conversation_id\":\"${conv_id}\",\"stream\":false,\"messages\":[{\"role\":\"user\",\"content\":\"${content}\"}]}"
}

assert_contains() {
    local label="$1" body="$2" pattern="$3"
    if echo "$body" | grep -q "$pattern"; then
        echo "    PASS: $label contains '$pattern'"
        return 0
    fi
    echo "    FAIL: $label expected to contain '$pattern'" >&2
    echo "    body: $body" >&2
    return 1
}

# ─── Round 1: Scenario 1 (L1→L2→downstream) + Scenario 3 (interrupt) ───

run_round_one() {
    echo
    echo "==================== Round 1: §6.2.1 + §6.2.3 ===================="

    # L1: three-field mode (layer1 profile) — has interrupt config for scenario 3
    start_process layer1 "$L1_PORT" "layer1,dev,mock-versatile" "agent_L1"

    # L2: three-field mode (layer2 profile)
    start_process layer2 "$L2_PORT" "layer2,dev,mock-versatile" "agent_L2"

    # Downstream: legacy mode (no result-extractions → returns final answer)
    start_process downstream "$DOWNSTREAM_PORT" "dev,mock-versatile" "agent_biz" \
        --openjiuwen.service.versatile.result-node-name=AnswerNode \
        --openjiuwen.service.versatile.messages.required=true

    wait_for_health "$L1_PORT" layer1
    wait_for_health "$L2_PORT" layer2
    wait_for_health "$DOWNSTREAM_PORT" downstream

    echo
    echo "--- §6.2.1 两层识别 + 下游业务 (L1→L2→downstream) ---"
    echo "    POST http://localhost:${L1_PORT}/v1/query  messages=[{user, 订酒店}]"
    local resp1
    resp1=$(send_query "$L1_PORT" "c1-scenario1" "订酒店")
    echo "    response: $(echo "$resp1" | head -c 500)"
    assert_contains "scenario1" "$resp1" "酒店预订成功"

    echo
    echo "--- §6.2.3 工作流显式用户交互 (interrupt) ---"
    echo "    POST http://localhost:${L1_PORT}/v1/query  messages=[{user, 中断}]"
    local resp3
    resp3=$(send_query "$L1_PORT" "c5-scenario3" "中断")
    echo "    response: $(echo "$resp3" | head -c 500)"
    assert_contains "scenario3-interrupt" "$resp3" "_interrupt"
    assert_contains "scenario3-resume-token" "$resp3" "tok-123"

    echo
    echo "==> Round 1 complete, stopping processes"
    stop_all
}

# ─── Round 2: Scenario 4 (L2 ambiguous self-heal L1→L2→default-wf) ───

run_round_two() {
    echo
    echo "==================== Round 2: §6.2.4 L2 意图不明自消 ===================="

    # L1: three-field mode (layer1 profile) — routes intent_L1_hotel to L2
    start_process layer1 "$L1_PORT" "layer1,dev,mock-versatile" "agent_L1"

    # L2: three-field mode (layer2 profile) — returns ambiguous intent_id="1"
    # and self-heals via a2a_delegate to agent_card_L2_default
    start_process layer2 "$L2_PORT" "layer2,dev,mock-versatile" "agent_L2"

    # default-wf: terminal node hosting agent_L2_default, returns fallback business output
    start_process default-wf "$DEFAULT_WF_PORT" "dev,mock-versatile" "agent_L2_default" \
        --openjiuwen.service.versatile.result-node-name=AnswerNode \
        --openjiuwen.service.versatile.messages.required=true

    wait_for_health "$L1_PORT" layer1
    wait_for_health "$L2_PORT" layer2
    wait_for_health "$DEFAULT_WF_PORT" default-wf

    echo
    echo "--- §6.2.4 L2 意图不明自消 (L1→L2→default-wf) ---"
    echo "    POST http://localhost:${L1_PORT}/v1/query  messages=[{user, 意图不明}]"
    local resp
    resp=$(send_query "$L1_PORT" "c6-scenario4" "意图不明")
    echo "    response: $(echo "$resp" | head -c 500)"
    # 核心断言：最终响应包含 default-wf 的兜底业务输出，证明 L2 ambiguous 自消链路走通
    assert_contains "scenario4-ambiguous-self-heal" "$resp" "默认工作流兜底"

    echo
    echo "==> Round 2 complete, stopping processes"
    stop_all
}

main() {
    echo "==> L2 §5.5.3 方案 B mock 联调 (three scenarios)"
    check_dependencies
    build_if_needed
    run_round_one
    run_round_two
    echo
    echo "==> All scenarios passed."
    echo "    Logs: $LOG_DIR/{layer1,layer2,downstream,default-wf}.log"
}

main "$@"
