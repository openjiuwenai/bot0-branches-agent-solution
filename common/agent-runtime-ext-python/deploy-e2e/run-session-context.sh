#!/usr/bin/env bash
# 入站会话上下文写侧的真实运行环境验证（Feat-Func-004b §2.3.1.1）。
#
# ## 四段判据
#
# | 段 | 判什么 | 不验会怎样 |
# |---|---|---|
# | 写入发生 | 真实 HTTP 请求后该键存在 | 路由里漏调写入时，进程内判据照样绿 |
# | 字段集相等 | 五字段一个不多一个不少 | 少 agent_id 会让存量的续轮恢复读到空 |
# | 内容正确 | 请求头与请求体真的落进去了 | 写了空壳等于没写 |
# | 南向读得到 | 经出站件取数路径拿到非空上下文 | 这是本项要修的那个后果本身 |
#
# 第四段最要紧：前三段验「写对了」，第四段验「写的东西能被用上」。
# 本项此前的缺口表现就是南向四字段恒空——不验第四段，等于没验到修复。
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/_backend.sh"

PORT="${SESSION_CTX_PORT:-18102}"
CONV="conv-e2e-$$"

_pid=$(ss -lptn "sport = :$PORT" 2>/dev/null | grep -oP 'pid=\K[0-9]+' | head -1)
[ -n "$_pid" ] && { e2e_log "清理端口 $PORT 的残留进程（pid $_pid）"; kill -9 "$_pid" 2>/dev/null; sleep 1; }

e2e_start "e2e_session_context_server" "$PORT"
e2e_wait_health 60
e2e_log "服务就绪（端口 ${PORT}，会话 ${CONV}）"

FAILED=0

# ── 写入前：键应不存在（证明后面的存在是本次请求造成的）──────
BEFORE=$(curl -s -m 20 "$BASE/probe-session-context?conversation_id=$CONV" 2>/dev/null)
case "$BEFORE" in
  *'"found":false'*) e2e_log "✅ 请求前该键不存在——后续的存在可归因于本次请求" ;;
  *) e2e_log "❌ 请求前该键已存在，本轮读数无法归因：${BEFORE:0:160}"; FAILED=1 ;;
esac

# ── 发一次真实 REST 请求 ────────────────────────────────────
e2e_log "发真实 REST 请求（带自定义头与查询参数）"
RESP=$(curl -s -m 30 -X POST \
  "$BASE/v1/proj-1/agents/session-ctx-echo/conversations/$CONV?tenant=t-9&channel=mobile" \
  -H "Content-Type: application/json" \
  -H "X-Trace-Id: trace-e2e" \
  -H "X-User-Id: user-42" \
  -d '{"query":"查余额","stream":false}' 2>/dev/null)
e2e_log "  应答 ${RESP:0:140}"

# ── 一：写入确实发生 ────────────────────────────────────────
AFTER=$(curl -s -m 20 "$BASE/probe-session-context?conversation_id=$CONV" 2>/dev/null)
e2e_log "  落库 ${AFTER:0:300}"
case "$AFTER" in
  *'"found":true'*) e2e_log "  ✅ 该键已写入——写侧在真实请求路径上生效" ;;
  *) e2e_log "  ❌ 该键仍不存在——写侧未接进请求路径"; FAILED=1 ;;
esac

# ── 二：字段集相等（五项，一个不多一个不少）────────────────
FIELDS=$(printf '%s' "$AFTER" | grep -oP '"fields":\s*\K\[[^]]*\]')
EXPECTED='["agent_id","body","headers","params","trace_id"]'
if [ "$FIELDS" = "$EXPECTED" ]; then
  e2e_log "  ✅ 字段集与存量相等：$FIELDS"
else
  e2e_log "  ❌ 字段集为 ${FIELDS:-空}，期望 $EXPECTED"
  e2e_log "     少 agent_id 会让存量的续轮恢复读到空"
  FAILED=1
fi

# ── 三：内容真的落进去了（不是空壳）────────────────────────
case "$AFTER" in
  *'trace-e2e'*) e2e_log "  ✅ 请求头已落库（含 X-Trace-Id）" ;;
  *) e2e_log "  ❌ 请求头未落库——写了空壳等于没写"; FAILED=1 ;;
esac
case "$AFTER" in
  *'查余额'*) e2e_log "  ✅ 请求体已落库" ;;
  *) e2e_log "  ❌ 请求体未落库"; FAILED=1 ;;
esac
case "$AFTER" in
  *'"tenant"'*) e2e_log "  ✅ 查询参数已落库" ;;
  *) e2e_log "  ❌ 查询参数未落库"; FAILED=1 ;;
esac
TTL=$(printf '%s' "$AFTER" | grep -oP '"ttl_s":\s*\K-?[0-9]+')
if [ "${TTL:-0}" = "1800" ]; then
  e2e_log "  ✅ 生存期 ${TTL}s，与存量一致"
else
  e2e_log "  ❌ 生存期为 ${TTL:-未取到}，期望 1800"; FAILED=1
fi

# ── 四：南向真的读得到（本项要修的后果本身）────────────────
e2e_log "经南向出站件的取数路径读会话上下文"
SB=$(curl -s -m 20 "$BASE/probe-southbound-context?conversation_id=$CONV" 2>/dev/null)
e2e_log "  南向 ${SB:0:300}"
case "$SB" in
  *'"headers_empty":false'*) e2e_log "  ✅ 南向拿到非空请求头——链路闭合" ;;
  *) e2e_log "  ❌ 南向请求头为空——这正是本项要修的缺口，未修复"; FAILED=1 ;;
esac
case "$SB" in
  *'"body_empty":false'*) e2e_log "  ✅ 南向拿到非空请求体" ;;
  *) e2e_log "  ❌ 南向请求体为空"; FAILED=1 ;;
esac

# ── 五：第二次请求不覆写（读到即不写）──────────────────────
e2e_log "同一会话再发一次请求，内容不同——应不覆写"
curl -s -m 30 -X POST "$BASE/v1/proj-1/agents/session-ctx-echo/conversations/$CONV" \
  -H "Content-Type: application/json" -H "X-Trace-Id: trace-second" \
  -d '{"query":"第二轮","stream":false}' >/dev/null 2>&1
AGAIN=$(curl -s -m 20 "$BASE/probe-session-context?conversation_id=$CONV" 2>/dev/null)
case "$AGAIN" in
  *'trace-e2e'*) e2e_log "  ✅ 仍是首轮内容——读到即不写生效" ;;
  *) e2e_log "  ❌ 内容被续轮覆写：${AGAIN:0:200}"; FAILED=1 ;;
esac

[ "$FAILED" = "0" ] && { e2e_log "✅ 入站会话上下文写侧在真实环境下闭合"; exit 0; }
e2e_log "❌ 会话上下文写侧验证未通过"
exit 1
