#!/usr/bin/env bash
# TC-022-SYNCSTATE · 部署级 E2E（自定义 REST 同步信封的状态位，Feat-Func-022b §4.7，方案 B1）：
# 宿主装配 MobileBankChannel(sync_state_fields=True) 后，同步信封在两键之后追加 state/event，
# 中断时追加 message；success/answer 不动。exit 0=过 / 1=不过。
#
# ## 它验的那一条
#
# 权威 FEAT-022 §5.1.4：同步响应可以是最终结果、当前 Task 投影或错误投影；仍在执行、中断或超时时
# 不得声明成功完成；信封字段名可自定义但结果与错误语义须可追溯。上游 runtime 把投影交宿主
# （CustomRestProtocolAdapter.fromA2ATask），本版同构：投影点交通道，状态位是宿主的装配选择。
#
# ## 断言
#
# 三态各一次真 socket 往返，逐键核对取值与键序（进程内经 ASGI 传输验不到真序列化的键序）：
#   完成   → {"success":true,"answer":"回显:<query>","state":"TASK_STATE_COMPLETED","event":"task_completed"}
#   中断   → 两键后 state=TASK_STATE_INPUT_REQUIRED、event=task_input_required、message=请输入验证码
#   失败   → success=false、answer 空、error=错误块文案，之后 state=TASK_STATE_FAILED、event=task_failed
# 期望值由请求推出（宿主 Agent 回显 query），不依赖模型。
set -uo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_backend.sh"

log() { echo "[e2e-rest-sync-state] $*"; }

e2e_start "e2e_rest_sync_state_server" 18118
e2e_wait_health 60

FAIL=0
check_case() {
  local label="$1" query="$2" expect="$3"
  local conv="conv-ss-$$-$RANDOM"
  local body
  body=$(curl -s -m 30 "$BASE/v1/proj/agents/edp_sync_state_e2e/conversations/$conv" \
    -H "Content-Type: application/json" -d "{\"input\":{\"query\":\"$query\"},\"stream\":false}")
  BODY="$body" EXPECT="$expect" LABEL="$label" python3 -c '
import json, os
raw = os.environ["BODY"]; label = os.environ["LABEL"]
got = json.loads(raw, object_pairs_hook=lambda kv: kv)   # 保留序列化键序
exp = json.loads(os.environ["EXPECT"], object_pairs_hook=lambda kv: kv)
assert got == exp, f"{label}：实得 {raw}，期望 " + os.environ["EXPECT"]
assert "E42" not in raw, f"{label}：错误码不上 wire：{raw}"
' && log "   ✅ $label" || { log "❌ $label 失败"; FAIL=1; }
}

Q="查余额-$$"
log "1/3 完成态"
check_case "完成" "$Q" "{\"success\":true,\"answer\":\"回显:$Q\",\"state\":\"TASK_STATE_COMPLETED\",\"event\":\"task_completed\"}"
log "2/3 中断态"
check_case "中断" "请中断-$$" '{"success":true,"answer":"","state":"TASK_STATE_INPUT_REQUIRED","event":"task_input_required","message":"请输入验证码"}'
log "3/3 失败态"
check_case "失败" "请失败-$$" '{"success":false,"answer":"","error":"下游不可用","state":"TASK_STATE_FAILED","event":"task_failed"}'

if [ "$FAIL" -ne 0 ]; then e2e_diag; exit 1; fi
log "✅ 同步信封状态位三态全部通过"
exit 0
