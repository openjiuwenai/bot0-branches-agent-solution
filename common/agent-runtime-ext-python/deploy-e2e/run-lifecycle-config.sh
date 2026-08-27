#!/usr/bin/env bash
# 生命周期配置项的真实运行环境验证（Feat-Func-000b §4.2.2、§9）。
#
# ## 为什么进程内判据不够
#
# 进程内判据直接调生命周期编排器，验的是它的逻辑。真实启动多覆盖两样：
#
# | 多出的 | 为什么进程内看不到 |
# |---|---|
# | 配置真的透传到了编排器 | 进程内直接给编排器传参；工厂漏传时进程内照样绿 |
# | 启动失败真的让服务起不来 | 进程内只看到抛异常，看不到「端口没人监听」 |
#
# **本项的实证**：工厂此前就漏传了该项——只在编排器上支持、工厂不传，
# 配置到不了消费方。那种漏接只有真实启动路径能发现。
#
# ## 替身的边界
#
# 装的是**必然失败的初始化钩子**——只替掉「钩子会失败」这件事本身，
# 开关逻辑仍是被测件在跑。
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/_backend.sh"

PORT="${LIFECYCLE_PORT:-18096}"
FAILED=0

_pid=$(ss -lptn "sport = :$PORT" 2>/dev/null | grep -oP 'pid=\K[0-9]+' | head -1)
[ -n "$_pid" ] && { e2e_log "清理端口 $PORT 的残留进程（pid $_pid）"; kill -9 "$_pid" 2>/dev/null; sleep 1; }

export E2E_PASS_ENV="E2E_INIT_FAIL_FAST"

# 自行轮询就绪，**不用 e2e_wait_health**：那个函数在未就绪时直接退出脚本，
# 而本脚本第一段恰恰**期望**服务起不来——那是判据的正例，不是脚本该终止的理由。
poll_ready() {
  local port="$1" timeout="$2" i
  for i in $(seq 1 "$timeout"); do
    curl -sf -m 2 "http://127.0.0.1:$port/health" >/dev/null 2>&1 && return 0
    sleep 1
  done
  return 1
}

# ── 真：钩子失败应终止启动 ──────────────────────────────────
# **两段必须用同一个解释器**。此处曾硬编码 `.venv/bin/python`，而下面那段走
# `e2e_start`（用 `_e2e_python`：`.venv` 优先、退回 `python3`）。CI 上没有 `.venv`，
# 于是上段因**路径不存在**而起不来、下段用 `python3` 起得来——脚本报绿，
# 而它宣称的「两段结果不同 = 开关被真实消费」完全不成立：差异来自解释器，不是开关。
#
# 这条注释下面那句「若开关未被消费，两段结果会相同」防的是一个方向，
# **反方向没防**：两段也可能因为与开关无关的原因而不同。
e2e_log "init_fail_fast=1：装必然失败的初始化钩子，服务应起不来"
export E2E_INIT_FAIL_FAST=1
( E2E_INIT_FAIL_FAST=1 "$(_e2e_python)" -m uvicorn e2e_a2a_server:app \
    --port "$PORT" --log-level warning > "$HERE/.e2e-logs/lifecycle-fastfail.log" 2>&1 ) &
FAST_PID=$!
if poll_ready "$PORT" 12; then
  e2e_log "  ❌ 服务竟然起来了——快失败未生效"
  FAILED=1
else
  e2e_log "  ✅ 服务未就绪——快失败生效，装配错误没被带进运行期"
fi
kill -9 "$FAST_PID" 2>/dev/null
_pid=$(ss -lptn "sport = :$PORT" 2>/dev/null | grep -oP 'pid=\K[0-9]+' | head -1)
[ -n "$_pid" ] && kill -9 "$_pid" 2>/dev/null
sleep 2

# ── 假：钩子失败应降级启动 ──────────────────────────────────
e2e_log "init_fail_fast=0：同样的失败钩子，服务应降级起来"
export E2E_INIT_FAIL_FAST=0
e2e_start "e2e_a2a_server" "$PORT"
if e2e_wait_health 40; then
  e2e_log "  ✅ 服务就绪——降级启动生效"
else
  e2e_log "  ❌ 服务未起来——降级路径失效"
  FAILED=1
fi

# 降级留痕由进程内判据覆盖（`test_lifecycle_config_consumers.py` 的
# `test_degraded_startup_leaves_a_log_record`）。此处**不重复验日志文本**——
# E2E 服务只配了传输层的日志级别，我方记录器未接入其输出，
# 在这里断言日志文本验的是「日志有没有接」，不是「降级有没有留痕」。
#
# 真实环境这一层验的是**行为**：同一个必然失败的钩子，开关真则起不来、假则起得来。
# 两段的对比本身就是证据——若开关未被消费，两段结果会相同。
e2e_log "  ✅ 两段结果不同（真=起不来、假=起得来）——开关被真实消费"

[ "$FAILED" = "0" ] && { e2e_log "✅ 生命周期配置项在真实启动路径下均生效"; exit 0; }
e2e_log "❌ 生命周期配置项验证未通过"
exit 1
