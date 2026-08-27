#!/usr/bin/env bash
# 标准 A2A 通道的双侧对等比对（真 socket）：同一批 JSON-RPC 请求打存量与本版，比对完整响应。
#
# ## 它补的覆盖缺口
#
# `run-parity.sh` 的双侧比对覆盖的是**自定义 REST 通道**（执行前三关 + SSE 流 +
# 共享键双写）。**标准 A2A 通道两侧从未逐字节比过**——已知那条通道上有一处偏离
# （`TaskStatus.timestamp`），而未知的可能不止一处：没有比对就没有读数。
#
# 该缺口 2026-08-23 登记在 `COMPAT-SURFACE-INVENTORY.md` 的「本清单自身的局限」，
# 本脚本消除它。
#
# ## 两侧的 A2A 入口
#
# | 侧 | 入口 | 装配 |
# |---|---|---|
# | 存量 | `/a2a`（`applications/a2a_service/app.py` 在启动钩子里挂载） | `legacy_boot:app` |
# | 本版 | 挂载前缀由 `create_a2a_app` 决定 | `e2e_a2a_parity_server` |
#
# ## 已知的有意偏离怎么处理
#
# `TaskStatus.timestamp` 是**已裁定的有意偏离**（用户 2026-08-23 两轮裁定，
# 记在 `COMPAT-SURFACE-INVENTORY.md` 的「已裁定的有意偏离」专节）。
# 比对时按字段名显式剔除——否则这条 E2E 一建出来就是红的，而人会把它关掉。
#
# **剔除清单必须极小且逐条有出处**：它是这条 E2E 的信任基础，
# 每加一项剔除都等于放弃一块覆盖面。当前只有一项。
#
# ## 前置
#
# - **Redis**：存量启动即连接。脚本自行起容器；已有实例时设 LEGACY_REDIS_PORT 复用。
# - **存量**：默认取本仓的 `.legacy-oracle/`（`tools/legacy_oracle.sh fetch` 按锚定提交导出），
#   可用 LEGACY_ROOT 覆盖。**不再指向 ../../openJiuwen/agent-runtime-mvp**——那是 2026-08-22
#   裁定作废的历史 fork，Y3 清理漏了这一处，于是本脚本一直退 3、这一维从未真跑过。
#
# exit 0=判过且通过 / 1=不通过 / **3=未判**（存量仓或其虚拟环境不在场）。
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/_backend.sh"

FAILED=0
mkdir -p "$HERE/.e2e-logs"

unjudged() {
  e2e_log "⏭ $1"
  e2e_log "   本次未验证标准 A2A 通道的对等性；要强制在场请置 GATE_REQUIRE_BASELINES=1"
  exit 3
}

LEGACY_ROOT="${LEGACY_ROOT:-$HERE/../.legacy-oracle}"
LEGACY_PORT="${LEGACY_A2A_PORT:-18101}"
MINE_PORT="${MINE_A2A_PORT:-18100}"
REDIS_PORT="${LEGACY_REDIS_PORT:-16379}"
REDIS_NAME="a2a-parity-redis"

[ -d "$LEGACY_ROOT" ] || { unjudged "存量仓不存在（$LEGACY_ROOT）"; }
# **用本仓的解释器跑存量**：`.legacy-oracle/` 是按锚定提交导出的源码树，
# 没有也不该有自己的虚拟环境（导出即删，装一份依赖是多余的）。存量的运行期依赖
# （fastapi、uvicorn、redis、a2a-sdk）本仓都有，且版本由本仓的锁文件固定——
# 两侧同解释器反而消除了「差异来自解释器」这个混淆项，本仓在 `run-lifecycle-config`
# 上踩过它：两路用不同解释器时，那一路因路径不存在而起不来，差异被误读成开关生效。
LEGACY_PY="${LEGACY_PY:-$HERE/../.venv/bin/python}"
[ -x "$LEGACY_PY" ] || { unjudged "解释器不可用（$LEGACY_PY）"; }

for _port in "$LEGACY_PORT" "$MINE_PORT"; do
  _pid=$(ss -lptn "sport = :$_port" 2>/dev/null | grep -oP 'pid=\K[0-9]+' | head -1)
  [ -n "$_pid" ] && { e2e_log "清理残留进程（端口 $_port，pid $_pid）"; kill -9 "$_pid" 2>/dev/null; }
done
sleep 1

REDIS_STARTED=0
if ! (echo > "/dev/tcp/127.0.0.1/$REDIS_PORT") 2>/dev/null; then
  command -v docker >/dev/null 2>&1 || { unjudged "无 Redis 且无容器运行时"; }
  docker run -d --rm --name "$REDIS_NAME" -p "$REDIS_PORT:6379" redis:7-alpine >/dev/null 2>&1 \
    || { unjudged "Redis 容器起不来"; }
  REDIS_STARTED=1
  sleep 2
fi

# **路径面按存量锚定提交的真实结构取**：那个提交的顶层只有 applications／docs／
# foundation／management／scripts／service，**没有 runtime 也没有 server**。
# 上一版按旧 fork 的结构拼了 `$LEGACY_ROOT/runtime` 与 `$LEGACY_ROOT/server`，
# 两个都不存在——即使 LEGACY_ROOT 指对了，导入面也是残的。
LEGACY_PYPATH="$LEGACY_ROOT/foundation:$LEGACY_ROOT/service:$LEGACY_ROOT/applications/a2a_service:$HERE:$HERE/.."

kill_by_port() {
  local pid
  pid=$(ss -lptn "sport = :$1" 2>/dev/null | grep -oP 'pid=\K[0-9]+' | head -1)
  [ -n "$pid" ] && kill -9 "$pid" 2>/dev/null
  return 0
}

cleanup_a2a_parity() {
  # **按端口杀，不只杀记录的 PID**：存量以 `( ... ) &` 起在子 shell 里，
  # 记录到的是外壳的 PID，杀它留下 uvicorn 继续监听——下一次运行会连到
  # 上一次的残留服务，那时源码已经变了而读数来自旧进程。
  [ -n "${LEGACY_PID:-}" ] && kill "$LEGACY_PID" 2>/dev/null
  kill_by_port "$LEGACY_PORT"
  kill_by_port "$MINE_PORT"
  [ "$REDIS_STARTED" = "1" ] && docker rm -f "$REDIS_NAME" >/dev/null 2>&1
  return 0
}
trap cleanup_a2a_parity EXIT

# ── 存量的模型凭据 ─────────────────────────────────────────
# 存量在 lifespan 里构造 ReActAgent，**启动期就校验 provider 配置**，缺 api_key
# 直接抛 `[181002] model service config error` 而整个应用起不来——它不是运行到
# 某次推理才失败的。本比对不发起任何模型调用，但要过这道启动期校验。
#
# 变量名两侧不同：本仓统一用 `LLM_BASE`/`LLM_API_KEY`/`LLM_MODEL`，存量的
# EDPAgent 读的是 `PLANNING_AGENT_MODEL_BASE_URL`/`_API_KEY`/`_NAME`，
# 故在这里做一次映射，而不是要求调用方记两套名字。
if [ -z "${LLM_API_KEY:-}" ] || [ -z "${LLM_BASE:-}" ] || [ -z "${LLM_MODEL:-}" ]; then
  unjudged "缺模型凭据（需 LLM_BASE / LLM_API_KEY / LLM_MODEL 三个都给）：存量启动期校验 provider 配置，缺一起不来"
fi

# ── 起存量 ──────────────────────────────────────────────────
e2e_log "起存量服务（真 socket，端口 $LEGACY_PORT）"
( cd "$LEGACY_ROOT/applications/a2a_service" \
  && PYTHONPATH="$LEGACY_PYPATH" REDIS_HOST=127.0.0.1 REDIS_PORT="$REDIS_PORT" REDIS_DB=0 \
     PARITY_FIXED_EVENTS=1 \
     PLANNING_AGENT_MODEL_BASE_URL="$LLM_BASE" \
     PLANNING_AGENT_MODEL_API_KEY="$LLM_API_KEY" \
     PLANNING_AGENT_MODEL_NAME="$LLM_MODEL" \
     "$LEGACY_PY" -m uvicorn legacy_boot:app --port "$LEGACY_PORT" --log-level warning \
     > "$HERE/.e2e-logs/legacy-a2a.log" 2>&1 ) &
LEGACY_PID=$!

for _ in $(seq 1 40); do
  sleep 1
  [ "$(curl -s -o /dev/null -w '%{http_code}' -m 2 "http://127.0.0.1:$LEGACY_PORT/health")" = "200" ] && break
done
[ "$(curl -s -o /dev/null -w '%{http_code}' -m 2 "http://127.0.0.1:$LEGACY_PORT/health")" = "200" ] \
  || { tail -5 "$HERE/.e2e-logs/legacy-a2a.log" 2>/dev/null; unjudged "存量服务未就绪"; }
e2e_log "  存量就绪"

# 存量的 `/a2a` 在启动钩子里挂，而钩子可能因缺依赖降级——**显式确认它在**，
# 否则后面每一条都比「两侧都 404」，那是**假绿**：形态一致但什么都没验到。
LEGACY_CARD_CODE=$(curl -s -o /dev/null -w '%{http_code}' -m 5 \
  "http://127.0.0.1:$LEGACY_PORT/a2a/.well-known/agent-card.json")
[ "$LEGACY_CARD_CODE" = "200" ] \
  || unjudged "存量的 /a2a 未挂载（卡片端点 $LEGACY_CARD_CODE）——本条比不了"
e2e_log "  存量 /a2a 已挂载"

# ── 起本版 ──────────────────────────────────────────────────
e2e_start "e2e_a2a_parity_server" "$MINE_PORT"
e2e_wait_health 60
e2e_log "  本版就绪"

# ── 比对 ────────────────────────────────────────────────────
# **已裁定的有意偏离逐项剔除**，当前只有一项：
#   `status.timestamp` —— 本版填、存量不填。出处见
#   `COMPAT-SURFACE-INVENTORY.md` 的「已裁定的有意偏离」专节与
#   `L2-host-obligations.md` 的 H-SERVE-10。
normalize() {
  python3 -c "
import json, sys
KNOWN_DEVIATIONS = ('timestamp',)   # 逐项有出处，见本脚本头部

def strip(node):
    if isinstance(node, dict):
        out = {}
        for k, v in node.items():
            if k == 'status' and isinstance(v, dict):
                v = {kk: vv for kk, vv in v.items() if kk not in KNOWN_DEVIATIONS}
            out[k] = strip(v)
        return out
    if isinstance(node, list):
        return [strip(x) for x in node]
    return node

raw = sys.stdin.read()
try:
    print(json.dumps(strip(json.loads(raw)), ensure_ascii=False, sort_keys=True))
except Exception:
    print(raw)   # 不是 JSON 就原样比——错误页、纯文本响应都要比得到
"
}

compare_a2a() {
  local name="$1" path="$2"; shift 2
  local mine legacy mine_code legacy_code
  mine=$(curl -s -m 15 -w $'\n%{http_code}' "$BASE$path" "$@")
  legacy=$(curl -s -m 15 -w $'\n%{http_code}' "http://127.0.0.1:$LEGACY_PORT$path" "$@")
  mine_code="${mine##*$'\n'}"; legacy_code="${legacy##*$'\n'}"
  mine="${mine%$'\n'*}"; legacy="${legacy%$'\n'*}"

  if [ "$mine_code" != "$legacy_code" ]; then
    e2e_log "  ❌ $name 状态码不同：本版 $mine_code、存量 $legacy_code"; FAILED=1; return
  fi
  local mine_n legacy_n
  mine_n=$(printf '%s' "$mine" | normalize)
  legacy_n=$(printf '%s' "$legacy" | normalize)
  if [ "$mine_n" != "$legacy_n" ]; then
    e2e_log "  ❌ $name 响应体不同（已剔除已裁定偏离后仍不同）"
    e2e_log "     本版 ${mine_n:0:200}"
    e2e_log "     存量 ${legacy_n:0:200}"
    FAILED=1; return
  fi
  e2e_log "  ✅ $name（$mine_code，剔除已裁定偏离后一致）"
}

e2e_log "标准 A2A 通道的真 socket 对等比对"

RPC="/a2a/"
JSON=(-X POST -H "Content-Type: application/json")

# 错误面：这几条不依赖智能体的输出，两侧应逐字节一致。
compare_a2a "未知方法" "$RPC" "${JSON[@]}" \
  -d '{"jsonrpc":"2.0","id":"1","method":"no/such/method","params":{}}'
compare_a2a "请求体非合法 JSON" "$RPC" "${JSON[@]}" -d '{bad'
compare_a2a "缺 method 字段" "$RPC" "${JSON[@]}" \
  -d '{"jsonrpc":"2.0","id":"2","params":{}}'
compare_a2a "查询不存在的任务" "$RPC" "${JSON[@]}" \
  -d '{"jsonrpc":"2.0","id":"3","method":"tasks/get","params":{"id":"no-such-task"}}'
compare_a2a "取消不存在的任务" "$RPC" "${JSON[@]}" \
  -d '{"jsonrpc":"2.0","id":"4","method":"tasks/cancel","params":{"id":"no-such-task"}}'

# 卡片面：两侧的卡片由各自配置生成，字段值会不同（名称、版本、端点地址），
# **只比结构**——比值会让这条恒红，而恒红的判据等于没有判据。
e2e_log "卡片端点的结构比对"
card_keys() {
  curl -s -m 10 "$1/a2a/.well-known/agent-card.json" \
    | python3 -c "import json,sys; print(','.join(sorted(json.load(sys.stdin).keys())))" 2>/dev/null
}
# **卡片上另有一处已知差异**，与 `status.timestamp` 同类，逐项剔除：
#   `defaultInputModes` / `defaultOutputModes` —— 本版有、存量没有。
#   成因：存量的 `AgentCardSettings`（`agent_runtime/adapters/inbound/a2a/card.py`）
#   **根本没有这两个字段**，建卡时不传；本版传了，值取自协议库的构造默认
#   （实测 `['text', 'text/plain']`）。
#   两者都是可选字段，多出来不影响按协议实现的客户端。
#   本条 2026-08-24 由本脚本首次实测发现——此前没有任何比对覆盖这条通道。
CARD_KNOWN_DEVIATIONS="defaultInputModes|defaultOutputModes"

MINE_KEYS=$(card_keys "$BASE" | tr ',' '\n' | grep -Ev "^($CARD_KNOWN_DEVIATIONS)$" | paste -sd,)
LEGACY_KEYS=$(card_keys "http://127.0.0.1:$LEGACY_PORT" | tr ',' '\n' | grep -Ev "^($CARD_KNOWN_DEVIATIONS)$" | paste -sd,)
if [ -z "$MINE_KEYS" ] || [ "$MINE_KEYS" != "$LEGACY_KEYS" ]; then
  e2e_log "  ❌ 卡片顶层字段集不同"
  e2e_log "     本版 $MINE_KEYS"
  e2e_log "     存量 $LEGACY_KEYS"
  FAILED=1
else
  e2e_log "  ✅ 卡片顶层字段集一致（$MINE_KEYS；已剔除两项已登记差异）"
fi

[ "$FAILED" = "0" ] || { e2e_log "❌ 标准 A2A 通道对等比对不通过"; e2e_diag; exit 1; }
e2e_log "✅ 标准 A2A 通道的真 socket 对等比对通过"
exit 0
