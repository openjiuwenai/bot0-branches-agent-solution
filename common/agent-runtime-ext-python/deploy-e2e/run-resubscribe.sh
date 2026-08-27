#!/usr/bin/env bash
# TC-001-RESUB · 部署级 E2E（活动 Task 的重订阅）：
# 起阻塞智能体 → 第一条连接拿到 taskId 后**保持不断** → 第二条连接发 SubscribeToTask。
# exit 0=过 / 1=不过。
#
# ## 它补的是哪一块
#
# 权威 FEAT-001 那条 MUST 要求支持 `SubscribeToTask(params.id=taskId)`，
# 让另一个连接先收到订阅时读取的 Task 快照、再接着收挂接后的后续事件（权威条款原文）。此前只有一个进程内判据，
# 且挂着 `xfail(strict=True)`，reason 写「流式执行途中 Task 已被标终态」。
#
# **2026-08-25 实测推翻了那个结论——错在工具不在产品。**
# 那个判据用 `httpx.ASGITransport` 驱动，而它不是流式的：把整个 ASGI 应用跑完、
# 收齐全部响应体才交给客户端。处理器阻塞 15 秒时的实测时序是
# 「handler 15.00s 跑完 → 客户端 15.01s 才拿到响应头」——
# **「原流仍开着」这个场景在进程内传输下根本构造不出来**。
#
# 真 socket 下同一段代码：0.05s 拿到 taskId、0.05s 发起重订阅、
# 200 + text/event-stream、首帧 `state = TASK_STATE_WORKING`。**拿到的正是活动 Task。**
#
# ## 要验的那一条
#
# **重订阅拿到的必须是活动 Task，不是终态拒绝。** 只断言「返回 200」不够——
# 终态订阅被拒时也可能是 200 带 JSON-RPC error；只断言「有响应」更不够。
# 三项一起验：出流形态、无协议错误、首帧状态是活动态。
#
# 期望值来源：状态取自本次实跑的重订阅首帧，脚本只做结构断言。
set -uo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_backend.sh"

log() { echo "[e2e-resubscribe] $*"; }

e2e_start "e2e_resubscribe_server" 18116
e2e_wait_health 60

CTX="c-resub-$$"
SEND="{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"SendStreamingMessage\",
  \"params\":{\"message\":{\"messageId\":\"m-resub-1\",\"contextId\":\"$CTX\",
  \"role\":\"ROLE_USER\",\"parts\":[{\"text\":\"跑一会\"}]}}}"

log "1/3 发起流式请求并**保持连接**，读首帧取任务标识"
# 后台跑、把首帧写进文件：**连接必须留着**——断了就变成「终态订阅」，
# 那测的是另一回事（见上文 reason 的由来）。
FIRST_FILE=$(mktemp)
curl -s -N --max-time 25 "$BASE/a2a/" -H 'Content-Type: application/json' \
  -H 'A2A-Version: 1.0' -d "$SEND" > "$FIRST_FILE" 2>/dev/null &
STREAM_PID=$!

TASK_ID=""
for _ in $(seq 1 50); do
  sleep 0.2
  TASK_ID=$(python3 -c "
import json, sys
for line in open('$FIRST_FILE', encoding='utf-8', errors='ignore'):
    if not line.startswith('data: '):
        continue
    result = json.loads(line[6:] or '{}').get('result', {})
    for key in ('task', 'statusUpdate'):
        tid = result.get(key, {}).get('id') or result.get(key, {}).get('taskId')
        if tid:
            print(tid); sys.exit(0)
" 2>/dev/null || true)
  [ -n "$TASK_ID" ] && break
done

if [ -z "$TASK_ID" ]; then
  log "❌ 首帧里没拿到任务标识，重订阅场景无从验起"
  log "   首帧内容：$(head -c 300 "$FIRST_FILE")"
  kill "$STREAM_PID" 2>/dev/null || true; rm -f "$FIRST_FILE"
  e2e_diag; exit 1
fi
log "  任务标识 $TASK_ID（原流仍在跑，PID $STREAM_PID）"

log "2/3 **原流不断**，另一条连接发 SubscribeToTask"
SUB="{\"jsonrpc\":\"2.0\",\"id\":\"sub-1\",\"method\":\"SubscribeToTask\",\"params\":{\"id\":\"$TASK_ID\"}}"
SUB_OUT=$(curl -s -i -N --max-time 6 "$BASE/a2a/" -H 'Content-Type: application/json' \
  -H 'A2A-Version: 1.0' -d "$SUB" 2>/dev/null | head -c 4000 || true)

kill "$STREAM_PID" 2>/dev/null || true
rm -f "$FIRST_FILE"

log "3/3 断言三项：出流形态、无协议错误、首帧是活动态"
SUB_OUT="$SUB_OUT" python3 -c "
import json, os, sys

raw = os.environ['SUB_OUT']
head, _, body = raw.partition('\r\n\r\n')
if not body:
    head, _, body = raw.partition('\n\n')

# 一、出流形态：必须是 SSE，不能退化成普通 JSON 响应
assert 'text/event-stream' in head.lower(), (
    f'重订阅没有走流式出口，响应头是：{head[:300]}')

frames = [l[6:] for l in body.splitlines() if l.startswith('data: ')]
assert frames, f'重订阅没有任何 data 帧，响应体：{body[:300]}'

first = json.loads(frames[0] or '{}')

# 二、无协议错误：终态订阅被拒时也是 200，错在 JSON-RPC 信封里
assert 'error' not in first, (
    f'重订阅被拒：{json.dumps(first, ensure_ascii=False)[:300]}')

# 三、首帧是**活动**态。终态集逐个排除，不用 'not in 终态' 的宽判——
# 那样一个空状态也能过。
state = first.get('result', {}).get('task', {}).get('status', {}).get('state', '')
assert state, f'首帧里没有状态：{json.dumps(first, ensure_ascii=False)[:300]}'
assert state in ('TASK_STATE_WORKING', 'TASK_STATE_SUBMITTED', 'TASK_STATE_INPUT_REQUIRED'), (
    f'重订阅拿到的不是活动 Task，状态是 {state}——'
    '这正是那条 xfail 声称的现象，若在真 socket 下复现则说明是真缺陷')

print(f'  重订阅拿到活动 Task：{state}')
" || { log "❌ 断言失败"; e2e_diag; exit 1; }

log "通过"
exit 0
