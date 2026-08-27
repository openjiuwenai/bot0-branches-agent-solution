#!/usr/bin/env bash
# 部署级 E2E（Feat-Func-004b §4.8 异步回调回灌的真实往返）：
# 起服务 → POST /drive-callback（内部由假远端**真的 POST** 回调到本地接收端点）
# → 断言五步链路：登记 → 真实 HTTP 往返 → 鉴权 → 判重 → 认领落定 → 重投幂等。
# exit 0=过 / 1=不过。确定性（无 LLM、无外部依赖）。
set -uo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_backend.sh"

log() { echo "[e2e-callback] $*"; }

e2e_start "e2e_callback_server" 18096
e2e_wait_health 60

log "驱动一次完整往返：批次登记 → 假远端真 POST 回调 → 认领 → 重投"
R=$(curl -s -m 60 -X POST "$BASE/drive-callback")
log "   响应: $R"

echo "$R" | python3 -c "
import sys, json
d = json.load(sys.stdin)

# 一 · 登记：远端受理即写进批次快照（§3.4.3）
assert d.get('registered') is True, f'成员未登记进批次快照，回调将永远匹配不到: {d}'
assert d.get('settled_before') is False, f'登记时不该已落定: {d}'

# 二 · 真实 HTTP 往返：路由存在、鉴权过、接收器受理
assert d.get('first_status') == 200, f'回调接收端点未返回 200（路由或鉴权问题）: {d.get(\"first_status\")}'
first = d.get('first_body') or {}
assert first.get('status') == 'accepted', f'首次回调未被受理: {first}'
assert first.get('backfilled') is True, f'首次回调未回灌: {first}'

# 三 · 认领落定：成员经真实回调被推进
assert d.get('settled_after') is True, f'回调到达后成员未落定，结算判定不会通过: {d}'

# 四 · 重投幂等：同一通知标识判重复，不重复回灌
assert d.get('second_status') == 200, f'重投应返回 200: {d.get(\"second_status\")}'
second = d.get('second_body') or {}
assert second.get('status') == 'duplicate', f'重投未被判重复（幂等失效，重试会重复回灌）: {second}'

print('[e2e-callback] ✅ 真实往返：批次登记 → HTTP POST 回调 → 鉴权 → 判重 → 认领落定 → 重投幂等')
" || { log "❌ 回调往返断言失败"; e2e_diag; exit 1; }

log "✅ 部署级 Feat-Func-004b §4.8 异步回调回灌往返 E2E 通过"
exit 0
