#!/usr/bin/env bash
# 容器级部署 E2E（FEAT-002 Versatile 远端服务代理）：
# build → run → POST 自定义 REST → 我的 runtime → VersatileAgentHandler
# → 真 socket HTTP POST → 容器内 mock Versatile 平台 → 行帧流 → 帧翻译 → 对外信封。
# 确定性（无 LLM）。exit 0=过 / 1=不过。
set -uo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_backend.sh"

log() { echo "[e2e-versatile] $*"; }

e2e_start "e2e_versatile_server" 18091
e2e_wait_health 60

# ── 以下与后端无关：真 socket 上的 HTTP 往返与断言 ──────────────────

log "4/5 POST → runtime → Versatile 代理 → mock 平台 → 归一"
RESP=$(curl -s -m 30 "$BASE/v1/proj/agents/versatile-e2e/conversations/conv-v1" \
  -H "Content-Type: application/json" \
  -d '{"input":{"query":"帮我查一下余额"},"stream":false}')
log "   响应: $RESP"

echo "$RESP" | python3 -c "
import sys, json
EXPECTED = '您的账户余额为 6312.58 元'
raw = sys.stdin.read()
d = json.loads(raw)
blob = json.dumps(d, ensure_ascii=False)

# ① 远端业务结果经完整链路归一后原样到达（含中文与小数，验编码与透传）
assert EXPECTED in blob, f'远端结果未归一到对外信封: {blob[:400]}'

# ② 非流式聚合只取终答，故字段行不应出现在这里（它们是增量帧，不是终答）
for noise in ('event: message', 'id: 1'):
    assert noise not in blob, f'非流式响应混入了非终答帧: {noise!r} in {blob[:400]}'

# ③ 非流式聚合只取终答：中间增量帧不应出现在非流式响应里
assert '正在查询' not in blob, f'非流式响应混入了中间增量帧: {blob[:400]}'

print('[e2e-versatile] ✅ 非流式三项断言全过：终答归一 / 非终答帧未混入 / 只取终答')
" || { log "❌ 非流式断言失败"; e2e_diag; exit 1; }

log "5/5 流式路径：验增量帧序列（空行不截流、SSE 字段行不泄漏、终答在最后）"
STREAM=$(curl -s -m 30 -N "$BASE/v1/proj/agents/versatile-e2e/conversations/conv-v2" \
  -H "Content-Type: application/json" \
  -d '{"input":{"query":"帮我查一下余额"},"stream":true}')
log "   流式响应前 300 字: ${STREAM:0:300}"

echo "$STREAM" | python3 -c "
import sys
EXPECTED = '您的账户余额为 6312.58 元'
raw = sys.stdin.read()

# ① 空行未截流：空行前后的两帧内容都到达了
assert '正在查询' in raw, f'空行前的增量帧丢失: {raw[:500]}'
assert EXPECTED in raw, f'终答未到达流式响应: {raw[:500]}'

# ② 顺序：增量在前、终答在后
assert raw.index('正在查询') < raw.index(EXPECTED), '帧序错乱：终答早于中间增量'

# ③ 非 JSON 行**必须**作为原始数据帧透传到对外流 —— 与存量一致。
#    存量的帧处理默认分支对所有不匹配已知模式的行都返回原样承载该行的数据事件，
#    前端可能已依赖它们的到达。对标的行为相反（丢弃），但对外兼容是六原则之首。
for passthrough in ('event: message', 'id: 1'):
    assert passthrough in raw, f'非 JSON 行未透传到对外流（与存量不一致）: {passthrough!r}'

print('[e2e-versatile] ✅ 流式三项断言全过：空行不截流 / 帧序正确 / 非 JSON 行按存量透传')
" || { log "❌ 流式断言失败"; e2e_diag; exit 1; }

log "✅ 容器级 FEAT-002 Versatile 远端代理 E2E 通过"
exit 0
