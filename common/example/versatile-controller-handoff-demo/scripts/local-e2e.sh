#!/usr/bin/env bash
#
# Local end-to-end runbook for the FEAT-002 controller intent-handoff demo.
# Verifies the L2 spec §7.2 场景旅程验收 journeys against real processes:
#
#   layer1 (port 18091, profiles layer1,mock-controller)
#     agent_card_l1 — runtime + controller-handoff adapter, backed by the
#     in-process mock controller (agent_L1_controller, message format).
#   layer2 (port 18092, profiles layer2,mock-controller)
#     agent_card_l2 — runtime + controller-handoff adapter, backed by
#     agent_L2_controller. "不在范围" is a signal handoff type
#     (handoff.signal.handoff-types): L2 answers its caller with the
#     not-in-scope marker envelope — no outbound call (upstream-signal).
#
# Handoff calls go A2A JSON-RPC (POST /a2a) peer-to-peer via the default
# A2ARemoteAgentClient; loop-trace metadata
# (handoffHopCount/handoffRouteTrace/sourceAgentId) rides the A2A metadata
# as the spec's 循环保护（跨请求）journey requires.
#
# Journeys (L2 spec §7.2, one scenario each):
#   1  一级命中本地工作流        default query        → 本地答案, 无转调调用
#   2  一级转调二级              query 转调            → 意图返回 "3" → agent_card_l2 → 二级答案
#   3  二级退回一级重新路由      query 退回            → L2 not-in-scope 标记 → L1 重识别 → 本地答案
#   4  控制器返回真正异常        query 异常            → FEAT-002 错误映射 FAILED
#   5  转调消息缺少目标信息      query 无目标          → VERSATILE_HANDOFF_TARGET_MISSING
#   6  未授权目标               query 越权            → VERSATILE_HANDOFF_TARGET_NOT_ALLOWED
#   7  目标调用失败              query 不可达          → VERSATILE_HANDOFF_TARGET_UNAVAILABLE
#   8  下游请求用户输入(未启用)  query 补充信息        → VERSATILE_HANDOFF_CROSS_AGENT_RESUME_UNSUPPORTED
#   9  循环保护（同请求）        query 循环            → L1 重识别仍转调同目标 → DUPLICATE_TARGET
#   10 调用超时                 query 超时            → VERSATILE_HANDOFF_TIMEOUT (handoff.timeout=3s, L2 sleeps 10s)
#
# Usage:
#   ./scripts/local-e2e.sh              # build if needed, run all scenarios
#   SKIP_BUILD=1 ./scripts/local-e2e.sh # reuse existing jar
#
# Prerequisites: Java 17, and agent-service-app + agent-service-adapters-versatile-*
# installed in the local Maven repository (see repo CONTRIBUTING.md).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

L1_PORT="${L1_PORT:-18091}"
L2_PORT="${L2_PORT:-18092}"
HEALTH_TIMEOUT_SECONDS="${HEALTH_TIMEOUT_SECONDS:-90}"
JAR_FILE="$MODULE_DIR/target/versatile-controller-handoff-demo-0.1.0.jar"
LOG_DIR="$MODULE_DIR/target"

PIDS=()
declare -A PID_BY_NAME=()

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
  echo "==> Building jar: $JAR_FILE"
  (cd "$MODULE_DIR" && mvn -q package -DskipTests)
}

start_process() {
  local name="$1" port="$2" profiles="$3"
  local log="$LOG_DIR/${name}.log"
  echo "==> Starting $name (profiles=$profiles, port=$port)"
  java -jar "$JAR_FILE" \
    --spring.profiles.active="$profiles" \
    --server.port="$port" \
    >"$log" 2>&1 &
  local pid=$!
  PIDS+=("$pid")
  PID_BY_NAME["$name"]="$pid"
  echo "    pid=$pid log=$log"
}

# 就绪探测：/v1/query 缺 conversation_id 应返回 400，证明 servlet 与链路已就绪
wait_for_health() {
  local port="$1" name="$2"
  local deadline=$(( $(date +%s) + HEALTH_TIMEOUT_SECONDS ))
  printf "    %-10s " "$name:"
  while [ "$(date +%s)" -lt "$deadline" ]; do
    local status
    status=$(curl -s -o /dev/null -w "%{http_code}" -X POST "http://127.0.0.1:${port}/v1/query" \
      -H "Content-Type: application/json" -d '{}' 2>/dev/null || echo 000)
    if [ "$status" = "400" ]; then
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

# 发送一轮 /v1/query（默认非流式；SSE 用 send_query_sse）。SSE 在错误终态时连接被
# completeWithError 关闭，curl 可能以非零码结束，统一 || true 兜底。
send_query() {
  local conv_id="$1" content="$2" stream="${3:-false}"
  curl -s -X POST "http://127.0.0.1:${L1_PORT}/v1/query" \
    -H "Content-Type: application/json" \
    -H "X-User-ID: u-42" \
    -d "{\"conversation_id\":\"${conv_id}\",\"stream\":${stream},\"messages\":[{\"role\":\"user\",\"content\":\"${content}\"}]}" || true
}

send_query_sse() {
  curl -s -N -X POST "http://127.0.0.1:${L1_PORT}/v1/query" \
    -H "Content-Type: application/json" \
    -H "X-User-ID: u-42" \
    -H "Accept: text/event-stream" \
    -d "{\"conversation_id\":\"${1}\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"${2}\"}]}" || true
}

assert_contains() {
  local label="$1" body="$2" pattern="$3"
  if echo "$body" | grep -q "$pattern"; then
    echo "    PASS: $label contains '$pattern'"
    return 0
  fi
  echo "    FAIL: $label expected to contain '$pattern'" >&2
  echo "    body: $(echo "$body" | head -c 800)" >&2
  return 1
}

assert_log_contains() {
  local label="$1" logfile="$2" pattern="$3"
  if grep -q "$pattern" "$logfile" 2>/dev/null; then
    echo "    PASS: $label log contains '$pattern'"
    return 0
  fi
  echo "    FAIL: $label log expected to contain '$pattern'" >&2
  return 1
}

assert_log_not_contains() {
  local label="$1" logfile="$2" pattern="$3"
  if grep -q "$pattern" "$logfile" 2>/dev/null; then
    echo "    FAIL: $label log expected NOT to contain '$pattern'" >&2
    return 1
  fi
  echo "    PASS: $label log does not contain '$pattern'"
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

# 等待静态发现完成：对端 card 已注册进 A2ARemoteAgentCardRegistry（默认
# A2ARemoteAgentClient 依赖 registry 命中；失败每 30s 重试，这里等到成功为止）
wait_for_discovery() {
  local name="$1" pattern="$2"
  local deadline=$(( $(date +%s) + HEALTH_TIMEOUT_SECONDS ))
  printf "    %-10s " "$name:"
  while [ "$(date +%s)" -lt "$deadline" ]; do
    if grep -q "$pattern" "$LOG_DIR/${name}.log" 2>/dev/null; then
      echo "DISCOVERED"
      return 0
    fi
    printf "."
    sleep 1
  done
  echo " TIMEOUT"
  echo "ERROR: $name did not discover remote agent (pattern: $pattern)" >&2
  return 1
}

main() {
  echo "==> FEAT-002 控制器意图转调 场景旅程验收 (L2 §7.2)"
  build_if_needed

  echo
  echo "==================== 启动 layer1 + layer2 ===================="
  mkdir -p "$LOG_DIR"
  # 先起 layer2：layer1 启动时的静态发现需要立刻拉到 layer2 的 card，
  # 否则要等 30s 重试周期
  start_process layer2 "$L2_PORT" "layer2,mock-controller"
  wait_for_health "$L2_PORT" layer2
  start_process layer1 "$L1_PORT" "layer1,mock-controller"
  wait_for_health "$L1_PORT" layer1
  wait_for_discovery layer1 "Discovered remote agent 'agent_card_l2'"
  wait_for_discovery layer2 "Discovered remote agent 'agent_card_l1'"

  echo
  echo "==================== 场景1: 一级命中本地工作流 ===================="
  local resp
  resp=$(send_query "c1-local" "查询本地工作流")
  echo "    response: $(echo "$resp" | head -c 400)"
  assert_contains "s1-local-answer" "$resp" "一级本地业务答案"
  # 场景1最先执行：此时 layer1 日志不应有任何出站 A2A 转调调用
  assert_log_not_contains "s1-no-handoff" "$LOG_DIR/layer1.log" "A2A call agent="
  assert_log_contains "s1-controller-invoked" "$LOG_DIR/layer1.log" "Mock controller agentId=agent_L1_controller conversationId=c1-local"

  echo
  echo "==================== 场景2: 一级转调二级 (intent=3 → agent_card_l2) ===================="
  resp=$(send_query "c2-handoff" "帮我转调订机票")
  echo "    response: $(echo "$resp" | head -c 400)"
  assert_contains "s2-l2-answer" "$resp" "二级本域业务答案"
  assert_log_contains "s2-l1-outbound" "$LOG_DIR/layer1.log" "handoff outbound target=agent_card_l2 source=INTENT_MAPPING"
  assert_log_contains "s2-l1-caller" "$LOG_DIR/layer1.log" "A2A call agent=agent_card_l2"
  assert_log_contains "s2-l2-received" "$LOG_DIR/layer2.log" "conversation_id=c2-handoff"
  # mock 在信号帧前发送生产形态的意图回显帧（无 summary 键）：应被整行抑制且不报错
  assert_log_contains "s2-echo-suppressed" "$LOG_DIR/layer1.log" "handoff classify hit but required field"
  assert_log_not_contains "s2-no-contract-error" "$LOG_DIR/layer1.log" "VERSATILE_HANDOFF_MESSAGE_CONTRACT"

  echo
  echo "---- 场景2b: 同场景流式（SSE 增量透传） ----"
  local sse
  sse=$(send_query_sse "c2b-handoff-stream" "帮我转调订机票")
  echo "    sse: $(echo "$sse" | head -c 400)"
  assert_contains "s2b-stream-answer" "$sse" "二级本域业务答案"

  echo
  echo "==================== 场景3: 二级退回一级重新路由 ===================="
  resp=$(send_query "c3-bounce" "退回：这个请求不属于二级业务域")
  echo "    response: $(echo "$resp" | head -c 400)"
  assert_contains "s3-final-local-answer" "$resp" "一级本地业务答案"
  # L2 识别"不在范围"后不出站调用，直接回 not-in-scope 标记信封（upstream-signal）
  assert_log_contains "s3-l2-signal-emitted" "$LOG_DIR/layer2.log" "handoff not-in-scope signal emitted"
  assert_log_not_contains "s3-l2-no-outbound" "$LOG_DIR/layer2.log" "handoff outbound target="
  # L1 检测到标记后重跑自身控制器重新识别
  assert_log_contains "s3-l1-re-recognition" "$LOG_DIR/layer1.log" "handoff downstream not-in-scope, re-running controller"
  # L1 同一会话被控制器执行两次：首次转调、二次本地命中
  local l1_count
  l1_count=$(grep -c "Mock controller agentId=agent_L1_controller conversationId=c3-bounce" "$LOG_DIR/layer1.log" || true)
  assert_eq 2 "$l1_count" "s3-l1-invocations"

  echo
  echo "==================== 场景4: 控制器返回真正异常 ===================="
  local http_code body
  body=$(mktemp)
  http_code=$(curl -s -o "$body" -w "%{http_code}" -X POST "http://127.0.0.1:${L1_PORT}/v1/query" \
    -H "Content-Type: application/json" \
    -d '{"conversation_id":"c4-exception","stream":false,"messages":[{"role":"user","content":"触发控制器异常"}]}')
  resp=$(cat "$body")
  echo "    http_status: $http_code  body: $(echo "$resp" | head -c 300)"
  assert_eq 500 "$http_code" "s4-http-500"
  assert_contains "s4-error-contract" "$resp" "AGENT_EXECUTION_FAILED"
  assert_log_contains "s4-baseline-error-mapping" "$LOG_DIR/layer1.log" "controller query returned remote error.*c4-exception"
  assert_log_contains "s4-handled" "$LOG_DIR/layer1.log" "Handling controller-handoff query conversation_id=c4-exception"

  echo
  echo "==================== 场景5: 转调消息缺少目标信息 (intent=99 未映射) ===================="
  sse=$(send_query_sse "c5-missing" "无目标：意图没有映射")
  echo "    sse: $(echo "$sse" | head -c 500)"
  assert_contains "s5-target-missing" "$sse" "VERSATILE_HANDOFF_TARGET_MISSING"
  assert_log_contains "s5-log-code" "$LOG_DIR/layer1.log" "handoff failed code=VERSATILE_HANDOFF_TARGET_MISSING"

  echo
  echo "==================== 场景6: 未授权目标 (intent=6 → agent_card_forbidden) ===================="
  sse=$(send_query_sse "c6-forbidden" "越权：目标不在允许范围")
  echo "    sse: $(echo "$sse" | head -c 500)"
  assert_contains "s6-not-allowed" "$sse" "VERSATILE_HANDOFF_TARGET_NOT_ALLOWED"
  assert_log_contains "s6-log-code" "$LOG_DIR/layer1.log" "handoff failed code=VERSATILE_HANDOFF_TARGET_NOT_ALLOWED"

  echo
  echo "==================== 场景7: 目标调用失败 (intent=5 → dead port) ===================="
  sse=$(send_query_sse "c7-unreachable" "不可达：目标端口不通")
  echo "    sse: $(echo "$sse" | head -c 500)"
  assert_contains "s7-unavailable" "$sse" "VERSATILE_HANDOFF_TARGET_UNAVAILABLE"
  assert_log_contains "s7-log-code" "$LOG_DIR/layer1.log" "handoff failed code=VERSATILE_HANDOFF_TARGET_UNAVAILABLE"
  assert_log_contains "s7-unknown-agent" "$LOG_DIR/layer1.log" "Unknown remote agent: agent_card_dead"

  echo
  echo "==================== 场景8: 下游请求用户输入（续接能力未启用） ===================="
  sse=$(send_query_sse "c8-input-required" "补充信息：下游要问用户")
  echo "    sse: $(echo "$sse" | head -c 600)"
  assert_contains "s8-resume-unsupported" "$sse" "VERSATILE_HANDOFF_CROSS_AGENT_RESUME_UNSUPPORTED"
  assert_log_contains "s8-log-code" "$LOG_DIR/layer1.log" "handoff failed code=VERSATILE_HANDOFF_CROSS_AGENT_RESUME_UNSUPPORTED"
  assert_log_contains "s8-l2-interrupt" "$LOG_DIR/layer2.log" "A2A interrupt detected.*c8-input-required"

  echo
  echo "==================== 场景9: 循环保护（重识别后同目标反复转调） ===================="
  sse=$(send_query_sse "c9-loop" "循环：二级始终不在范围")
  echo "    sse: $(echo "$sse" | head -c 500)"
  # L2 回 not-in-scope 标记 → L1 重识别仍转调同一目标 → 单请求重复目标防环报错
  assert_contains "s9-duplicate-target" "$sse" "VERSATILE_HANDOFF_DUPLICATE_TARGET"
  assert_log_contains "s9-log-code" "$LOG_DIR/layer1.log" "handoff failed code=VERSATILE_HANDOFF_DUPLICATE_TARGET"
  # L2 只收到一次转调，且自身从不出站（upstream-signal 无反向调用）
  assert_log_not_contains "s9-l2-no-outbound" "$LOG_DIR/layer2.log" "handoff outbound target="

  echo
  echo "==================== 场景10: 调用超时 (handoff.timeout=3s, L2 延迟 10s) ===================="
  sse=$(send_query_sse "c10-timeout" "超时：下游睡 10 秒")
  echo "    sse: $(echo "$sse" | head -c 500)"
  assert_contains "s10-timeout" "$sse" "VERSATILE_HANDOFF_TIMEOUT"
  assert_log_contains "s10-log-code" "$LOG_DIR/layer1.log" "handoff failed code=VERSATILE_HANDOFF_TIMEOUT"

  echo
  echo "==> All §7.2 journeys passed."
  echo "    Logs: $LOG_DIR/{layer1,layer2}.log"
}

main "$@"
