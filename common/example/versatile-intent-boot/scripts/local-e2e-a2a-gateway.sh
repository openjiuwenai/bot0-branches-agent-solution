#!/usr/bin/env bash
#
# Local end-to-end runbook for the A2A Gateway caller mode (full chain).
# Verifies L2 §6.2.1 场景一 (两层识别 + 下游业务), L2 §6.2.4 意图不明自消,
# and the multi-turn route cache (route cached on turn 1, reused on turn 2)
# under a2a-gateway.enabled=true.
#
# The script runs two rounds with a process restart in between:
#   - Round 1: L2 with default-workflow → §6.2.1 + §6.2.4 self-heal
#     + multi-turn route cache (conv_id=c4-multi-turn, two client turns)
#   - Round 2: L1/L2 restarted with direct-chain enabled; downstream is a plain
#     versatile mock server. gateway tunnels the terminal biz card directly to
#     the mock (/v1/proj/agents/agent_biz/conversations/{cid}, serve body
#     rewritten to {inputs:...}); client receives raw versatile SSE data: lines.
#
# Architecture:
#   - gateway process (port 8084, profile mock-a2a-gateway): forwarding proxy
#     that routes POST /a2a/{agentId} to the target runtime's /v1/query based
#     on a hardcoded agentCard → URL routing table. Wraps the target's
#     QueryResponse.result in an answer envelope so the gateway caller can
#     preserve the terminal business text and agent_id.
#
#   - layer1 process (port 8081, profiles layer1,dev,mock-versatile,
#     a2a-gateway-test): serves /v1/query, runs mock Versatile SSE flow that
#     resolves "订酒店" → agent_card_L2_hotel, then forwards via
#     A2AGatewayRemoteAgentCaller to the mock gateway.
#
#   - layer2 process (port 8082, profiles layer2,dev,mock-versatile,
#     a2a-gateway-test): serves /v1/query, runs mock Versatile SSE flow that
#     resolves "订酒店" → agent_card_biz_hotel_domestic, then forwards via
#     A2AGatewayRemoteAgentCaller to the mock gateway. Configures
#     ambiguous-intent-id="1" + default-workflow.agent-card=agent_card_L2_default
#     so an L2 ambiguous result self-heals via a2a_delegate to the default-wf
#     process.
#
#   - downstream process (port 8083, profiles dev,mock-versatile + overrides):
#     terminal node — no result-extractions, no intent-agent-mapping. Returns
#     the final business output "酒店预订成功：上海今晚五星".
#
#   - default-wf process (port 8085, profiles dev,mock-versatile + overrides):
#     terminal node hosting agent_L2_default. Returns the fallback business
#     output "默认工作流兜底：转人工客服" when L2 self-heals an ambiguous intent.
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
# Chain (L2 §6.2.4 意图不明自消):
#   1. Client → L1 /v1/query "意图不明"
#   2. L1 Versatile → three-field {agent_id:"agent_card_L2_hotel"}
#   3. L1 A2AGateway caller → gateway /a2a/agent_card_L2_hotel
#   4. Gateway forwards → L2 /v1/query "意图不明"
#   5. L2 Versatile → three-field {response_content:"无法确定国内/国际酒店",
#      intent_id:"1"} (ambiguous)
#   6. L2 Adapter detects intent_id=="1", default-workflow.agent-card configured
#   7. L2 self-heals: a2a_delegate → gateway /a2a/agent_card_L2_default
#   8. Gateway forwards → default-wf /v1/query "意图不明"
#   9. default-wf Versatile → terminal {text:"默认工作流兜底：转人工客服"}
#   10. default-wf → gateway → L2 → gateway → L1 → client
#   Final response contains "默认工作流兜底"
#
# Chain (multi-turn route cache, conv_id=c4-multi-turn):
#   Turn 1 (cache miss):
#     1. Client → L1 /v1/query "订酒店" (conv_id=c4-multi-turn)
#     2. L1 Versatile invoked (counter=1) → three-field {agent_id:
#        "agent_card_L2_hotel"}
#     3. CachedVersatileAgentHandler captures {agentName:
#        "agent_card_L2_hotel"} into RouteCache keyed by conv_id
#     4. L1 → gateway → L2 → gateway → downstream → "酒店预订成功"
#   Turn 2 (cache hit, same conv_id):
#     1. Client → L1 /v1/query "再订一晚" (conv_id=c4-multi-turn)
#     2. CachedVersatileAgentHandler cache hit → synthesizes a2a_delegate
#        {agentName:"agent_card_L2_hotel"} WITHOUT invoking L1 Versatile
#        (counter stays at 1)
#     3. L1 → gateway → L2 → gateway → downstream → "酒店预订成功"
#   Assertions:
#     - Both turns' responses contain "酒店预订成功"
#     - L1 versatile log count for c4-multi-turn == 1 (cache hit on turn 2)
#     - L2 versatile log count for c4-multi-turn == 2 (one per turn)
#     - Gateway inbound agent_card_L2_hotel delta == 2 (one per turn)
#     - Gateway inbound agent_card_biz_hotel_domestic delta == 2 (one per turn)
#
# Header propagation is verified via gateway log assertions (the gateway logs
# all inbound token/userId/versionNode/X-B3-*/X-Biz-Tag headers at INFO).
#
# Usage:
#   ./scripts/local-e2e-a2a-gateway.sh            # build if needed, run
#   SKIP_BUILD=1 ./scripts/local-e2e-a2a-gateway.sh
#
# Prerequisites: Java 17 on PATH, and the prerequisite artifacts
# (agent-service-app / agent-service-adapters-versatile) installed in the
# local Maven repository — see README.md「前置依赖 → 首次构建」.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

GATEWAY_PORT="${GATEWAY_PORT:-8084}"
L1_PORT="${L1_PORT:-8081}"
L2_PORT="${L2_PORT:-8082}"
DOWNSTREAM_PORT="${DOWNSTREAM_PORT:-8083}"
DEFAULT_WF_PORT="${DEFAULT_WF_PORT:-8085}"
L1_QUERY_BASE_URL="${L1_QUERY_BASE_URL:?L1_QUERY_BASE_URL is required}"
HEALTH_TIMEOUT_SECONDS="${HEALTH_TIMEOUT_SECONDS:-90}"
JAR_FILE="$MODULE_DIR/target/versatile-intent-boot-0.1.0.jar"
LOCAL_SCHEME="${LOCAL_SCHEME:-http}"
LOCAL_HOST="${LOCAL_HOST:-localhost}"

local_base_url() {
    printf '%s://%s:%s' "$LOCAL_SCHEME" "$LOCAL_HOST" "$1"
}

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
declare -A PID_BY_NAME=()
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

stop_all() {
    cleanup
}

# Stops a single process by its logical name. Used between e2e rounds to
# restart a process with different config without tearing down the whole
# ensemble. Truncates the process log so post-restart assertions do not
# match stale entries from the previous round.
stop_process_by_name() {
    local name="$1"
    local pid="${PID_BY_NAME[$name]:-}"
    if [ -z "$pid" ] || ! kill -0 "$pid" 2>/dev/null; then
        echo "==> WARNING: $name process not found, skipping stop"
        : > "$LOG_DIR/${name}.log"
        return 0
    fi
    echo "==> Stopping $name (pid=$pid)"
    kill "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
    PIDS=("${PIDS[@]/$pid}")
    unset 'PID_BY_NAME[$name]'
    : > "$LOG_DIR/${name}.log"
}

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
        if curl -sf "$(local_base_url "$port")/health" >/dev/null 2>&1; then
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
    local base_url
    base_url="$(local_base_url "$port")"
    echo "==> Starting $name (profiles=$profiles, port=$port)"
    java -jar "$JAR_FILE" \
        --spring.profiles.active="$profiles" \
        --server.port="$port" \
        --openjiuwen.service.versatile.url-template="${base_url}/v1/proj/agents/${agent_segment}/conversations/{conversation_id}" \
        "$@" \
        >"$log" 2>&1 &
    local pid=$!
    PIDS+=("$pid")
    PID_BY_NAME["$name"]="$pid"
    echo "    pid=$pid log=$log"
}

# Sends a /v1/query with the upstream B3 / biz-tag headers set, so the
# caller's resolveUpstreamHeader() has something to propagate.
send_query_with_trace() {
    local port="$1" conv_id="$2" content="$3"
    curl -s -X POST "$(local_base_url "$port")/v1/query" \
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

assert_eq() {
    local expected="$1" actual="$2" label="$3"
    if [ "$expected" = "$actual" ]; then
        echo "    PASS: $label == $expected"
        return 0
    fi
    echo "    FAIL: $label expected=$expected actual=$actual" >&2
    return 1
}

assert_not_contains() {
    local label="$1" body="$2" pattern="$3"
    if echo "$body" | grep -q "$pattern"; then
        echo "    FAIL: $label expected NOT to contain '$pattern'" >&2
        echo "    body: $body" >&2
        return 1
    fi
    echo "    PASS: $label does not contain '$pattern'"
    return 0
}

main() {
    echo "==> A2A Gateway 全链路联调 (L2 §6.2.1 + §6.2.4 自消 + 多轮路由缓存)"
    check_dependencies
    build_if_needed

    echo
    echo "======================================== Round 1: §6.2.1 + §6.2.4 自消 + 多轮路由缓存 ========================================"

    echo
    echo "==================== 启动 gateway + L1 + L2 + downstream + default-wf ===================="

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

    # default-wf: L2 自消默认工作流终端节点，hosting agent_L2_default，返回兜底业务输出
    start_process default-wf "$DEFAULT_WF_PORT" "dev,mock-versatile" "agent_L2_default" \
        --openjiuwen.service.versatile.result-node-name=AnswerNode \
        --openjiuwen.service.versatile.messages.required=true

    wait_for_health "$GATEWAY_PORT" gateway
    wait_for_health "$L1_PORT" layer1
    wait_for_health "$L2_PORT" layer2
    wait_for_health "$DOWNSTREAM_PORT" downstream
    wait_for_health "$DEFAULT_WF_PORT" default-wf

    echo
    echo "==================== Scenario: L2 §6.2.1 两层识别 + 下游业务 ===================="
    echo "    POST $(local_base_url "$L1_PORT")/v1/query  messages=[{user, 订酒店}]"
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
    assert_log_contains "hop1-gateway-forward" "$LOG_DIR/gateway.log" "Mock A2A Gateway forwarding agentId=agent_card_L2_hotel -> $(local_base_url "$L2_PORT")/v1/query"
    # hop 2: L2 → gateway → downstream (agentId=agent_card_biz_hotel_domestic)
    assert_log_contains "hop2-gateway-inbound" "$LOG_DIR/gateway.log" "Mock A2A Gateway inbound agentId=agent_card_biz_hotel_domestic"
    assert_log_contains "hop2-gateway-forward" "$LOG_DIR/gateway.log" "Mock A2A Gateway forwarding agentId=agent_card_biz_hotel_domestic -> $(local_base_url "$DOWNSTREAM_PORT")/v1/query"

    echo
    echo "==================== 验证 gateway 日志：header 透传 ===================="
    # 验证 L1→gateway hop 的 header 透传 (token/userId/versionNode 来自 A2AGatewayProperties)
    # MockA2AGatewayController 出于安全考虑只记录 tokenPresent 布尔值，不打印 raw token
    assert_log_contains "header-token" "$LOG_DIR/gateway.log" "tokenPresent=true"
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
    echo "==================== Scenario: L2 §6.2.4 L2 意图不明自消 (L1→gateway→L2→gateway→default-wf) ===================="
    echo "    POST $(local_base_url "$L1_PORT")/v1/query  messages=[{user, 意图不明}]"
    local resp2
    resp2=$(send_query_with_trace "$L1_PORT" "c2-gateway-ambiguous" "意图不明")
    echo "    response: $(echo "$resp2" | head -c 800)"

    # 核心断言：最终响应包含 default-wf 的兜底业务输出，证明 L2 ambiguous 自消链路走通
    assert_contains "ambiguous-final-fallback-output" "$resp2" "默认工作流兜底"

    echo
    echo "==================== 验证 gateway 日志：L2 自消 hop ===================="
    # hop 3: L2 → gateway → default-wf (agentId=agent_card_L2_default) — L2 ambiguous 自消
    assert_log_contains "hop3-gateway-inbound" "$LOG_DIR/gateway.log" "Mock A2A Gateway inbound agentId=agent_card_L2_default"
    assert_log_contains "hop3-gateway-forward" "$LOG_DIR/gateway.log" "Mock A2A Gateway forwarding agentId=agent_card_L2_default -> $(local_base_url "$DEFAULT_WF_PORT")/v1/query"

    echo
    echo "==================== 验证 L2 日志：ambiguous 自消 a2a_delegate ===================="
    assert_log_contains "l2-ambiguous-delegate" "$LOG_DIR/layer2.log" "A2AGateway call agent=agent_card_L2_default"

    echo
    echo "==================== Scenario: 多轮对话路由缓存复用 (conv_id=c4-multi-turn, two turns) ===================="
    echo "    Turn 1: POST $(local_base_url "$L1_PORT")/v1/query  conv_id=c4-multi-turn  messages=[{user, 订酒店}]"
    echo "    Turn 2: POST $(local_base_url "$L1_PORT")/v1/query  conv_id=c4-multi-turn  messages=[{user, 再订一晚}]"
    echo "    Expected: both turns return 酒店预订成功; L1 versatile invoked ONCE (cache hit on turn 2)"

    # Capture gateway inbound counts BEFORE the multi-turn scenario. The
    # gateway log accumulates across c1/c2 scenarios, so we assert deltas
    # rather than absolute counts.
    local gw_l2_hotel_before gw_biz_before
    gw_l2_hotel_before=$(grep -c "inbound agentId=agent_card_L2_hotel" "$LOG_DIR/gateway.log" 2>/dev/null || echo 0)
    gw_biz_before=$(grep -c "inbound agentId=agent_card_biz_hotel_domestic" "$LOG_DIR/gateway.log" 2>/dev/null || echo 0)

    local resp_t1 resp_t2
    resp_t1=$(send_query_with_trace "$L1_PORT" "c4-multi-turn" "订酒店")
    echo "    turn1 response: $(echo "$resp_t1" | head -c 800)"
    assert_contains "multi-turn-turn1-business-output" "$resp_t1" "酒店预订成功"

    resp_t2=$(send_query_with_trace "$L1_PORT" "c4-multi-turn" "再订一晚")
    echo "    turn2 response: $(echo "$resp_t2" | head -c 800)"
    assert_contains "multi-turn-turn2-business-output" "$resp_t2" "酒店预订成功"

    echo
    echo "==================== 验证路由缓存命中：L1 versatile 仅调用一次 ===================="
    # conv_id c4-multi-turn is unique to this scenario, so a direct grep is clean.
    local l1_versatile_count l2_versatile_count
    l1_versatile_count=$(grep -c "Mock Versatile agentId=agent_L1 conversationId=c4-multi-turn" "$LOG_DIR/layer1.log" 2>/dev/null || echo 0)
    l2_versatile_count=$(grep -c "Mock Versatile agentId=agent_L2 conversationId=c4-multi-turn" "$LOG_DIR/layer2.log" 2>/dev/null || echo 0)
    if [ "$l1_versatile_count" -eq 1 ]; then
        echo "    PASS: L1 versatile invoked exactly once for c4-multi-turn (route cache hit on turn 2)"
    else
        echo "    FAIL: expected L1 versatile count=1 (cache hit on turn 2), got $l1_versatile_count" >&2
        return 1
    fi
    if [ "$l2_versatile_count" -eq 2 ]; then
        echo "    PASS: L2 versatile invoked twice for c4-multi-turn (chain still flows through L2 on both turns)"
    else
        echo "    FAIL: expected L2 versatile count=2 (one per turn), got $l2_versatile_count" >&2
        return 1
    fi

    echo
    echo "==================== 验证 gateway 日志：两轮各触发一次 L1→L2 hop + L2→downstream hop ===================="
    local gw_l2_hotel_after gw_biz_after gw_l2_hotel_delta gw_biz_delta
    gw_l2_hotel_after=$(grep -c "inbound agentId=agent_card_L2_hotel" "$LOG_DIR/gateway.log" 2>/dev/null || echo 0)
    gw_biz_after=$(grep -c "inbound agentId=agent_card_biz_hotel_domestic" "$LOG_DIR/gateway.log" 2>/dev/null || echo 0)
    gw_l2_hotel_delta=$((gw_l2_hotel_after - gw_l2_hotel_before))
    gw_biz_delta=$((gw_biz_after - gw_biz_before))
    if [ "$gw_l2_hotel_delta" -eq 2 ] && [ "$gw_biz_delta" -eq 2 ]; then
        echo "    PASS: gateway received 2 L1→L2 hops and 2 L2→downstream hops for c4-multi-turn (one pair per turn)"
    else
        echo "    FAIL: expected gateway deltas L2_hotel=2 biz=2, got L2_hotel=$gw_l2_hotel_delta biz=$gw_biz_delta" >&2
        return 1
    fi

    echo
    echo "======================================== Round 2: versatile direct-chain SSE 透传 ========================================"

    echo
    echo "==================== 重启 L1 + L2 + downstream（直链模式）===================="
    # 直链场景：L1/L2 以 direct-chain.enabled=true 启动（DirectChainVersatileAgentHandler
    # 截胡 a2a_delegate，改走 gateway 隧道 X-Direct-Chain:true）。downstream 仅作 versatile
    # mock server 宿主，不开 direct-chain——末端业务卡由 gateway 隧道直接转发到 downstream 的
    # /v1/proj/agents/agent_biz/conversations/{cid}（gateway 内硬编码 versatileAgentId=agent_biz，
    # 并把 serve body 翻译成 {inputs:...}），业务原始 SSE 不经任何业务终端 handler。
    # gateway 进程不变（已带 /a2a/{agentId} 隧道端点；a2a-gateway-test profile 已配
    # base-url=http://localhost:8084，直链 handler 经 A2AGatewayCardResolver 复用）。
    #
    # DirectChainAutoConfiguration 在 imports 文件中先于 RouteCacheAutoConfiguration，
    # 故 direct-chain.enabled=true 时 DirectChainVersatileAgentHandler 抢得 AgentHandler
    # 槽位（@ConditionalOnMissingBean），route-cache 自动让位。L1 保留其默认
    # route-cache.enabled=true（application-layer1.yml）即可——直链仍生效。
    stop_process_by_name layer1
    stop_process_by_name layer2
    stop_process_by_name downstream

    # L1: direct-chain 默认全直链（a2a-forward-agent-cards 留空），agent_card_L2_hotel 走直链
    start_process layer1 "$L1_PORT" "layer1,dev,mock-versatile,a2a-gateway-test" "agent_L1" \
        --openjiuwen.example.direct-chain.enabled=true

    # L2: direct-chain 默认全直链，agent_card_biz_hotel_domestic 走直链
    start_process layer2 "$L2_PORT" "layer2,dev,mock-versatile,a2a-gateway-test" "agent_L2" \
        --openjiuwen.example.direct-chain.enabled=true

    # downstream: 仅作 versatile mock server 宿主（MockVersatileController）。
    # 直链末端业务卡由 gateway 隧道直接转发到 downstream 的
    # /v1/proj/agents/agent_biz/conversations/{cid}（gateway 内硬编码 versatileAgentId=agent_biz，
    # 并把 serve body 翻译成 {inputs:...}），业务原始 SSE 不经任何业务终端 handler。
    # 故 downstream 不开 direct-chain / raw-passthrough，不注册 RawVersatilePassthroughHandler。
    start_process downstream "$DOWNSTREAM_PORT" "dev,mock-versatile" "agent_biz"

    wait_for_health "$L1_PORT" layer1
    wait_for_health "$L2_PORT" layer2
    wait_for_health "$DOWNSTREAM_PORT" downstream

    echo
    echo "==================== Scenario: versatile direct-chain client stream:true ==================="
    echo "    POST $(local_base_url "$L1_PORT")/v1/query  stream=true  messages=[{user, 订酒店}]"
    echo "    Expected: client 收到业务原始 versatile SSE 事件 (custom_rsp_data + 酒店预订成功)"
    echo "              且无 a2a JSON-RPC 折叠痕迹 (无 TASK_STATE_COMPLETED)"
    local dc_body_file dc_status
    dc_body_file="$(mktemp)"
    dc_status=$(curl -s -o "$dc_body_file" -w "%{http_code}" \
        -X POST "${L1_QUERY_BASE_URL}/v1/query" \
        -H "Content-Type: application/json" \
        -d '{"conversation_id":"c5-direct-chain","stream":true,"user_id":"u-42","messages":[{"role":"user","content":"订酒店"}]}')
    local dc_body
    dc_body="$(cat "$dc_body_file")"
    echo "    http_status: $dc_status"
    echo "    body: $(echo "$dc_body" | head -c 1200)"

    # HTTP 200
    assert_eq "200" "$dc_status" "direct-chain stream HTTP"

    # 响应体包含业务原始 versatile SSE 事件（经 Map 再序列化，字段名不变）
    assert_contains "direct-chain-raw-event" "$dc_body" "custom_rsp_data"
    assert_contains "direct-chain-business-output" "$dc_body" "酒店预订成功"

    # 响应体不含 a2a JSON-RPC 折叠痕迹（直链隧道透传原始 SSE，不产 task 状态事件）
    assert_not_contains "direct-chain-no-a2a-fold" "$dc_body" "TASK_STATE_COMPLETED"

    echo
    echo "==================== 验证 gateway 日志：两跳直链隧道 ===================="
    # hop 1: L1 → gateway 哑隧道 → L2 /v1/query (agentId=agent_card_L2_hotel, X-Direct-Chain=true)
    assert_log_contains "dc-hop1-gateway-tunnel" "$LOG_DIR/gateway.log" "Mock A2A Gateway TUNNEL agentId=agent_card_L2_hotel -> $(local_base_url "$L2_PORT")/v1/query"
    # hop 2: L2 → gateway 隧道 → downstream versatile mock（末端业务卡：gateway 翻译 body
    #        并直连 /v1/proj/agents/agent_biz/conversations/{cid}，不经业务终端 handler）
    assert_log_contains "dc-hop2-gateway-tunnel" "$LOG_DIR/gateway.log" "Mock A2A Gateway TUNNEL agentId=agent_card_biz_hotel_domestic -> $(local_base_url "$DOWNSTREAM_PORT")/v1/proj/agents/agent_biz/conversations/c5-direct-chain"

    echo
    echo "==> All scenarios passed."
    echo "    Logs: $LOG_DIR/{gateway,layer1,layer2,downstream,default-wf}.log"
}

main "$@"
