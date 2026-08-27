#!/usr/bin/env bash
# 容器级部署 E2E（questioner + 真实 LLM 中断续接）：build → run（host 网络+注入 Kimi key）→
# HTTP 请求1(中断)+请求2(续接) → 断言 → teardown。
# exit 0=判过且通过 / 1=不通过 / **3=未判**（无模型凭据）。
# 这一行原写「0(跳过,无 key)」，与下面第 41 行起的代码不符——那里早已改用 3。
# **未判不等于通过**，写成 0 会让缺凭据的环境永远报绿。
# 强制交付门禁的"真 LLM 中断续接"变体（叠在 run.sh 之上）。
set -uo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_backend.sh"

log() { echo "[e2e-llm] $*"; }

# **把模型凭据透传进容器**。
#
# 容器默认只拿得到 `E2E_MODULE`，其余变量要按 `E2E_PASS_ENV` 清单显式列出
# （见 `_backend.sh` 的透传循环）。本脚本此前**没设这个清单**——
# 头部注释逐字写着「注入 Kimi key」，而实际一个变量都没进去，
# 服务在容器里读到空的 `LLM_API_KEY`，启动即失败：
#
#     [181002] model service config error, reason: api_key is required for provider OpenAI
#
# 于是它被判「未判（外部依赖不可用）」——**而真实原因是脚本没透传**，
# 不是环境缺凭据。本机明明配得出凭据，这条却从来没跑成过。
#
# 这是「未判掩盖了真缺陷」的一例：未判看起来是环境问题，人就不再往下查。
export E2E_PASS_ENV="LLM_API_KEY LLM_BASE LLM_MODEL"

e2e_start "e2e_questioner_server" 18095
e2e_wait_health 60

# ── 以下与后端无关：真 socket 上的 HTTP 往返与断言 ──────────────────

CONV="conv-q-$$"
AGENT_PATH="$BASE/v1/proj/agents/edp_questioner_e2e/conversations/$CONV"

log "4/4 请求1（流式）→ questioner 就缺失 account_id 中断（interrupt_start 帧）"
R1=$(curl -s -m 90 -N "$AGENT_PATH" -H "Content-Type: application/json" \
  -d '{"input":{"query":"我要查余额"},"stream":true}')
if ! echo "$R1" | grep -q "interrupt_start"; then
  # 外部 LLM 限流/不可用 → 优雅跳过（不误判为交付失败）；真正的 runtime 问题才 fail。
  if docker logs "$NAME" 2>&1 | grep -qiE "rate_limit|too many requests|failed to invoke llm|APIConnectionError|APITimeout"; then
    log "⏭ 外部 LLM（Kimi）限流/不可达，跳过 questioner+LLM 门禁（非 runtime 失败）"
    # **退出码 3 = 未判**，不是 0。用 0 会被 `tools/deploy_e2e_guard.py`
    # 算成通过，而「没查成」与「查了且通过」是两件事——
    # 后者是交付结论，前者是缺口。
    exit 3
  fi
  log "❌ 请求1 未见中断帧（非 LLM 外部原因）"; echo "$R1" | head -5; e2e_diag; exit 1
fi
log "   ✅ 请求1 见 interrupt_start（真实 LLM 判缺失字段并中断）"

log "   请求2（同会话，阻塞）→ REST 续接路由 → resume_query → 续跑并恢复 account_id → 完成"
R2=$(curl -s -m 90 "$AGENT_PATH" -H "Content-Type: application/json" \
  -d '{"input":{"query":"我的账号是 6217001234567890"},"stream":false}')
log "   响应: $R2"
# 断言：续接路由生效 + 交互态恢复（account_id=6217... 被提取）+ workflow 续跑完成。
echo "$R2" | python3 -c "
import sys, json
d = json.load(sys.stdin)
assert d.get('success') is True, f'success!=True: {d}'
ans = d.get('answer') or ''
assert '6217001234567890' in ans and '已受理' in ans, f'续接未恢复 account_id: {d}'
print('[e2e-llm] ✅ 容器内 questioner+真 LLM：中断（HTTP interrupt_start）→ REST 续接 → 恢复 account_id → 完成')
" || { log "❌ 请求2 续接断言失败"; e2e_diag; exit 1; }

log "✅ 容器级 questioner+真 LLM 中断+续接路由 E2E 通过"
exit 0
