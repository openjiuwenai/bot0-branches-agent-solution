#!/usr/bin/env bash
# Task 快照数据库档在**真实启动路径**上的验证（Feat-Func-003b §11.2、§12.1 判据九～十一）。
#
# ## 为什么进程内判据不够
#
# 进程内判据直接调 `build_a2a_task_store` 与 `build_a2a_stores_with_init`，验的是它们的逻辑。
# 真实启动多覆盖两样：
#
# | 多出的 | 为什么进程内看不到 |
# |---|---|
# | 配置不全时**进程真的起不来** | 进程内只看到抛异常，看不到「端口没人监听」 |
# | 多了这一档之后**默认部署照旧起得来** | 进程内不建应用，装配根多一个参数是否把默认路径打坏，进程内不判 |
#
# **本项的实证**：存量在此处静默当作 sqlite、路径也空，失败发生在更靠后的地方且不含配置项名。
# 本版有意偏离，把失败提前到装配期——「提前到装配期」这件事只有真实启动路径能验。
#
# ## 判得了的两段与判不了的那一段
#
# 本脚本只含**不依赖任何外部件**的两段。真 sqlite 往返要 `openjiuwen_runtime`
# （存量基础包，提供数据库处理器实现），本机与 CI runner 都没有——它拆在
# `run-task-db-sqlite.sh`，并在 CI 的 `E2E_NOT_APPLICABLE` 里声明前置与理由。
#
# **为什么必须拆**：一条脚本 = 一个受判维度。把判不了的那段留在这里，
# 整条脚本退 3，`deploy-e2e` 整道随之「未判」，CI 判定为失败——实测打红过一次。
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/_backend.sh"

PORT="${TASK_DB_PORT:-18099}"
FAILED=0
WORK="$HERE/.e2e-logs"
mkdir -p "$WORK"

_pid=$(ss -lptn "sport = :$PORT" 2>/dev/null | grep -oP 'pid=\K[0-9]+' | head -1)
[ -n "$_pid" ] && { e2e_log "清理端口 $PORT 的残留进程（pid $_pid）"; kill -9 "$_pid" 2>/dev/null; sleep 1; }

export E2E_PASS_ENV="E2E_TASK_DB OPENJIUWEN__SERVICE__CONFIG_FILE"

# 自行轮询就绪，**不用 e2e_wait_health**：第一段恰恰期望服务起不来，
# 那个函数在未就绪时会直接终止脚本。
poll_ready() {
  local port="$1" timeout="$2" i
  for i in $(seq 1 "$timeout"); do
    curl -sf -m 2 "http://127.0.0.1:$port/health" >/dev/null 2>&1 && return 0
    sleep 1
  done
  return 1
}

_cache_section() {
  cat <<'YAML'
openjiuwen:
  service:
    middleware:
      endpoint_type: standalone
      standalone:
        host: 127.0.0.1
        port: 6379
        database: 0
        timeout_ms: 3000
YAML
}

# ── 一：开了这一档却没配类型 → 进程起不来，且日志指名配置项 ────────────
INCOMPLETE="$WORK/task-db-incomplete.yaml"
{ _cache_section; cat <<'YAML'
    runtime_db:
      runtime_db_enabled: true
YAML
} > "$INCOMPLETE"

e2e_log "一：runtime_db_enabled=true 但未配 runtime_db_type，服务应起不来"
LOG_A="$WORK/task-db-incomplete.log"
( cd "$HERE" && E2E_TASK_DB=1 OPENJIUWEN__SERVICE__CONFIG_FILE="$INCOMPLETE" \
    "$(_e2e_python)" -m uvicorn e2e_a2a_server:app \
    --port "$PORT" --log-level warning > "$LOG_A" 2>&1 ) &
PID_A=$!
if poll_ready "$PORT" 12; then
  e2e_log "  ❌ 服务竟然起来了——装配期校验没生效，配置错误被带进了运行期"
  FAILED=1
else
  e2e_log "  ✅ 服务未就绪——装配期失败生效"
fi
kill -9 "$PID_A" 2>/dev/null
_pid=$(ss -lptn "sport = :$PORT" 2>/dev/null | grep -oP 'pid=\K[0-9]+' | head -1)
[ -n "$_pid" ] && kill -9 "$_pid" 2>/dev/null
sleep 1

# **错误信息必须指名到配置项**：只判「起不来」的话，任何一种启动失败都算通过。
if grep -q "runtime_db_type" "$LOG_A"; then
  e2e_log "  ✅ 失败信息指名 runtime_db_type"
else
  e2e_log "  ❌ 失败信息里没有 runtime_db_type——起不来的原因可能与本项无关"
  e2e_log "     日志尾部：$(tail -3 "$LOG_A" | tr '\n' ' ')"
  FAILED=1
fi

# ── 二：不配这一档 → 默认部署照旧起得来并服务请求 ──────────────────────
DEFAULT_CFG="$WORK/task-db-default.yaml"
_cache_section > "$DEFAULT_CFG"

e2e_log "二：不配 runtime_db，默认部署应照旧起得来"
LOG_B="$WORK/task-db-default.log"
( cd "$HERE" && E2E_TASK_DB=1 OPENJIUWEN__SERVICE__CONFIG_FILE="$DEFAULT_CFG" \
    "$(_e2e_python)" -m uvicorn e2e_a2a_server:app \
    --port "$PORT" --log-level warning > "$LOG_B" 2>&1 ) &
PID_B=$!
if poll_ready "$PORT" 25; then
  e2e_log "  ✅ 服务就绪——多这一档没有打坏默认路径"
  CARD=$(curl -sf -m 5 "http://127.0.0.1:$PORT/.well-known/agent-card.json" 2>/dev/null)
  if [ -n "$CARD" ]; then
    e2e_log "  ✅ 卡片端点可达——标准协议面照常挂载"
  else
    e2e_log "  ❌ 卡片端点不可达"
    FAILED=1
  fi
else
  e2e_log "  ❌ 服务未起来——默认路径被这一档的改动打坏了"
  e2e_log "     日志尾部：$(tail -3 "$LOG_B" | tr '\n' ' ')"
  FAILED=1
fi
kill -9 "$PID_B" 2>/dev/null
_pid=$(ss -lptn "sport = :$PORT" 2>/dev/null | grep -oP 'pid=\K[0-9]+' | head -1)
[ -n "$_pid" ] && kill -9 "$_pid" 2>/dev/null

# 真 sqlite 往返**不在本脚本里**：它要存量基础包 openjiuwen_runtime，本机与 CI runner 都没有。
# 放在这里会让整条脚本退 3，把 deploy-e2e 整道拖成「未判」——实测把 CI 打红过一次。
# 独立成 run-task-db-sqlite.sh，并在 CI 的 E2E_NOT_APPLICABLE 里声明前置与理由。
# **本脚本只判它判得了的两段**，两段都不依赖任何外部件。

[ "$FAILED" -eq 0 ] && e2e_log "✅ Task 快照数据库档：装配期校验与默认路径两段通过" || e2e_log "❌ 存在失败项"
exit "$FAILED"
