#!/usr/bin/env bash
#
# Local end-to-end runbook for the A2A Gateway caller mode (full chain).
# Verifies L2 §6.2.1 场景一 (两层识别 + 下游业务) under
# a2a-gateway.enabled=true: L1 → gateway → L2 → gateway → downstream.
#
# Architecture:
#   - gateway process (port 8084, profile mock-a2a-gateway): forwarding proxy
#     that routes POST /a2a/{agentId} to the target runtime's /v1/query based
#     on a hardcoded agentCard → URL routing table. Wraps the target's
#     QueryResponse.result in an answer envelope so the caller's
#     RemoteAgentAnswerExtractor can capture business text and agent_id.
#
#   - layer1 process (port 8081, profiles layer1,dev,mock-versatile,
#     a2a-gateway-test): serves /v1/query, runs mock Versatile SSE flow that
#     resolves "订酒店" → agent_card_L2_hotel, then forwards via
#     A2AGatewayRemoteAgentCaller to the mock gateway.
#
#   - layer2 process (port 8082, profiles layer2,dev,mock-versatile,
#     a2a-gateway-test): serves /v1/query, runs mock Versatile SSE flow that
#     resolves "订酒店" → agent_card_biz_hotel_domestic, then forwards via
#     A2AGatewayRemoteAgentCaller to the mock gateway.
#
#   - downstream process (port 8083, profiles dev,mock-versatile + overrides):
#     terminal node — no result-extractions, no intent-agent-mapping. Returns
#     the final business output "酒店预订成功：上海今晚五星".
#
# Chain (L2 §6.2.1):
#   1. Client → L1 /v1/query "订酒店"
#   2. L1 Versatile → three-field {response_content:"L1酒店意图",
#      agent_id:"agent_card_L2_hotel"}
#   3. L1 A2AGateway caller → gateway /a2a/agent_card_L2_hotel
#   4. Gateway forwards → L2 /v1/query "订酒店"
#   5. L2 Versatile → three-field {response_content:"L2国内酒店",
#      agent_id:"agent_card_biz_hotel_domestic"}
#   6. L2 A2AGateway caller → gateway /a2a/agent_card_biz_hotel_domestic
#   7. Gateway forwards → downstream /v1/query "订酒店"
#   8. Downstream Versatile → terminal {text:"酒店预订成功：上海今晚五星"}
#   9. Downstream → gateway → L2 → gateway → L1 → client
#   Final response contains "酒店预订成功"
#
# Header propagation is verified via gateway log assertions (the gateway logs
# all inbound token/userId/versionNode/X-B3-*/X-Biz-Tag headers at INFO).
#
# Usage:
#   ./scripts/local-e2e-a2a-gateway.sh            # build if needed, run
#   SKIP_BUILD=1 ./scripts/local-e2e-a2a-gateway.sh
#
# Prerequisites: Java 17 on PATH.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

GATEWAY_PORT="${GATEWAY_PORT:-8084}"
L1_PORT="${L1_PORT:-8081}"
L2_PORT="${L2_PORT:-8082}"
DOWNSTREAM_PORT="${DOWNSTREAM_PORT:-8083}"
HEALTH_TIMEOUT_SECONDS="${HEALTH_TIMEOUT_SECONDS:-90}"
JAR_FILE="$MODULE_DIR/target/versatile-intent-boot-0.1.0.jar"

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

# Args: name port profiles agent_segment [extra java args...]
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

# Sends a /v1/query with the upstream B3 / biz-tag headers set, so the
# caller's resolveUpstreamHeader() has something to propagate.
send_query_with_trace() {
    local port="$1" conv_id="$2" content="$3"
    curl -s -X POST "http://localhost:${port}/v1/query" \
        -H "Content-Type: application/json" \
        -H "X-B3-TraceId: trace-abc" \
        -H "X-B3-SpanId: span-xyz" \
        -H "X-B3-Sampled: 1" \
        -H "X-Biz-Tag: hotel-flow" \
        -d "{\"conversation_id\":\"${conv_id}\",\"stream\":false,\"user_id\":\"u-42\",\"messages\":[{\"role\":\"user\",\"content\":\"${content}\"}]}"
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

assert_log_contains() {
    local label="$1" logfile="$2" pattern="$3"
    if grep -q "$pattern" "$logfile" 2>/dev/null; then
        echo "    PASS: $label log contains '$pattern'"
        return 0
    fi
    echo "    FAIL: $label log expected to contain '$pattern'" >&2
    echo "    logfile: $logfile" >&2
    return 1
}

main() {
    echo "==> A2A Gateway 全链路联调 (L2 §6.2.1: L1→gateway→L2→gateway→downstream)"
    build_if_needed

    echo
    echo "==================== 启动 gateway + L1 + L2 + downstream ===================="

    # mock gateway: 转发代理，根据 agentId 路由到目标 runtime
    start_process gateway "$GATEWAY_PORT" "mock-a2a-gateway" "agent_mock_gateway"

    # L1: layer1 提供路由配置；mock-versatile 提供 mock SSE；a2a-gateway-test 覆盖 enabled=true 并指向 mock gateway
    start_process layer1 "$L1_PORT" "layer1,dev,mock-versatile,a2a-gateway-test" "agent_L1"

    # L2: layer2 提供路由配置；mock-versatile 提供 mock SSE；a2a-gateway-test 覆盖 enabled=true 并指向 mock gateway
    start_process layer2 "$L2_PORT" "layer2,dev,mock-versatile,a2a-gateway-test" "agent_L2"

    # downstream: 终端节点，无 result-extractions / intent-agent-mapping，直接返回业务输出
    start_process downstream "$DOWNSTREAM_PORT" "dev,mock-versatile" "agent_biz" \
        --openjiuwen.service.versatile.result-node-name=AnswerNode \
        --openjiuwen.service.versatile.messages.required=true

    wait_for_health "$GATEWAY_PORT" gateway
    wait_for_health "$L1_PORT" layer1
    wait_for_health "$L2_PORT" layer2
    wait_for_health "$DOWNSTREAM_PORT" downstream

    echo
    echo "==================== Scenario: L2 §6.2.1 两层识别 + 下游业务 ===================="
    echo "    POST http://localhost:${L1_PORT}/v1/query  messages=[{user, 订酒店}]"
    echo "    Upstream headers: X-B3-TraceId=trace-abc X-B3-SpanId=span-xyz X-B3-Sampled=1 X-Biz-Tag=hotel-flow"
    local resp
    resp=$(send_query_with_trace "$L1_PORT" "c1-gateway-chain" "订酒店")
    echo "    response: $(echo "$resp" | head -c 800)"

    # 核心断言：最终响应包含 downstream 的业务输出，证明全链路 L1→gateway→L2→gateway→downstream 走通
    assert_contains "chain-final-business-output" "$resp" "酒店预订成功"

    echo
    echo "==================== 验证 gateway 日志：两次转发 hop ===================="
    # hop 1: L1 → gateway → L2 (agentId=agent_card_L2_hotel)
    assert_log_contains "hop1-gateway-inbound" "$LOG_DIR/gateway.log" "Mock A2A Gateway inbound agentId=agent_card_L2_hotel"
    assert_log_contains "hop1-gateway-forward" "$LOG_DIR/gateway.log" "Mock A2A Gateway forwarding agentId=agent_card_L2_hotel -> http://localhost:${L2_PORT}/v1/query"
    # hop 2: L2 → gateway → downstream (agentId=agent_card_biz_hotel_domestic)
    assert_log_contains "hop2-gateway-inbound" "$LOG_DIR/gateway.log" "Mock A2A Gateway inbound agentId=agent_card_biz_hotel_domestic"
    assert_log_contains "hop2-gateway-forward" "$LOG_DIR/gateway.log" "Mock A2A Gateway forwarding agentId=agent_card_biz_hotel_domestic -> http://localhost:${DOWNSTREAM_PORT}/v1/query"

    echo
    echo "==================== 验证 gateway 日志：header 透传 ===================="
    # 验证 L1→gateway hop 的 header 透传 (token/userId/versionNode 来自 A2AGatewayProperties)
    assert_log_contains "header-token" "$LOG_DIR/gateway.log" "token=dev-token"
    assert_log_contains "header-user-id" "$LOG_DIR/gateway.log" "userId=u-42"
    assert_log_contains "header-version-node" "$LOG_DIR/gateway.log" "versionNode=v1"
    # 验证 B3 trace 透传
    assert_log_contains "header-trace-id" "$LOG_DIR/gateway.log" "traceId=trace-abc"
    assert_log_contains "header-parent-span-id" "$LOG_DIR/gateway.log" "parentSpanId=span-xyz"
    assert_log_contains "header-sampled" "$LOG_DIR/gateway.log" "sampled=1"
    assert_log_contains "header-biz-tag" "$LOG_DIR/gateway.log" "bizTag=hotel-flow"

    echo
    echo "==================== 验证 L1/L2 日志：A2AGateway caller 激活 ===================="
    assert_log_contains "l1-gateway-caller-active" "$LOG_DIR/layer1.log" "A2AGateway call agent=agent_card_L2_hotel"
    assert_log_contains "l2-gateway-caller-active" "$LOG_DIR/layer2.log" "A2AGateway call agent=agent_card_biz_hotel_domestic"

    echo
    echo "==> All scenarios passed."
    echo "    Logs: $LOG_DIR/{gateway,layer1,layer2,downstream}.log"
}

main "$@"
