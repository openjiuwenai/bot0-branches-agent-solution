#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
BASE_URL="${INTENT_AGENT_BASE_URL:-http://127.0.0.1:18200}"
REQUEST_TIMEOUT="${BANK_INTENT_REQUEST_TIMEOUT_SECONDS:-600}"
KEEP_ARTIFACTS="${BANK_INTENT_KEEP_ARTIFACTS:-false}"
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
  if [ "$status" -eq 0 ] && [ "$KEEP_ARTIFACTS" != "true" ]; then
    rm -rf "$TMP_DIR"
  else
    printf '\nLogs and responses retained in %s\n' "$TMP_DIR"
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

print_json() {
  local label="$1"
  local path="$2"
  printf '\n=== %s ===\n' "$label"
  "$PYTHON" - "$path" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as stream:
    json.dump(json.load(stream), sys.stdout, ensure_ascii=False, indent=2)
print()
PY
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
  print_json "REQUEST $(basename "$output")" "$output.request"
  curl -fsS --max-time "$REQUEST_TIMEOUT" -X POST "$BASE_URL/a2a/" \
    -H 'Content-Type: application/json' --data-binary "@$output.request" >"$output"
  print_json "RESPONSE $(basename "$output")" "$output"
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

assert_log_count() {
  local label="$1"
  local log_file="$2"
  local expected="$3"
  shift 3
  local actual
  actual="$($PYTHON - "$log_file" "$@" <<'PY'
import re, sys
path, *needles = sys.argv[1:]
with open(path, encoding="utf-8") as stream:
    lines = stream.readlines()
def contains(line, needle):
    if needle and needle[-1].isdigit():
        return re.search(re.escape(needle) + r"(?![0-9.])", line) is not None
    return needle in line
print(sum(1 for line in lines if all(contains(line, needle) for needle in needles)))
PY
)"
  [ "$actual" -eq "$expected" ] || fail "$label count=$actual, expected=$expected"
}

assert_log_order() {
  local label="$1"
  local log_file="$2"
  local first="$3"
  local second="$4"
  "$PYTHON" - "$log_file" "$first" "$second" <<'PY'
import sys
path, first, second = sys.argv[1:]
with open(path, encoding="utf-8") as stream:
    lines = stream.readlines()
first_index = next((index for index, line in enumerate(lines) if first in line), None)
second_index = next((index for index, line in enumerate(lines) if second in line), None)
if first_index is None or second_index is None or first_index >= second_index:
    raise SystemExit(f"expected {first!r} before {second!r}")
PY
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
run_completed fallback-routing "请帮我写一首关于星空的诗" "银行"
assert_log_count "fallback intent result" "$TMP_DIR/intent.log" 1 \
  "BANK_DEMO_TOOL_RESULT tool=intent_match" '"status":"FALLBACK"' \
  '"intentId":"bank-intent-fallback"'

transfer_context="$(new_context transfer-confirm)"
transfer_first="$TMP_DIR/transfer-confirm-1.json"
write_request "$transfer_context" "" "给张三转100元" "$transfer_first"
assert_state "$transfer_first" TASK_STATE_INPUT_REQUIRED
assert_contains "$transfer_first" "确认" "张三" "100"
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
assert_contains "$follow_3" "确认" "李四" "200"
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
plan_1="$TMP_DIR/transfer-plan-1.json"
plan_log_start="$(wc -l < "$TMP_DIR/intent.log")"
write_request "$plan_context" "" "给张三和李四各转100元" "$plan_1"
assert_state "$plan_1" TASK_STATE_INPUT_REQUIRED
assert_contains "$plan_1" "执行计划" "1. 给张三转账100元" "2. 给李四转账100元" \
  "当前执行第 1/2 步" "确认" "张三" "100"
plan_task="$(task_field "$plan_1" id)"
[ -n "$plan_task" ] || fail "planned transfer returned no task id"

plan_2="$TMP_DIR/transfer-plan-2.json"
write_request "$plan_context" "$plan_task" "确认" "$plan_2"
assert_state "$plan_2" TASK_STATE_INPUT_REQUIRED
assert_contains "$plan_2" "第 1/2 步已完成" "张三" "100" "当前执行第 2/2 步" "确认" "李四"

plan_3="$TMP_DIR/transfer-plan-3.json"
write_request "$plan_context" "$plan_task" "确认" "$plan_3"
assert_state "$plan_3" TASK_STATE_COMPLETED
assert_contains "$plan_3" "张三" "李四" "100"
pass "DeepAgent exposes its plan and completes routed transfers one by one"

tail -n "+$((plan_log_start + 1))" "$TMP_DIR/intent.log" >"$TMP_DIR/intent-plan.log"
assert_log_order "todo_create precedes planned intent routing" "$TMP_DIR/intent-plan.log" \
  "BANK_DEMO_TOOL_CALL tool=todo_create" "BANK_DEMO_TOOL_CALL tool=intent_match"
assert_log_count "planned intent_match calls" "$TMP_DIR/intent-plan.log" 2 \
  "BANK_DEMO_TOOL_CALL tool=intent_match"

assert_log_count "balance execution" "$TMP_DIR/balance.log" 1 \
  "BANK_DEMO_EXECUTION tool=query_balance"
assert_log_count "wealth recommendation execution" "$TMP_DIR/wealth-advisor.log" 1 \
  "BANK_DEMO_EXECUTION tool=recommend_wealth"
assert_log_count "calculator execution" "$TMP_DIR/intent.log" 1 \
  "BANK_DEMO_EXECUTION tool=bank_calculator"
assert_log_count "date execution" "$TMP_DIR/intent.log" 1 \
  "BANK_DEMO_EXECUTION tool=current_date"
assert_log_count "weather execution" "$TMP_DIR/intent.log" 1 \
  "BANK_DEMO_EXECUTION tool=weather_query"
assert_log_count "all confirmed transfer executions" "$TMP_DIR/transfer.log" 4 \
  "BANK_DEMO_EXECUTION tool=execute_transfer"
assert_log_count "no abandoned Wang Wu transfer" "$TMP_DIR/transfer.log" 0 \
  "BANK_DEMO_EXECUTION tool=execute_transfer" "recipient=王五"
assert_log_count "Zhang San 100 transfers" "$TMP_DIR/transfer.log" 2 \
  "BANK_DEMO_EXECUTION tool=execute_transfer" "recipient=张三" "amount=100"
assert_log_count "Li Si follow-up transfer" "$TMP_DIR/transfer.log" 1 \
  "BANK_DEMO_EXECUTION tool=execute_transfer" "recipient=李四" "amount=200"
assert_log_count "Li Si planned transfer" "$TMP_DIR/transfer.log" 1 \
  "BANK_DEMO_EXECUTION tool=execute_transfer" "recipient=李四" "amount=100"
assert_log_count "all confirmed wealth purchases" "$TMP_DIR/wealth-purchase.log" 2 \
  "BANK_DEMO_EXECUTION tool=purchase_wealth"
assert_log_count "10000 wealth purchase" "$TMP_DIR/wealth-purchase.log" 1 \
  "BANK_DEMO_EXECUTION tool=purchase_wealth" "amount=10000"
assert_log_count "1000 wealth purchase after intent change" "$TMP_DIR/wealth-purchase.log" 1 \
  "BANK_DEMO_EXECUTION tool=purchase_wealth" "amount=1000"
pass "business routing and exact execution audit"

echo "All bank intent routing scenarios passed."
