#!/usr/bin/env bash
# 部署级 E2E：标准 A2A northbound 四项对外断言（容器 + 真 socket + 真 a2a-sdk）
#
# 为什么要这一支：既有 E2E 变体挂的都是自定义 REST 面，标准 A2A 面在容器里从未被挂起来过
# ——这才是 L2-overview §8.2「四项断言主题在 deploy-e2e 下逐项零命中」的根因。
# 本脚本用 e2e_a2a_server 变体把 /a2a 挂进容器，逐项断言：
#   ① Agent Card 端点   ② 三个 JSON-RPC method 被分发   ③ 错误码 -32700/-32601
#   ④ SSE 帧形态（正常帧仅 data:，无 event: 行；含 error 的帧才附 event: error）
#
# 与进程内线级测试（test_a2a_northbound_wire_contract.py）的分工：那组同进程无真 socket，
# 锁装配与 SDK 交互后的表面；本脚本过容器网络与真 socket，锁的是部署形态下的对外表面。
#
# 用法：deploy-e2e/run-a2a-northbound.sh    exit 0=过 / 1=不过
set -uo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_backend.sh"

log() { echo "[a2a-e2e] $*"; }

FAIL=0   # 累计断言失败数；chk/chk_not 命中失败即置位，末尾据此定退出码

chk() {  # chk <名称> <条件描述> <实测> <期望子串>
  if printf '%s' "$3" | grep -qF -- "$4"; then
    log "  [OK  ] $1"
  else
    log "  [FAIL] $1 —— 期望含「$4」，实得：$(printf '%s' "$3" | head -c 220)"
    FAIL=1
  fi
}

chk_not() {
  if printf '%s' "$3" | grep -qF -- "$4"; then
    log "  [FAIL] $1 —— 不应含「$4」，实得：$(printf '%s' "$3" | head -c 220)"
    FAIL=1
  else
    log "  [OK  ] $1"
  fi
}

e2e_start "e2e_a2a_server" 18092
e2e_wait_health 60

# ── 以下与后端无关：真 socket 上的 HTTP 往返与断言 ──────────────────
log "   health OK"

log "4/4 四项对外断言（真 socket）"

# ① Agent Card 端点
CARD=$(curl -s -m 10 "$BASE/a2a/.well-known/agent-card.json")
chk "① Card 端点 /a2a/.well-known/agent-card.json（存量唯一端点）返回 Card" "" "$CARD" '"name"'

RPC() { curl -s -m 20 "$BASE/a2a/" -H 'Content-Type: application/json' -H 'A2A-Version: 1.0' -d "$1"; }

# ② 三个 method 被分发（可业务失败，但不得 -32601）
SEND='{"jsonrpc":"2.0","id":"1","method":"SendMessage","params":{"message":{"messageId":"m1","contextId":"c1","role":"ROLE_USER","parts":[{"text":"hi"}]}}}'
chk_not "② SendMessage 被分发（非 -32601）" "" "$(RPC "$SEND")" '-32601'
GET='{"jsonrpc":"2.0","id":"2","method":"GetTask","params":{"name":"tasks/no-such-task"}}'
chk_not "② GetTask 被分发（非 -32601）" "" "$(RPC "$GET")" '-32601'

# ③ 错误码
BADJSON=$(curl -s -m 10 "$BASE/a2a/" -H 'Content-Type: application/json' -H 'A2A-Version: 1.0' --data-binary '{not json')
chk "③ 非法 JSON → -32700" "" "$BADJSON" '-32700'
UNK='{"jsonrpc":"2.0","id":"3","method":"no/such/method","params":{}}'
UNKR=$(RPC "$UNK")
chk "③ 未知 method → -32601" "" "$UNKR" '-32601'
chk "③ 错误走 JSON-RPC 信封（回带请求 id）" "" "$UNKR" '"id"'

# ④ SSE 帧形态
STREAM='{"jsonrpc":"2.0","id":"4","method":"SendStreamingMessage","params":{"message":{"messageId":"m2","contextId":"c2","role":"ROLE_USER","parts":[{"text":"hi"}]}}}'
HDRS=$(curl -s -m 20 -D - -o /dev/null "$BASE/a2a/" -H 'Content-Type: application/json' -H 'A2A-Version: 1.0' -d "$STREAM")
chk "④ 流式响应 Content-Type 为 text/event-stream" "" "$HDRS" 'text/event-stream'
BODY=$(curl -s -m 20 -N "$BASE/a2a/" -H 'Content-Type: application/json' -H 'A2A-Version: 1.0' -d "$STREAM")
chk "④ SSE 帧含 data: 行" "" "$BODY" 'data:'
EVENTS=$(printf '%s' "$BODY" | grep '^event:' || true)
if [ -z "$EVENTS" ]; then
  log "  [OK  ] ④ 正常流无 event: 行（SDK 仅对含 error 的项附 event: error）"
else
  log "  [FAIL] ④ 正常流出现 event: 行：$(printf '%s' "$EVENTS" | head -3 | tr '\n' ' ')"
  FAIL=1
fi
chk "④ data 载荷是 JSON-RPC 响应体" "" "$BODY" '"result"'

if [ "$FAIL" -eq 0 ]; then
  log "✅ 标准 A2A northbound 四项断言全过（容器 + 真 socket + 真 a2a-sdk）"
else
  log "❌ 有断言未过"
  e2e_diag
fi
exit "$FAIL"
