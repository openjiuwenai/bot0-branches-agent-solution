#!/usr/bin/env bash
# 对外端点全量实测（Feat-Func-001b §2.3.1）：详设声称的每条对外端点，起真服务逐个请求。
#
# ## 为什么需要这一道
#
# 本项目实证过一次：站点根的两条卡片端点，设计写了、验收矩阵列了、判据描述也提到了，
# 但实现从未注册。该缺陷穿过五道检查——
#
# | 检查 | 为什么没抓到 |
# |---|---|
# | 设计完备性 | 设计写了，且写得比多数条款细 |
# | 三态一致审计 | 判据存在，描述与条款对得上 |
# | 全量判据 | 判据实际请求的是另一条路径，恒绿 |
# | 对外兼容差分 | 存量也没有这两条，两侧一致，判通过 |
# | 部署级往返 | 我方作为调用方按序试候选路径，第一条即命中 |
#
# 五道都是**从内部视角出发**的。只有把每条对外路径真的请求一遍，才会看见 404。
#
# 本脚本把那个手工动作固化下来。**清单从详设正文提取，不手写**——手写的清单
# 会随详设新增端点而过期，而过期时没有任何东西报错。
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
PORT="${ENDPOINT_INVENTORY_PORT:-18790}"
PY="${ENDPOINT_INVENTORY_PY:-}"
BASE="http://127.0.0.1:$PORT"

# shellcheck source=/dev/null
[ -f "$HERE/_backend.sh" ] && source "$HERE/_backend.sh" 2>/dev/null || true

log() { printf '%s\n' "$*"; }

# **解释器定位交给 `_e2e_python`**（`.venv` 优先、退回 `python3`），
# 不硬编码 `.venv/bin/python`。CI 用 `actions/setup-python` 加 `pip install`，
# runner 上没有 `.venv`。
[ -n "$PY" ] || PY="$(_e2e_python 2>/dev/null || echo python3)"

# **找不到解释器退 3（未判），不是 0**。
#
# 此处原写 `exit 0`——CI 上 `.venv` 不存在，这条 E2E **从来没真跑过，却一直报绿**。
# 「跳过」在本仓的退出码约定里是 3，0 的含义是「判过且通过」。
# 把未判写成通过，等于宣布这一维在 CI 上永远不用跑，而没有任何东西会提醒。
command -v "$PY" >/dev/null 2>&1 || [ -x "$PY" ] || {
    log "未判：解释器不可用（$PY）。**未判不等于通过**——装好依赖再跑。"
    exit 3
}

# ── 从详设提取端点清单 ────────────────────────────────────────────
# 只取「反引号包裹的 方法 + 空格 + 斜杠开头路径」这一形态，与详设的端点表写法一致。
# 含占位符的路径单独处理——它们是模板，须代入具体值才能请求。
mapfile -t DECLARED < <(
  grep -oh '`\(GET\|POST\) /[^`]*`' "$ROOT"/internal/develop/03-architecture/L2-Low-Level-Design/agent-runtime/*.md 2>/dev/null \
    | tr -d '`' | sort -u
)
# **提取不到声明退 3，不是 0**：考核面为空即报绿是最廉价的逃逸——
# 详设换个写法、提取正则失配，这条 E2E 就永远绿，而没有任何东西提醒。
[ "${#DECLARED[@]}" -gt 0 ] || {
    log "未判：没从详设提取到任何端点声明。**未判不等于通过**——"
    log "      要么详设的端点表写法变了、提取失配，要么这份详设确实没有端点。"
    exit 3
}
log "从详设提取到 ${#DECLARED[@]} 条端点声明"

# ── 起服务 ────────────────────────────────────────────────────────
fuser -k -n tcp "$PORT" 2>/dev/null
sleep 1
mkdir -p "$HERE/.e2e-logs"
( cd "$ROOT" && PYTHONPATH=deploy RUNTIME_PORT="$PORT" \
    "$PY" -m uvicorn host_app:app --port "$PORT" --log-level warning \
    > "$HERE/.e2e-logs/endpoint-inventory.log" 2>&1 ) &
SRV=$!
cleanup() { kill "$SRV" 2>/dev/null; fuser -k -n tcp "$PORT" 2>/dev/null; }
trap cleanup EXIT

for _ in $(seq 1 40); do
  sleep 0.5
  [ "$(curl -s -o /dev/null -m 2 -w '%{http_code}' "$BASE/health")" = "200" ] && break
done
# **服务未就绪退 3，不是 0**：起不来时这一维一条都没验，报绿等于宣布它不用跑。
[ "$(curl -s -o /dev/null -m 2 -w '%{http_code}' "$BASE/health")" = "200" ] \
  || {
    log "未判：服务未就绪。**未判不等于通过**——下面是最后 5 行日志。"
    tail -5 "$HERE/.e2e-logs/endpoint-inventory.log"
    exit 3
  }

# ── 逐条请求 ──────────────────────────────────────────────────────
# 占位符代入具体值。**代入表须与详设的路径模板同步**——模板改了这里不改，
# 表现是该端点被跳过而非报错。
substitute() {
  printf '%s' "$1" \
    | sed -e 's|{project_id}|proj|g' -e 's|{项目}|proj|g' -e 's|{项目标识}|proj|g' \
          -e 's|{agent_id}|mobile_bank_agent|g' -e 's|{Agent}|mobile_bank_agent|g' \
          -e 's|{智能体标识}|mobile_bank_agent|g' \
          -e 's|{conversation_id}|inv-1|g' -e 's|{会话}|inv-1|g' -e 's|{会话标识}|inv-1|g' \
          -e 's|{通道路径}|proj/agents/mobile_bank_agent/conversations/inv-1|g' \
          -e 's|{channel_path}|proj/agents/mobile_bank_agent/conversations/inv-1|g'
}
# **`{通道路径}` 必须代入**：它展开后是自定义 REST 的主入口与取消端点——
# 本仓最主要的两条对外出口。此前它们落在「跳过（模板未代入）」里，
# 而本脚本自己写着「**这不是通过**」：跳过等于这两条的可达性没被任何东西验证过。
# 代入值取存量默认路由的形态（与 `run-parity.sh` 的 `$ROUTE` 同构）。

FAILED=0
SKIPPED=0
for decl in "${DECLARED[@]}"; do
  method="${decl%% *}"
  path="${decl#* }"
  concrete="$(substitute "$path")"
  # 仍含占位符的是无法代入的模板（如通用的「通道路径」），跳过并计数
  case "$concrete" in
    *"{"*)
      log "  跳过（模板未代入）  $method $path"
      SKIPPED=$((SKIPPED + 1))
      continue
      ;;
  esac
  code=$(curl -s -o /dev/null -m 8 -w '%{http_code}' -X "$method" "$BASE$concrete" \
           -H 'Content-Type: application/json' -H 'A2A-Version: 1.0' \
           --data '{"jsonrpc":"2.0","id":"1","method":"SendMessage","params":{"message":{"messageId":"m","contextId":"inv","role":"ROLE_USER","parts":[{"text":"x"}]}}}')
  # 404 表示端点不存在 —— 详设声称有而实际没有，正是本脚本要抓的。
  # 其余状态码（含 4xx 业务错误）都说明端点存在且被路由到了。
  if [ "$code" = "404" ]; then
    log "  不存在 404        $method $concrete"
    FAILED=$((FAILED + 1))
  else
    log "  存在   $code        $method $concrete"
  fi
done

log ""
if [ "$SKIPPED" -gt 0 ]; then
  log "$SKIPPED 条为无法代入的路径模板，未请求。**这不是通过**——"
  log "它们的可达性本脚本没有验证，须由对应特性的判据覆盖。"
fi
if [ "$FAILED" -gt 0 ]; then
  log "失败：$FAILED 条详设声称的端点实际不存在。"
  log "设计声称与实现不符，二者必须对齐：补实现，或订正详设并说明为何不提供。"
  exit 1
fi
log "通过：详设声称的端点全部可达。"
