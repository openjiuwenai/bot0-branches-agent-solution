#!/usr/bin/env bash
# 容器级部署 E2E（远端 4xx 时对外帧不带远端地址）：
# build → run → POST 自定义 REST → 我的 runtime → VersatileAgentHandler
# → 真 socket HTTP POST → 容器内 mock 平台回 422 → 错误块 → 对外 SSE 帧。
#
# 起因：2026-08-24 的缺陷报告 Bug-002。两次真实往返（远端 422 与 404）各抓到一次
# 完整远端 URL 出现在 SSE 帧的 error 与 custom_rsp_data.content 两位，
# 含内网 IP、端口、路径与查询参数。
#
# 确定性（无 LLM，无外部依赖）。exit 0=过 / 1=不过。
set -uo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_backend.sh"

log() { echo "[e2e-versatile-http-error] $*"; }

e2e_start "e2e_versatile_http_error_server" 18099
e2e_wait_health 60

# ── 以下与后端无关：真 socket 上的 HTTP 往返与断言 ──────────────────

log "4/4 POST（流式）→ runtime → 代理到必定回 422 的 mock 平台 → 收对外帧"
RESP=$(curl -s -m 40 -N "$BASE/v1/proj/agents/versatile-http-error-e2e/conversations/conv-4xx" \
  -H "Content-Type: application/json" \
  -d '{"input":{"query":"帮我查一下余额"},"stream":true}')
log "   流式响应前 300 字: ${RESP:0:300}"

echo "$RESP" | python3 -c "
import sys, json

raw = sys.stdin.read()
frames = [json.loads(l[6:]) for l in raw.splitlines() if l.startswith('data: ')]
assert frames, f'流式响应没有任何 data 帧: {raw[:400]}'

# ① 远端地址的每一段都不得出现在**任何一帧的任何字节**里。
#    逐帧逐字段查太脆（字段名会变），直接查整段原文——它就是调用方收到的字节。
LEAKS = ['internal-topology-9x7', 'workspace_id=42', '127.0.0.1:8090', 'for url', '/mock-versatile-4xx/']
for leaked in LEAKS:
    assert leaked not in raw, f'远端地址片段 {leaked!r} 泄漏到 wire 上: {raw[:600]}'

# ② 状态码必须到达调用方 —— 否则「不泄漏」会被一个空文案满足，
#    而空文案让运维拿不到任何线索（同 run-versatile-down.sh 的断言②）。
assert any('422' in (f.get('error') or '') for f in frames), \
    f'失败帧没有带上远端状态码，运维拿不到区分「远端拒绝」与「远端不可达」的线索: {raw[:600]}'

# 心跳信道的终帧是**失败通知**，不是失败帧：它的信封 success 位为真，存量同样如此。
# 与 run-versatile-down.sh 同一处订正——此前「所有帧失败位为假」之所以成立，
# 是因为本版根本不产这一帧，那条断言把我方与存量的差异当成规格固定住了。
def _inner(f):
    return f.get('custom_rsp_data') or {}

heartbeats = [f for f in frames if _inner(f).get('event') == 'heartbeat']
business = [f for f in frames if _inner(f).get('event') != 'heartbeat']

# ③ 失败必须写在 success 位上 —— 不得把失败包装为成功。
assert all(f.get('success') is False for f in business), \
    f'远端 422 却有业务帧声称成功: {[f.get(\"success\") for f in business]}'

# ④ 传输层失败不得伪装成业务输出：output 位必须为空。
assert all(not f.get('output') for f in frames), f'失败帧的 output 位混入了内容: {raw[:600]}'

# ⑤ 心跳终帧在场、形态照存量、排在失败帧之前，且 status 为 error。
#    **4xx 与超时是两支**：本条走 VersatileHttpStatusError，status=error；
#    超时那支 status=timeout，由 run-versatile-down.sh 与进程内判据分别锁。
assert len(heartbeats) == 1, f'心跳终帧不是恰好一帧（实收 {len(heartbeats)}）: {raw[:600]}'
hb_at = next(i for i, f in enumerate(frames) if _inner(f).get('event') == 'heartbeat')
fail_at = next(i for i, f in enumerate(frames) if f.get('error'))
assert hb_at < fail_at, f'心跳终帧排在失败帧之后（{hb_at} vs {fail_at}）: {raw[:600]}'
hb = _inner(heartbeats[0]).get('data') or {}
assert hb.get('contract_version') == 'HB-CONTRACT-1.0', f'契约版本位不对: {hb}'
assert hb.get('heartbeat_type') == 'end', f'终帧类型位不对: {hb}'
assert hb.get('status') == 'error', f'远端 4xx 的状态位应为 error: {hb}'
assert hb.get('source') == 'a2a_service', f'来源位不是存量字面量: {hb}'
assert isinstance(hb.get('seq'), int), f'终帧没补序号: {hb}'

# 心跳终帧同样不得泄漏远端地址：它带 request_id 与 source，两处都不含 URL。
for leaked in LEAKS:
    assert leaked not in str(hb), f'心跳终帧泄漏了远端地址片段 {leaked!r}: {hb}'

print('[e2e-versatile-http-error] ✅ 五项断言全过：地址不上 wire / 状态码到达 / 失败位为假 / output 位为空 / 心跳终帧形态与位置')
" || { log \"❌ 断言失败\"; e2e_diag; exit 1; }

log "✅ 容器级「远端 4xx 不泄漏远端地址」E2E 通过"
exit 0
