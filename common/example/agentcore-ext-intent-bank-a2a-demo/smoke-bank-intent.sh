#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
BASE_URL="${INTENT_AGENT_BASE_URL:-http://127.0.0.1:18200}"
REQUEST_TIMEOUT="${BANK_INTENT_REQUEST_TIMEOUT_SECONDS:-600}"
TMP_DIR="$(mktemp -d)"
PIDS=()

if command -v python3 >/dev/null 2>&1; then
  PYTHON=python3
elif command -v python >/dev/null 2>&1; then
  PYTHON=python
else
  echo "FAIL: python3 or python is required" >&2
  exit 1
fi

cleanup() {
  local status=$?
  for pid in "${PIDS[@]:-}"; do
    kill "$pid" 2>/dev/null || true
  done
  if [ "$status" -eq 0 ]; then
    rm -rf "$TMP_DIR"
  else
    printf '\nLogs and responses retained in %s\n' "$TMP_DIR" >&2
  fi
}
trap cleanup EXIT

fail() {
  echo "FAIL: $1" >&2
  exit 1
}

pass() {
  echo "PASS: $1"
}

if [ ! -r "$SCRIPT_DIR/application-intent_local.yml" ]; then
  fail "copy application-intent_local-example.yml to application-intent_local.yml and configure both models"
fi

cd "$SCRIPT_DIR"

echo "[1/4] Build demo"
mvn -q clean package

start_agent() {
  local label="$1"
  local jar="$2"
  java -jar "$jar" >"$TMP_DIR/$label.log" 2>&1 &
  PIDS+=("$!")
}

wait_for_health() {
  local label="$1"
  local url="$2"
  local pid="$3"
  for _ in $(seq 1 90); do
    if ! kill -0 "$pid" 2>/dev/null; then
      fail "$label exited before health check; see $TMP_DIR/$label.log"
    fi
    if curl -fsS "$url/health" 2>/dev/null | grep -q '"status":"healthy"'; then
      return
    fi
    sleep 2
  done
  fail "$label did not become healthy; see $TMP_DIR/$label.log"
}

echo "[2/4] Start business agents"
start_agent balance balance-agent-runtime/target/intent-bank-balance-agent-runtime-0.1.0.jar
start_agent transfer transfer-agent-runtime/target/intent-bank-transfer-agent-runtime-0.1.0.jar
start_agent wealth-advisor wealth-advisor-agent-runtime/target/intent-bank-wealth-advisor-agent-runtime-0.1.0.jar
start_agent wealth-purchase wealth-purchase-agent-runtime/target/intent-bank-wealth-purchase-agent-runtime-0.1.0.jar
wait_for_health balance http://127.0.0.1:18201 "${PIDS[0]}"
wait_for_health transfer http://127.0.0.1:18202 "${PIDS[1]}"
wait_for_health wealth-advisor http://127.0.0.1:18203 "${PIDS[2]}"
wait_for_health wealth-purchase http://127.0.0.1:18204 "${PIDS[3]}"

echo "[3/4] Start intent agent"
start_agent intent intent-agent-runtime/target/intent-bank-intent-agent-runtime-0.1.0.jar
wait_for_health intent "$BASE_URL" "${PIDS[4]}"
for port in 18200 18201 18202 18203 18204; do
  curl -fsS "http://127.0.0.1:$port/.well-known/agent-card.json" >"$TMP_DIR/card-$port.json"
done
pass "five health checks and Agent Cards"

write_request() {
  local context_id="$1"
  local task_id="$2"
  local message="$3"
  local output="$4"
  "$PYTHON" - "$context_id" "$task_id" "$message" "$output.request" <<'PY'
import json, sys, uuid
context_id, task_id, message, output = sys.argv[1:]
request_message = {
    "role": "ROLE_USER",
    "contextId": context_id,
    "parts": [{"text": message}],
}
if task_id:
    request_message["taskId"] = task_id
payload = {
    "jsonrpc": "2.0",
    "id": str(uuid.uuid4()),
    "method": "SendMessage",
    "params": {"message": request_message},
}
with open(output, "w", encoding="utf-8") as stream:
    json.dump(payload, stream, ensure_ascii=False)
PY
  curl -fsS --max-time "$REQUEST_TIMEOUT" -X POST "$BASE_URL/a2a/" \
    -H 'Content-Type: application/json' --data-binary "@$output.request" >"$output"
}

task_field() {
  local response="$1"
  local field="$2"
  "$PYTHON" - "$response" "$field" <<'PY'
import json, sys
path, field = sys.argv[1:]
with open(path, encoding="utf-8") as stream:
    response = json.load(stream)
if response.get("error"):
    raise SystemExit("JSON-RPC error: " + json.dumps(response["error"], ensure_ascii=False))
task = ((response.get("result") or {}).get("task") or {})
if field == "state":
    print(((task.get("status") or {}).get("state")) or "")
elif field == "id":
    print(task.get("id") or "")
PY
}

assert_state() {
  local response="$1"
  local expected="$2"
  local actual
  actual="$(task_field "$response" state)"
  [ "$actual" = "$expected" ] || fail "$(basename "$response") state=$actual, expected=$expected"
}

assert_contains() {
  local response="$1"
  shift
  "$PYTHON" - "$response" "$@" <<'PY'
import json, sys
path, *expected = sys.argv[1:]
with open(path, encoding="utf-8") as stream:
    text = json.dumps(json.load(stream), ensure_ascii=False).lower()
normalized = text.replace(",", "").replace(" ", "")
missing = [item for item in expected if item.lower().replace(",", "").replace(" ", "") not in normalized]
if missing:
    raise SystemExit("response missing " + repr(missing) + ": " + text[:4000])
PY
}

assert_contains_any() {
  local response="$1"
  shift
  "$PYTHON" - "$response" "$@" <<'PY'
import json, sys
path, *expected = sys.argv[1:]
with open(path, encoding="utf-8") as stream:
    text = json.dumps(json.load(stream), ensure_ascii=False).lower()
normalized = text.replace(",", "").replace(" ", "")
if not any(item.lower().replace(",", "").replace(" ", "") in normalized for item in expected):
    raise SystemExit("response contains none of " + repr(expected) + ": " + text[:4000])
PY
}

new_context() {
  printf 'intent-bank-%s-%s-%s' "$1" "$(date +%Y%m%d%H%M%S)" "$RANDOM"
}

run_completed() {
  local label="$1"
  local message="$2"
  shift 2
  local response="$TMP_DIR/$label.json"
  write_request "$(new_context "$label")" "" "$message" "$response"
  assert_state "$response" TASK_STATE_COMPLETED
  assert_contains "$response" "$@"
  pass "$label"
}

echo "[4/4] Run routing, interruption, intent-change, and planning scenarios"
run_completed balance-routing "查询我的账户余额" "12800"
run_completed wealth-advisor-routing "推荐一款稳健的三个月理财" "稳盈90天"
run_completed calculator-routing "帮我计算 6 * 7" "42"
date_response="$TMP_DIR/date-routing.json"
write_request "$(new_context date-routing)" "" "今天是几号" "$date_response"
assert_state "$date_response" TASK_STATE_COMPLETED
today_iso="$(date +%Y-%m-%d)"
today_zh="$(date +%Y)年$((10#$(date +%m)))月$((10#$(date +%d)))日"
assert_contains_any "$date_response" "$today_iso" "$today_zh"
pass "date-routing"
run_completed weather-routing "深圳天气怎么样" "深圳"
run_completed fallback-routing "请帮我写一首关于星空的诗" "匹配" "银行"

transfer_context="$(new_context transfer-confirm)"
transfer_first="$TMP_DIR/transfer-confirm-1.json"
write_request "$transfer_context" "" "给张三转100元" "$transfer_first"
assert_state "$transfer_first" TASK_STATE_INPUT_REQUIRED
assert_contains "$transfer_first" "确认"
transfer_task="$(task_field "$transfer_first" id)"
[ -n "$transfer_task" ] || fail "transfer confirmation returned no task id"
transfer_second="$TMP_DIR/transfer-confirm-2.json"
write_request "$transfer_context" "$transfer_task" "确认" "$transfer_second"
assert_state "$transfer_second" TASK_STATE_COMPLETED
assert_contains "$transfer_second" "张三" "100"
pass "transfer confirmation and resume"

follow_context="$(new_context transfer-followup)"
follow_1="$TMP_DIR/transfer-followup-1.json"
write_request "$follow_context" "" "我要转账" "$follow_1"
assert_state "$follow_1" TASK_STATE_INPUT_REQUIRED
follow_task="$(task_field "$follow_1" id)"
follow_2="$TMP_DIR/transfer-followup-2.json"
write_request "$follow_context" "$follow_task" "收款人是李四" "$follow_2"
assert_state "$follow_2" TASK_STATE_INPUT_REQUIRED
follow_3="$TMP_DIR/transfer-followup-3.json"
write_request "$follow_context" "$follow_task" "金额是200元" "$follow_3"
assert_state "$follow_3" TASK_STATE_INPUT_REQUIRED
assert_contains "$follow_3" "确认"
follow_4="$TMP_DIR/transfer-followup-4.json"
write_request "$follow_context" "$follow_task" "确认" "$follow_4"
assert_state "$follow_4" TASK_STATE_COMPLETED
assert_contains "$follow_4" "李四" "200"
pass "transfer information follow-up and resume"

purchase_context="$(new_context wealth-purchase)"
purchase_1="$TMP_DIR/wealth-purchase-1.json"
write_request "$purchase_context" "" "购买一万元稳盈90天" "$purchase_1"
assert_state "$purchase_1" TASK_STATE_INPUT_REQUIRED
assert_contains "$purchase_1" "确认"
purchase_task="$(task_field "$purchase_1" id)"
purchase_2="$TMP_DIR/wealth-purchase-2.json"
write_request "$purchase_context" "$purchase_task" "确认" "$purchase_2"
assert_state "$purchase_2" TASK_STATE_COMPLETED
assert_contains "$purchase_2" "稳盈90天" "10000"
pass "wealth purchase confirmation and resume"

change_context="$(new_context intent-change)"
change_1="$TMP_DIR/intent-change-1.json"
write_request "$change_context" "" "给王五转50元" "$change_1"
assert_state "$change_1" TASK_STATE_INPUT_REQUIRED
change_task="$(task_field "$change_1" id)"
change_2="$TMP_DIR/intent-change-2.json"
write_request "$change_context" "$change_task" "改为购买1000元稳盈90天理财" "$change_2"
assert_state "$change_2" TASK_STATE_INPUT_REQUIRED
assert_contains "$change_2" "理财" "确认"
change_3="$TMP_DIR/intent-change-3.json"
write_request "$change_context" "$change_task" "确认" "$change_3"
assert_state "$change_3" TASK_STATE_COMPLETED
assert_contains "$change_3" "稳盈90天" "1000"
pass "intent change re-enters intent_match"

plan_context="$(new_context transfer-plan)"
plan_response="$TMP_DIR/transfer-plan-1.json"
write_request "$plan_context" "" "给张三和李四各转100元" "$plan_response"
plan_task="$(task_field "$plan_response" id)"
for step in 2 3 4 5; do
  state="$(task_field "$plan_response" state)"
  if [ "$state" = TASK_STATE_COMPLETED ]; then
    break
  fi
  [ "$state" = TASK_STATE_INPUT_REQUIRED ] || fail "planned transfer reached unexpected state $state"
  next="$TMP_DIR/transfer-plan-$step.json"
  write_request "$plan_context" "$plan_task" "确认" "$next"
  plan_response="$next"
done
assert_state "$plan_response" TASK_STATE_COMPLETED
assert_contains "$plan_response" "张三" "李四" "100"
pass "DeepAgent plan executes two routed transfer steps"

echo "All bank intent routing scenarios passed."
