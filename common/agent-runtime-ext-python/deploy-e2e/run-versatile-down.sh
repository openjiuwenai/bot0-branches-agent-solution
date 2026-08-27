#!/usr/bin/env bash
# 容器级部署 E2E（远端不可达 → 失败终态）：
# build → run → POST 自定义 REST → 我的 runtime → VersatileAgentHandler
# → 真 socket HTTP POST 到一个无人监听的端口 → 连接被拒 → 错误块 + 异常传播 → 对外信封。
#
# 补的是 run-versatile.sh 的对侧：那一份验远端**可达**时的帧序与透传，
# 本份验远端**不可达**时对外那一层写出了什么。失败被包装成 completed 的形态
# 只在对外信封上可见——单测里把替身配成抛异常，验的是「异常抛出后我怎么处理」，
# 验不到「连接真的被拒之后，wire 上是什么」。
#
# 确定性（无 LLM，不依赖外网，不依赖超时等待）。exit 0=过 / 1=不过。
set -uo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_backend.sh"

log() { echo "[e2e-versatile-down] $*"; }

e2e_start "e2e_versatile_down_server" 18098
e2e_wait_health 60

log "4/5 流式路径：远端连接被拒，对外信封必须声明失败且异常传到入口层"
STREAM=$(curl -s -m 30 -N "$BASE/v1/proj/agents/versatile-down-e2e/conversations/conv-down-s" \
  -H "Content-Type: application/json" \
  -d '{"input":{"query":"帮我查一下余额"},"stream":true}')
log "   流式响应前 300 字: ${STREAM:0:300}"

echo "$STREAM" | python3 -c "
import sys, json
raw = sys.stdin.read()
frames = [json.loads(l[6:]) for l in raw.splitlines() if l.startswith('data: ')]
assert frames, f'流式响应没有任何 data 帧: {raw[:400]}'

def _inner(f):
    return f.get('custom_rsp_data') or {}

# 心跳信道的终帧是**失败通知**，不是失败帧：它的信封 success 位为真，存量同样如此
# （对等比对实测两侧一致）。此前「所有帧失败位为假」这条断言之所以成立，
# 是因为本版根本不产这一帧——那条断言把我方与存量的差异当成规格固定住了。
heartbeats = [f for f in frames if _inner(f).get('event') == 'heartbeat']
business = [f for f in frames if _inner(f).get('event') != 'heartbeat']

# ① 失败必须写在 success 位上 —— 这是「不得把失败包装为 completed」的对外体现。
assert all(f.get('success') is False for f in business), \
    f'远端不可达却有业务帧声称成功: {[f.get(\"success\") for f in business]}'

# ② 错误文案必须到达调用方，不能只留在日志里。
#    只发失败位不带原因，运维拿不到任何线索。
assert any(f.get('error') for f in frames), f'失败帧未携带任何错误文案: {raw[:400]}'

# ③ 传输层失败不得伪装成业务输出：output 位必须为空。
#    往 output 里塞异常原文既是把内部实现细节送上 wire，也会让调用方
#    把故障文本当成智能体的回答显示出来。
assert all(not f.get('output') for f in frames), \
    f'失败帧的 output 位混入了内容: {raw[:400]}'

# ④ 异常必须传播到入口层 —— 这一维前三条全测不到。
#    前三条只证明「处理器发的错误块被正确投影了」；而决定 Task 终态是
#    FAILED 还是 COMPLETED 的，是异常有没有传到入口层。实测变异确认：
#    把 handler 的 raise 换成 return（错误块照发），前三条全绿。
#
#    可观测差异是帧数：异常传到入口层时，入口**无条件**补一个兜底终态帧
#    （照存量：error 与 content 均为空串，诊断只进日志）。存量同输入下也是两帧。
# **心跳终帧要排除**：它同样无 error、content 为空串，混进来会让本条在
# 异常没传到入口层时照样绿——那正是本条要挡的形态。
term = [f for f in business if not f.get('error') and not _inner(f).get('content')]
assert term, (
    '缺入口层的兜底终态帧：异常没有传到入口层，Task 不会落 FAILED。'
    f'实收 {len(frames)} 帧：{raw[:400]}'
)

# ⑤ Versatile 调用失败时，心跳信道那一帧终帧必须在场、形态照存量、排在失败帧之前。
#    存量在 _call_versatile_adapter 的 except 里先发心跳后落业务态，顺序是对外形态的一部分。
#    **本段在 python3 -c 的双引号串里，不能用反引号**：bash 会把它当命令替换执行，
#    实测报 command-not-found 且断言照常通过——噪声不影响读数，但它是一次真实的注入面。
assert len(heartbeats) == 1, f'心跳终帧不是恰好一帧（实收 {len(heartbeats)}）: {raw[:400]}'
hb_at = next(i for i, f in enumerate(frames) if _inner(f).get('event') == 'heartbeat')
fail_at = next(i for i, f in enumerate(frames) if f.get('error'))
assert hb_at < fail_at, f'心跳终帧排在失败帧之后（{hb_at} vs {fail_at}）: {raw[:400]}'
hb = _inner(heartbeats[0]).get('data') or {}
assert hb.get('contract_version') == 'HB-CONTRACT-1.0', f'契约版本位不对: {hb}'
assert hb.get('heartbeat_type') == 'end', f'终帧类型位不对: {hb}'
assert hb.get('status') == 'error', f'远端不可达的状态位应为 error: {hb}'
assert hb.get('source') == 'a2a_service', f'来源位不是存量字面量: {hb}'
assert isinstance(hb.get('seq'), int), f'终帧没补序号: {hb}'

print('[e2e-versatile-down] ✅ 流式五项断言全过：失败位为假 / 错误文案到达 / output 位为空 / 异常传播到入口层 / 心跳终帧形态与位置')
" || { log "❌ 流式断言失败"; e2e_diag; exit 1; }

log "5/5 同步路径：执行失败须表达为 success:false、answer 空、error 带错误块文案（2026-08-26 裁定，issue #152）"
SYNC=$(curl -s -m 30 "$BASE/v1/proj/agents/versatile-down-e2e/conversations/conv-down-n" \
  -H "Content-Type: application/json" \
  -d '{"input":{"query":"帮我查一下余额"},"stream":false}')
log "   同步响应: $SYNC"

echo "$SYNC" | python3 -c "
import sys, json
d = json.loads(sys.stdin.read())

# 同步那一路在执行失败时须表达失败（用户 2026-08-26 裁定：存量失败仍 success:true 是缺陷，
# 新版本修正，社区 issue #152）：success:false、answer 空串、error 为错误块自带的文案，
# 且不含远端地址（与流式失败帧同一条不泄漏纪律）。
assert d.get('success') is False, f'同步路径执行失败仍返回成功真值: {d}'
assert d.get('answer') == '', f'同步路径的 answer 不再是空串: {d}'
assert d.get('error'), f'同步路径失败信封缺 error 文案: {d}'
assert 'http://' not in d.get('error', '') and ':8' not in d.get('error', ''), f'error 文案泄漏了远端地址: {d}'

print('[e2e-versatile-down] ✅ 同步四项断言全过：失败位为假 / answer 空 / error 到达 / 不含远端地址')
" || { log "❌ 同步断言失败"; e2e_diag; exit 1; }

log "✅ 容器级远端不可达失败终态 E2E 通过"
exit 0
