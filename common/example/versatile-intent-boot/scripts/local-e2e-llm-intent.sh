#!/usr/bin/env bash
#
# Local end-to-end runbook for the LLM-driven intent demo.
# Real LLM classification at L1/L2 + real DeepAgent (Agent B) at downstream.
# Scenarios (single conversation_id):
#   A: 订酒店 → 上海（Agent B hotel ask_user：定什么地方 → 订哪天）
#   B: 买机票（L1 据历史识别话题切换 → L2_flight，Agent B flight ask_user：去哪里）
#   C: 继续订酒店（Agent B hotel shadow-task 恢复，ask_user：住几天）
#
# Requires: Java 17, LLM_API_KEY/BASE_URL/MODEL (OpenAI-compatible; e.g. GLM
#   glm-5.2 at https://open.bigmodel.cn/api/coding/paas/v4 per apiconfig.json).
#   DEEPSEEK_* defaults to LLM_* if not set. LLM params are read from
#   $MODULE_DIR/.env (see .env.example) or real env vars — env vars win.
#   API keys come ONLY from env/.env — never committed to code.
# Usage:   ./scripts/local-e2e-llm-intent.sh
#          SKIP_BUILD=1 ./scripts/local-e2e-llm-intent.sh
#          LLM_DEMO_ENV=/path/to/.env ./scripts/local-e2e-llm-intent.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$MODULE_DIR/../../.." && pwd)"
AGENT_B_MODULE="$REPO_ROOT/common/example/agentcore-ext-deepagent-remote-a2a-demo/agent-b-deepagent-runtime"

GATEWAY_PORT=8084
L1_PORT=8081
L2_HOTEL_PORT=8082
L2_FLIGHT_PORT=8086
AGENT_B_HOTEL_PORT=18191
AGENT_B_FLIGHT_PORT=18192
HEALTH_TIMEOUT_SECONDS="${HEALTH_TIMEOUT_SECONDS:-120}"

JAR_FILE="$MODULE_DIR/target/versatile-intent-boot-0.1.0.jar"
AGENT_B_JAR="$AGENT_B_MODULE/target/deepagent-remote-a2a-agent-b-0.1.0.jar"

PIDS=()
LOG_DIR="$MODULE_DIR/target"
mkdir -p "$LOG_DIR"

require_env() {
    local name="$1"
    if [ -z "${!name:-}" ]; then
        echo "ERROR: env $name is required (set before running)" >&2
        echo "       either export it, or put it in $MODULE_DIR/.env (see .env.example)" >&2
        exit 1
    fi
}

# Load LLM params (and any port/SKIP_BUILD overrides) from a .env file. Real
# environment variables win — .env only fills in vars that are not already set.
# Search order: $LLM_DEMO_ENV, then $MODULE_DIR/.env. Mirrors cli-llm-intent.py.
load_dotenv() {
    local env_file="${LLM_DEMO_ENV:-$MODULE_DIR/.env}"
    [ -f "$env_file" ] || return 0
    echo "==> loading .env: $env_file"
    local key val
    while IFS='=' read -r key val || [ -n "${key:-}" ]; do
        case "$key" in ''|\#*) continue ;; esac          # skip blank / comment lines
        key="${key#"${key%%[![:space:]]*}"}"; key="${key%"${key##*[![:space:]]}"}"
        val="${val#"${val%%[![:space:]]*}"}"; val="${val%"${val##*[![:space:]]}"}"
        case "$val" in                                   # strip one surrounding quote pair
            \"*\") val="${val#\"}"; val="${val%\"}" ;;
            \'*\') val="${val#\'}"; val="${val%\'}" ;;
        esac
        [ -n "$key" ] && [ -z "${!key:-}" ] && export "$key=$val" || true
    done < "$env_file"
}
load_dotenv
require_env LLM_API_KEY
require_env LLM_BASE_URL
require_env LLM_MODEL

# Agent B (DEEPSEEK_*) defaults to the same LLM config when not set separately,
# so a single apiconfig.json-style config drives both L1/L2 classification and
# the downstream DeepAgent. Override with explicit DEEPSEEK_* if Agent B should
# use a different model/endpoint.
export DEEPSEEK_API_KEY="${DEEPSEEK_API_KEY:-$LLM_API_KEY}"
export DEEPSEEK_BASE_URL="${DEEPSEEK_BASE_URL:-$LLM_BASE_URL}"
export DEEPSEEK_MODEL="${DEEPSEEK_MODEL:-$LLM_MODEL}"
export LLM_API_KEY LLM_BASE_URL LLM_MODEL

HOTEL_PROMPT='你是酒店预订 Agent。收到订酒店请求时，按顺序调用 ask_user 依次询问：① 想定什么地方（目的地）；② 订哪天（入住日期）；③ 住几天。每收到一次用户回答就继续下一个问题，三个问题都问完后返回最终答案，内容包含"酒店预订成功"。不要跳过 ask_user。'
FLIGHT_PROMPT='你是机票预订 Agent。收到买机票请求时，先调用 ask_user 询问去哪里（目的地），恢复后返回最终答案，内容包含"机票预订成功"。'

cleanup() {
    echo; echo "==> Stopping processes: ${PIDS[*]:-<none>}"
    for pid in "${PIDS[@]:-}"; do [ -n "$pid" ] && kill "$pid" 2>/dev/null || true; done
    wait 2>/dev/null || true
}
trap cleanup EXIT

build_if_needed() {
    if [ "${SKIP_BUILD:-0}" = "1" ]; then return; fi
    [ -f "$JAR_FILE" ] || (cd "$MODULE_DIR" && mvn -q package -DskipTests)
    [ -f "$AGENT_B_JAR" ] || (cd "$AGENT_B_MODULE" && mvn -q package -DskipTests)
}

wait_for_health() {
    local port="$1" name="$2" deadline=$(( $(date +%s) + HEALTH_TIMEOUT_SECONDS ))
    printf "    %-14s " "$name:"
    while [ "$(date +%s)" -lt "$deadline" ]; do
        if curl -sf "http://localhost:${port}/health" >/dev/null 2>&1 \
           || curl -sf "http://localhost:${port}/.well-known/agent-card.json" >/dev/null 2>&1 \
           || curl -sf "http://localhost:${port}/actuator/health" >/dev/null 2>&1; then
            echo "UP (port $port)"; return 0
        fi
        printf "."; sleep 1
    done
    echo " TIMEOUT"; echo "ERROR: $name unhealthy on $port" >&2; tail -30 "$LOG_DIR/${name}.log" 2>/dev/null || true; return 1
}

start_versatile() {  # name port profiles agent_segment [extra...]
    local name="$1" port="$2" profiles="$3" seg="$4"; shift 4
    local log="$LOG_DIR/${name}.log"
    echo "==> Starting $name (profiles=$profiles, port=$port)"
    java -jar "$JAR_FILE" \
        --spring.profiles.active="$profiles" \
        --server.port="$port" \
        --openjiuwen.service.versatile.url-template="http://localhost:${port}/v1/proj/agents/${seg}/conversations/{conversation_id}" \
        "$@" >"$log" 2>&1 &
    PIDS+=("$!"); echo "    pid=$! log=$log"
}

start_gateway() {
    local name=gateway port="$GATEWAY_PORT"
    local log="$LOG_DIR/${name}.log"
    echo "==> Starting $name (mock-a2a-gateway, port=$port)"
    java -jar "$JAR_FILE" \
        --spring.profiles.active="mock-a2a-gateway" \
        --server.port="$port" \
        --openjiuwen.service.versatile.url-template="http://localhost:${port}/v1/proj/agents/agent_mock_gateway/conversations/{conversation_id}" \
        --openjiuwen.example.mock-a2a-gateway.routing.agent_card_L2_flight="http://localhost:${L2_FLIGHT_PORT}" \
        --openjiuwen.example.mock-a2a-gateway.routing.agent_card_L2_flight_a="http://localhost:${L2_FLIGHT_PORT}" \
        --openjiuwen.example.mock-a2a-gateway.routing.agent_card_L2_flight_b="http://localhost:${L2_FLIGHT_PORT}" \
        --openjiuwen.example.mock-a2a-gateway.routing.agent_card_biz_hotel_domestic="http://localhost:${AGENT_B_HOTEL_PORT}" \
        --openjiuwen.example.mock-a2a-gateway.routing.agent_card_biz_hotel_international="http://localhost:${AGENT_B_HOTEL_PORT}" \
        --openjiuwen.example.mock-a2a-gateway.routing.agent_card_biz_flight_domestic="http://localhost:${AGENT_B_FLIGHT_PORT}" \
        --openjiuwen.example.mock-a2a-gateway.passthrough-cards="agent_card_biz_hotel_domestic,agent_card_biz_hotel_international,agent_card_biz_flight_domestic" \
        >"$log" 2>&1 &
    PIDS+=("$!"); echo "    pid=$! log=$log"
}

start_agent_b() {  # name port workspace prompt
    local name="$1" port="$2" ws="$3" prompt="$4"
    local log="$LOG_DIR/${name}.log"
    echo "==> Starting $name (Agent B, port=$port)"
    java -jar "$AGENT_B_JAR" \
        --server.port="$port" \
        --openjiuwen.demo.deep-agent.llm.workspace-path="$ws" \
        --openjiuwen.demo.deep-agent.llm.system-prompt="$prompt" \
        >"$log" 2>&1 &
    PIDS+=("$!"); echo "    pid=$! log=$log"
}

send_q() {  # port conv content
    curl -s -X POST "http://localhost:${1}/v1/query" \
        -H "Content-Type: application/json" \
        -H "X-Biz-Tag: llm-demo" \
        -d "{\"conversation_id\":\"${2}\",\"stream\":false,\"user_id\":\"u-42\",\"messages\":[{\"role\":\"user\",\"content\":\"${3}\"}]}"
}

assert_log() { grep -q "$2" "$LOG_DIR/$1.log" && echo "    PASS: $1 log contains $2" \
    || { echo "    FAIL: $1 log missing $2" >&2; return 1; }; }

main() {
    echo "==> LLM 意图驱动演示 (真实 LLM + DeepAgent downstream)"
    build_if_needed

    start_gateway
    start_versatile layer1 "$L1_PORT" "layer1,dev,mock-versatile,a2a-gateway-test,llm-intent" "agent_L1" \
        --openjiuwen.service.versatile.route-cache.enabled=false
    start_versatile layer2-hotel "$L2_HOTEL_PORT" "layer2,dev,mock-versatile,a2a-gateway-test,llm-intent" "agent_L2" \
        --openjiuwen.service.versatile.default-workflow.agent-card= \
        --openjiuwen.example.intent-llm.domain=hotel
    start_versatile layer2-flight "$L2_FLIGHT_PORT" "layer2-flight,dev,mock-versatile,a2a-gateway-test,llm-intent" "agent_L2_flight" \
        --openjiuwen.service.versatile.default-workflow.agent-card= \
        --openjiuwen.example.intent-llm.domain=flight
    start_agent_b agent-b-hotel "$AGENT_B_HOTEL_PORT" "target/agent-b-hotel" "$HOTEL_PROMPT"
    start_agent_b agent-b-flight "$AGENT_B_FLIGHT_PORT" "target/agent-b-flight" "$FLIGHT_PROMPT"

    wait_for_health "$GATEWAY_PORT" gateway
    wait_for_health "$L1_PORT" layer1
    wait_for_health "$L2_HOTEL_PORT" layer2-hotel
    wait_for_health "$L2_FLIGHT_PORT" layer2-flight
    wait_for_health "$AGENT_B_HOTEL_PORT" agent-b-hotel
    wait_for_health "$AGENT_B_FLIGHT_PORT" agent-b-flight

    local cid="c-llm-demo"
    echo; echo "==== 场景 A: 订酒店多轮 ask-user ===="
    echo "    response: $(send_q "$L1_PORT" "$cid" "订酒店" | head -c 600)"
    echo "    response: $(send_q "$L1_PORT" "$cid" "上海" | head -c 600)"

    echo; echo "==== 场景 B: 跳转买机票（切换意图）===="
    echo "    response: $(send_q "$L1_PORT" "$cid" "买机票" | head -c 600)"
    assert_log layer1 "A2AGateway call agent=agent_card_L2_flight" || true

    echo; echo "==== 场景 C: 回跳继续订酒店 ===="
    echo "    response: $(send_q "$L1_PORT" "$cid" "继续订酒店" | head -c 600)"
    assert_log layer1 "A2AGateway call agent=agent_card_L2_hotel" || true

    echo; echo "==> Demo run complete. Inspect logs: $LOG_DIR/{layer1,layer2-hotel,layer2-flight,agent-b-hotel,agent-b-flight,gateway}.log"
}
main "$@"