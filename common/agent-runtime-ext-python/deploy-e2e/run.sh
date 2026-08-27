#!/usr/bin/env bash
# 部署级 E2E：起服务（容器或本机进程）→ 真 socket HTTP 端到端断言 → teardown。
# 强制交付门禁：每步交付都跑本脚本，exit 0=过 / 1=不过。
#
# 用法：deploy-e2e/run.sh              （自动选后端：有容器运行时用容器，否则本机进程）
#       E2E_BACKEND=local deploy-e2e/run.sh    （强制本机进程，无需容器运行时）
#
# 覆盖面差异见 _backend.sh 顶部说明：本机后端保住真 socket 往返，
# 但**不覆盖**干净依赖环境与 Dockerfile 本身，那两项仍是欠账。
set -uo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_backend.sh"

e2e_start "e2e_server" 18090
e2e_wait_health 60

# ── 以下与后端无关：真 socket 上的 HTTP 往返与断言 ──────────────────

e2e_log "HTTP 端到端：POST 自定义 REST → 我的 runtime → 真实 agent-core workflow → 信封"
RESP=$(curl -s -m 30 "$BASE/v1/proj/agents/edp_e2e_wf/conversations/conv-c1" \
  -H "Content-Type: application/json" \
  -d '{"input":{"query":"我要查余额"},"stream":false}')
e2e_log "  响应: $RESP"

echo "$RESP" | python3 -c "
import sys, json
d = json.load(sys.stdin)
assert d.get('success') is True, f'success != True: {d}'
assert '查余额' in (d.get('answer') or ''), f'answer 未透传 query: {d}'
print('[e2e] ✅ 断言通过：我的 runtime 端到端驱动真实 agent-core，对外信封正确')
" || { e2e_log "❌ 端到端断言失败"; e2e_diag; exit 1; }

e2e_log "✅ 部署级 E2E 通过"
exit 0
