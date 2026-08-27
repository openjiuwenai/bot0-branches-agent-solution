#!/usr/bin/env bash
# 容器级部署 E2E（在途执行被取消后落取消终态）：
# build → run → 流式发起（拿 taskId）→ 执行中途调 tasks/cancel → 经另一条连接查终态。
#
# 起因：`internal/ledger/ISSUE-LEDGER.md` 的「取消仍无部署级读数」，连续两轮被实证有价值。
# `run-parity.sh` 只比对取消端点的**响应信封**，没有用例驱动「在途 → 取消 → 查终态」；
# 进程内判据各自验「取消标志置位后消费循环会 break」，验不到从 HTTP 取消请求
# 到 Task 终态之间那一整段真实链路。
#
# 确定性（无 LLM，无外部依赖）。exit 0=过 / 1=不过。
set -uo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_backend.sh"

log() { echo "[e2e-cancel] $*"; }

e2e_start "e2e_cancel_terminal_server" 18100
e2e_wait_health 60

RPC="$BASE/a2a"
JSON=(-H "Content-Type: application/json")

# ── 以下与后端无关：真 socket 上的 HTTP 往返与断言 ──────────────────

CTX="c-cancel-$$"
SEND="{\"jsonrpc\":\"2.0\",\"id\":\"s1\",\"method\":\"SendStreamingMessage\",
  \"params\":{\"message\":{\"messageId\":\"m-cancel-1\",\"contextId\":\"$CTX\",
  \"role\":\"ROLE_USER\",\"parts\":[{\"text\":\"请慢慢处理\"}]}}}"

log "4/4 流式发起 → 读首帧取 taskId（执行仍在进行）"
# `--max-time 3` 只读首帧就放手——**执行不因此停止**（那正是断连场景已验过的事实），
# 于是后面调取消时它确实还在途。
FIRST=$(curl -s -N --max-time 3 "$BASE/a2a/" -H 'Content-Type: application/json' \
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
  log "❌ 首帧里没拿到任务标识，取消场景无从验起"
  log "   首帧内容：$(printf '%s' "$FIRST" | head -c 300)"
  e2e_diag; exit 1
fi
log "   在途 taskId = $TASK_ID"

log "   执行中途调 CancelTask"
CANCEL=$(curl -s -m 20 "$BASE/a2a/" -H 'Content-Type: application/json' -H 'A2A-Version: 1.0' \
  -d "{\"jsonrpc\":\"2.0\",\"id\":\"c1\",\"method\":\"CancelTask\",\"params\":{\"id\":\"$TASK_ID\"}}")
log "   取消响应: ${CANCEL:0:200}"

log "   经另一条连接调 GetTask 查终态"
GET=$(curl -s -m 20 "$BASE/a2a/" -H 'Content-Type: application/json' -H 'A2A-Version: 1.0' \
  -d "{\"jsonrpc\":\"2.0\",\"id\":\"g1\",\"method\":\"GetTask\",\"params\":{\"id\":\"$TASK_ID\"}}")
log "   查询响应: ${GET:0:300}"

CANCEL="$CANCEL" GET="$GET" python3 -c "
import json, os, sys

cancel = json.loads(os.environ['CANCEL'])
got = json.loads(os.environ['GET'])

# ① 取消请求本身要被受理 —— 不是 JSON-RPC 错误。
assert 'error' not in cancel, f'取消请求被拒: {cancel}'

# ② **终态必须是取消，不是完成**。
#    慢智能体要跑 12 秒，取消若不生效它会自己走完并落完成态。
state = (((got.get('result') or {}).get('status') or {}).get('state') or '')
assert state, f'查不到 Task 状态: {got}'
assert 'cancel' in str(state).lower(), (
    f'在途执行被取消后终态是 {state!r}，不是取消态'
    '——取消没有传播到执行侧，或者结算把它当成了正常完成'
)

# ③ **执行必须真的停下来**。
#
#    可观察的区分是产物数量：执行真停了，产物停在取消时刻的那几个；
#    没停的话它会继续产出，12 秒跑完约 60 个。取宽松阈值——
#    精确值随机器快慢漂，而漂移会让判据变成噪声源。
#
#    **变异读数（2026-08-24，写下来免得后人重做）**：把我方注册表的取消传播
#    整个摘掉（`ActiveStreamRegistry.cancel` 不再置位任何句柄），本脚本**四条全绿、
#    产物数一样是 13**。追下去发现原因——**A2A 面的执行停止由协议库保证**：
#    `a2a/server/agent_execution/active_task.py` 在收到取消时 `cancel()` 掉生产者任务。
#    我方注册表那条路服务的是自定义 REST 面与关停排水。
#
#    所以本脚本锁的是**对外行为**（取消受理 → 终态为取消 → 执行停止 → 可查），
#    不是「我方某个内部机制被调用了」。那是对的分工：部署级验对外事实，
#    内部机制由单元判据锁。**但要写明**，否则后人会拿它当「我方取消传播」的守卫。
artifacts = (got.get('result') or {}).get('artifacts') or []
assert len(artifacts) < 20, (
    f'取消后产物仍有 {len(artifacts)} 个，接近跑满——执行没有真的停下来，'
    '取消只落了终态帧而没有传到执行侧'
)

print(f'[e2e-cancel] ✅ 四项断言全过：取消被受理 / 终态为取消 / 另一条连接可查 / '
      f'执行真的停了（产物 {len(artifacts)} 个，跑满约 60）')
" || { log "❌ 断言失败"; e2e_diag; exit 1; }

log "✅ 容器级「在途取消落取消终态」E2E 通过"
exit 0
