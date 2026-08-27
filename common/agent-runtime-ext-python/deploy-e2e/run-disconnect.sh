#!/usr/bin/env bash
# TC-001-DISC · 部署级 E2E（客户端真断连的结算行为）：
# 起慢智能体 → 客户端在执行中途掐断 socket → 另一条连接查 Task 状态。
# exit 0=过 / 1=不过。
#
# ## 它补的是哪一块
#
# `agent_runtime/tests/test_client_disconnect_settlement.py` 覆盖了两种注入形态
# （`aclose()` 的 `GeneratorExit`、`Task.cancel()` 的 `CancelledError`），
# 但都在**进程内**注入。**断连是传输层的物理事实**——进程内造不出
# 「对端 TCP 连接没了」，也就测不到从 socket 断到生成器收尾之间那整段真实链路。
# `internal/ledger/ISSUE-LEDGER.md` 的 R12-10 逐字登记了这条缺口。
#
# ## 要验的那一条
#
# **断连不得被结算成完成态**。它既非该轮真实终态，也非任何中断语义；
# 落成完成态之后，下一轮会按错误的状态路由——本仓实测过同型事故
# （中断落不下去时，用户的回答被当成新问题执行，两轮都返回 200 且无任何信号）。
#
# 期望值来源：状态取自本次实跑的 `GetTask` 响应，脚本只做结构断言。
set -uo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_backend.sh"

log() { echo "[e2e-disconnect] $*"; }

e2e_start "e2e_disconnect_server" 18105
e2e_wait_health 60

CTX="c-disc-$$"
SEND="{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"SendStreamingMessage\",
  \"params\":{\"message\":{\"messageId\":\"m-disc-1\",\"contextId\":\"$CTX\",
  \"role\":\"ROLE_USER\",\"parts\":[{\"text\":\"慢慢查\"}]}}}"

log "1/3 发起流式请求，读到首帧后**真的掐断连接**"
# `--max-time 2` 让 curl 到点直接关掉 socket——服务端那侧是真实的对端断开，
# 不是应用层的优雅收尾。首帧里带着 taskId，掐断前已经拿到。
FIRST=$(curl -s -N --max-time 2 "$BASE/a2a/" -H 'Content-Type: application/json' \
  -H 'A2A-Version: 1.0' -d "$SEND" 2>/dev/null | head -c 4000 || true)

TASK_ID=$(printf '%s' "$FIRST" | python3 -c "
import json, sys
for line in sys.stdin:
    if not line.startswith('data: '):
        continue
    result = json.loads(line[6:]).get('result', {})
    for key in ('task', 'statusUpdate'):
        tid = result.get(key, {}).get('id') or result.get(key, {}).get('taskId')
        if tid:
            print(tid); break
    else:
        continue
    break
")

if [ -z "$TASK_ID" ]; then
  log "❌ 首帧里没拿到任务标识，断连场景无从验起"
  log "   首帧内容：$(printf '%s' "$FIRST" | head -c 300)"
  e2e_diag; exit 1
fi
log "  任务标识 $TASK_ID"

log "2/4 断连后立刻查：Task 必须还活着，且没被转成失败或取消"
sleep 2
snapshot() {
  curl -s -m 20 "$BASE/a2a/" -H 'Content-Type: application/json' -H 'A2A-Version: 1.0' \
    -d "{\"jsonrpc\":\"2.0\",\"id\":\"$1\",\"method\":\"GetTask\",\"params\":{\"id\":\"$TASK_ID\"}}"
}
# **参数名是 `id`，不是 `name`**——实跑取值纠正的：传 `name` 时协议库回
# 「has no field named "name"，可用字段 [tenant, id, historyLength]」。
MID=$(snapshot 2)

log "3/4 等它自己走完（处理器总时长 6 秒）"
sleep 7
FINAL=$(snapshot 3)

log "4/4 断言两段快照"
MID="$MID" FINAL="$FINAL" python3 -c "
import json, os

def read(name):
    snap = json.loads(os.environ[name])
    assert 'error' not in snap, f'{name}: GetTask 返回协议错误：{snap}'
    # \`GetTask\` 直接返回 Task 本身，不再包一层 \`task\`——实跑取值纠正的。
    task = snap.get('result', {})
    state = task.get('status', {}).get('state', '')
    assert state, f'{name}: 快照里没有状态：{json.dumps(task)[:300]}'
    return state, len(task.get('artifacts', []))

mid_state, mid_count = read('MID')
final_state, final_count = read('FINAL')

# 权威 FEAT-001:159（MUST）：SSE 因客户端超时、网络中断或主动关闭而断开时，
# Task 必须继续在当前生命周期状态执行，**不得因 SSE 断开而转为 failed 或 canceled**。
# 同文 :93 的场景表与 :178 的边界表各重申一次。
assert mid_state not in ('TASK_STATE_FAILED', 'TASK_STATE_CANCELLED', 'TASK_STATE_CANCELED'), (
    f'SSE 断开把 Task 打成了 {mid_state}——权威要求它继续执行')

# **执行真的在继续**：只看状态不够，状态可以停在 WORKING 而执行早已停摆，
# 那时对外表现是「一直在处理」，与真在处理逐字节相同。产出数增长才是证据。
assert final_count > mid_count, (
    f'断连后执行停摆了：断连时 {mid_count} 条产出，七秒后仍是 {final_count} 条')
assert final_state == 'TASK_STATE_COMPLETED', (
    f'断连后没有自己走完：{final_state}')

print(f'  断连时 {mid_state}，产出 {mid_count} 条')
print(f'  七秒后 {final_state}，产出 {final_count} 条——执行未受断连影响')
" || { log "❌ 断言失败"; e2e_diag; exit 1; }

log "通过"
exit 0
