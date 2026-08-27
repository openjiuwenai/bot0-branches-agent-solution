#!/usr/bin/env bash
# 容器级部署 E2E（FEAT-004 远端工具 A2A 线级往返）：build → run（host 网络）→
# GET /drive-remote-tool（容器内对自身 /a2a 发真实 A2A 远端工具调用）→ 断言终答归一 → teardown。
# exit 0=过 / 1=不过。确定性（无 LLM），属强制每步交付门禁的一环。
set -uo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_backend.sh"

log() { echo "[e2e-remote] $*"; }

e2e_start "e2e_remote_tool_server" 18094
e2e_wait_health 60

# ── 以下与后端无关：真 socket 上的 HTTP 往返与断言 ──────────────────

log "4/4 GET /drive-remote-tool → 容器内 outbound 远端工具 → 真实 A2A JSONRPC → inbound /a2a → 终答"
R=$(curl -s -m 60 "$BASE/drive-remote-tool")
log "   响应: $R"
echo "$R" | python3 -c "
import sys, json
d = json.load(sys.stdin)
assert d.get('available') is True, f'远端目录未标记可用（Card 拉取失败？）: {d}'
assert d.get('match') is True, f'终答未归一往返: got={d.get(\"answer\")!r} expected={d.get(\"expected\")!r}'
print('[e2e-remote] ✅ 容器内 A2A 线级往返：outbound 远端工具 → 真实 a2a-sdk JSONRPC → inbound /a2a → 终答归一')
" || { log "❌ 远端工具往返断言失败"; e2e_diag; exit 1; }

# 生命周期边界帧是新增的对外 wire 行为，而上面那条走的是协调器直驱、
# 不经批次执行件与编排层，故整条不产生这一族帧。以下三步走**本仓真实的 REST 入口**：
# POST /v1/.../conversations/{会话} 读 SSE，帧经 `_sse_frames` 的完整出流路径出来。
#
# 早先这一段请求的是 /drive-delegation-frames——那个端点在容器内**进程内**构造编排器、
# 直接调投影函数收帧，真 socket 只覆盖了远端 A2A 那一腿，边界帧本身没过 REST wire。
# 且当时只断 `status == 'done'` 一个落态、断的是「键存在」而非字段集，
# 于是 `timeout` 那条对外文案与存量不一致的缺陷从这个缺口整个走了出去（独立复核点名）。

REST_BASE="$BASE/v1/proj/agents/root-planner/conversations"

# 断言器：从 SSE 流里取边界帧，逐一核字段集与取值。
read -r -d '' ASSERT_FRAMES <<'PYEOF'
import sys, json

expected_status = sys.argv[1]
expected_key = sys.argv[2]
expected_value = sys.argv[3] if len(sys.argv) > 3 else None

frames = []
for line in sys.stdin:
    line = line.strip()
    if not line.startswith("data:"):
        continue
    try:
        envelope = json.loads(line[5:].strip())
    except json.JSONDecodeError:
        continue
    inner = (envelope.get("custom_rsp_data") or {})
    if inner.get("event") != "sub_task":
        continue
    nested = inner.get("data") or {}
    if nested.get("event") in ("node_start", "node_end"):
        frames.append(nested)

starts = [f for f in frames if f.get("event") == "node_start"]
ends = [f for f in frames if f.get("event") == "node_end"]
assert starts, f"发起边界未出现在真实 REST 出口上: {frames}"
assert ends, f"收敛边界未出现在真实 REST 出口上: {frames}"
assert starts[0].get("entity_name"), f"发起边界缺被调方标识: {starts[0]}"

end = ends[0]
assert end.get("status") == expected_status, \
    f"收敛落态应为 {expected_status!r}，实得 {end.get('status')!r}: {end}"

# **字段集断言，不是「键存在」断言**：多一个键与少一个键同样是 wire 契约变更。
# 存量的 node_end 内层恰为 {event, status, <情境键>}。
got_keys = set(end) - {"elapsed_ms"}
want_keys = {"event", "status", expected_key}
assert got_keys == want_keys, \
    f"收敛边界字段集与存量不一致：多 {sorted(got_keys - want_keys)}、少 {sorted(want_keys - got_keys)}: {end}"

if expected_value is not None:
    assert end.get(expected_key) == expected_value, \
        f"情境键 {expected_key!r} 应为 {expected_value!r}，实得 {end.get(expected_key)!r}: {end}"

print(f"[e2e-remote]    落态 {expected_status}: {end}")
PYEOF

log "5/7 POST 真实 REST 入口（成功路径）→ 编排层 → 批次执行件 → 真实 A2A → SSE 边界帧"
curl -s -m 60 -N "$REST_BASE/conv-frames-done-$$" -H "Content-Type: application/json" \
  -H "X-E2E-Cred: token-$$" \
  -d '{"input":{"query":"查余额"},"trace_id":"trace-e2e-'"$$"'","stream":true}' \
  | python3 -c "$ASSERT_FRAMES" done content \
  || { log "❌ 成功落态的边界帧断言失败"; e2e_diag; exit 1; }

# G-01（2026-08-26 二轮重核）：经装配根装出的调用器此前根本不带会话上下文，南向恒回落
# 单文本片段，存量走 `session_context.headers` 的凭据透传整条断掉——而本脚本照样全绿。
# 现在断言：REST 入口带进来的凭据头，经南向数据片段、真 socket、对端 A2A 入站，真的到了。
log "5b/7 南向数据片段经真 socket 到达对端（凭据头透传链路闭合）"
curl -s -m 30 "$BASE/probe-southbound-arrival?conversation_id=conv-frames-done-$$" | python3 -c "
import json, sys
d = json.load(sys.stdin)
# 只认**远端 A2A 入站**写的那一次（服务身份 remote-planner）——REST 入口自己也会写一次，
# 不区分写入方时，装配根不接读取件脚本照样绿（实测）。
attempts = [a for a in (d.get('attempts') or []) if a.get('agent_id') == 'remote-planner']
assert attempts, f'远端 A2A 入站没有收到本会话的南向数据片段：{d}'
# 会话标识按存量形态派生：对端入站落在 {父会话}-sub-{目标标识} 上
derived = attempts[0].get('conversation_id', '')
assert derived.startswith('conv-frames-done-$$-sub-'), f'南向会话标识不是存量派生形态：{derived}'
headers = {str(k).lower(): v for k, v in (attempts[0].get('headers') or {}).items()}
assert headers.get('x-e2e-cred') == 'token-$$', f'凭据头没有随南向片段到达对端：{headers}'
# 本轮的链路标识随南向到达对端（需求九）。这一步验的是「调用方这一轮传的那个值
# 经真 socket 到了对端」——验不到「续轮取本轮而不是首轮」，那要两轮同会话，
# 由进程内判据覆盖（test_trace_identity_propagation.py 的本轮与回落两条）。
# 写清这条边界，免得读者把这一步当成需求九的完整证据。
#
# 注意：本段在双引号 heredoc 里，反引号会被 shell 当命令替换执行——实测报过
# 「command not found」。本文件这一段的注释一律不用反引号。
assert attempts[0].get('trace_id') == 'trace-e2e-$$', \
    f'本轮链路标识没有随南向片段到达对端：{attempts[0].get(\"trace_id\")!r}'
print('南向数据片段到达对端，凭据头与本轮链路标识透传闭合：', sorted(headers)[:8])
" || { log "❌ 南向数据片段未到达对端——装配根未接会话读取件（G-01），或本轮链路标识没随片段带出"; e2e_diag; exit 1; }

log "6/7 POST 真实 REST 入口（远端失败路径）→ 落态 failed"
curl -s -m 60 -N "$REST_BASE/conv-frames-failed-$$" -H "Content-Type: application/json" \
  -d '{"input":{"query":"__e2e_fail__ 请失败"},"stream":true}' \
  | python3 -c "$ASSERT_FRAMES" failed error \
  || { log "❌ 失败落态的边界帧断言失败"; e2e_diag; exit 1; }

# 超时那条**连取值一起断**：它出过一次内部中文原文上 wire 的缺陷，
# 而存量该位是无条件的固定字面量（remote_agent_handler.py 的子智能体族超时产出点）。
log "7/7 POST 真实 REST 入口（远端超时路径）→ 落态 timeout + 存量固定文案"
curl -s -m 60 -N "$REST_BASE/conv-frames-timeout-$$" -H "Content-Type: application/json" \
  -d '{"input":{"query":"__e2e_slow__ 请超时"},"stream":true}' \
  | python3 -c "$ASSERT_FRAMES" timeout error "sub agent timeout" \
  || { log "❌ 超时落态的边界帧断言失败"; e2e_diag; exit 1; }

log "✅ 边界帧经真实 REST wire 往返，三个落态的字段集与取值均与存量一致"
log "✅ 容器级 FEAT-004 远端工具 A2A 线级往返 E2E 通过"
exit 0
