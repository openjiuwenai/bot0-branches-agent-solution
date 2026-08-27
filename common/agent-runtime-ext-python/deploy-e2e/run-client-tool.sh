#!/usr/bin/env bash
# 容器级部署 E2E（FEAT-009 端侧工具 wire 契约，确定性无 LLM）：build → run（host 网络）→
# 请求1(流式,投影挂起) + 请求2(同会话,提交 outcome 续接) → 断言 → teardown。exit 0=过 / 1=不过。
set -uo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_backend.sh"

log() { echo "[e2e-client] $*"; }

e2e_start "e2e_client_tool_server" 18093
e2e_wait_health 60

# ── 以下与后端无关：真 socket 上的 HTTP 往返与断言 ──────────────────

CONV="conv-ct-$$"
AGENT_PATH="$BASE/v1/proj/agents/edp_client_tool_e2e/conversations/$CONV"

log "4/4 请求1（流式）→ 组件投影端侧工具请求 → interrupt_start 帧携带 {client_tool,args,call_id}"
R1=$(curl -s -m 60 -N "$AGENT_PATH" -H "Content-Type: application/json" \
  -d '{"input":{"query":"我要看账单"},"stream":true}')
if ! echo "$R1" | grep -q "interrupt_start" || ! echo "$R1" | grep -q "read_file"; then
  log "❌ 请求1 未见携带端侧工具投影的 interrupt 帧"; echo "$R1" | head -5; e2e_diag; exit 1
fi
log "   ✅ 请求1 见 interrupt_start + 投影(read_file)——client 据此知道要执行的工具"

log "   请求2（同会话）→ client 把工具执行结果作为 outcome 提交 → REST 续接 → from_raw 归一 → 完成"
R2=$(curl -s -m 60 "$AGENT_PATH" -H "Content-Type: application/json" \
  -d '{"input":{"query":"账单：本月消费 1280.00 元"},"stream":false}')
log "   响应: $R2"
echo "$R2" | python3 -c "
import sys, json
d = json.load(sys.stdin)
assert d.get('success') is True, f'success!=True: {d}'
ans = d.get('answer') or ''
assert '1280.00' in ans, f'端侧工具 outcome 未回灌进最终输出: {d}'
print('[e2e-client] ✅ 容器内端侧工具 wire 契约：投影上线(HTTP interrupt_start) → outcome 提交 → 续接归一 → 完成')
" || { log "❌ 请求2 续接断言失败"; e2e_diag; exit 1; }

log "✅ 容器级 FEAT-009 端侧工具 wire 契约 E2E 通过"
exit 0
