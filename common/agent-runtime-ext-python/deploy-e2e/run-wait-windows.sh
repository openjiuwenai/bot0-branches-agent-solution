#!/usr/bin/env bash
# 两个等待窗口的真实运行环境验证（Feat-Func-001b §6.2）。
#
# ## 为什么进程内判据不够
#
# 进程内判据直接调包装件的方法，验的是它的逻辑。真实往返多覆盖两样：
#
# | 多出的 | 为什么进程内看不到 |
# |---|---|
# | 包装件真的被装进了服务 | 进程内自己构造包装件；装配漏接时进程内照样绿 |
# | 超时后连接确实被释放 | 进程内没有连接可释放 |
#
# 本项目已实证「装配漏接」这类缺陷只在真实往返下暴露。
#
# ## 替身的边界
#
# 服务侧装的是**慢智能体**替身——它只替掉「执行很慢」这件事本身，
# 窗口逻辑仍是被测件在跑。不得用替身绕过被验的那一段。
#
# ## 判据
#
# 窗口设为一秒／两秒。非流式调用应在窗口附近返回而非挂满三十秒——
# **返回得比智能体快**就是窗口生效的证据。
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/_backend.sh"

PORT="${WAIT_WINDOW_PORT:-18097}"
EXEC_WAIT="${E2E_EXECUTION_WAIT_S:-1}"
CONSUME_WAIT="${E2E_CONSUME_WAIT_S:-2}"

# 清理上一次的残留——端口被占则本次连到旧进程，读数来自变更前的代码
_pid=$(ss -lptn "sport = :$PORT" 2>/dev/null | grep -oP 'pid=\K[0-9]+' | head -1)
[ -n "$_pid" ] && { e2e_log "清理端口 $PORT 的残留进程（pid $_pid）"; kill -9 "$_pid" 2>/dev/null; sleep 1; }

export E2E_A2A_WAIT_WINDOWS=1
export E2E_EXECUTION_WAIT_S="$EXEC_WAIT"
export E2E_CONSUME_WAIT_S="$CONSUME_WAIT"

# 容器后端须显式声明要透传的变量——否则容器里跑的是默认配置（快智能体、默认窗口）
export E2E_PASS_ENV="E2E_A2A_WAIT_WINDOWS E2E_EXECUTION_WAIT_S E2E_CONSUME_WAIT_S"
e2e_start "e2e_a2a_server" "$PORT"
e2e_wait_health 60
e2e_log "服务就绪（执行窗口 ${EXEC_WAIT}s、消费窗口 ${CONSUME_WAIT}s、智能体 sleep 30s）"

FAILED=0

# ── 执行等待窗口：非流式调用应在窗口附近返回，不挂满 ────────
e2e_log "执行等待窗口：发非流式调用，智能体 sleep 30s"
START=$(date +%s%N)
RESP=$(curl -s -m 25 "$BASE/a2a/" -X POST -H "Content-Type: application/json" -H "A2A-Version: 1.0" \
  -d '{"jsonrpc":"2.0","id":"1","method":"SendMessage","params":{"message":{"messageId":"m-1","contextId":"c-w1","role":"ROLE_USER","parts":[{"text":"慢查询"}]}}}' 2>/dev/null)
ELAPSED_MS=$(( ($(date +%s%N) - START) / 1000000 ))
e2e_log "  返回耗时 ${ELAPSED_MS}ms，响应 ${RESP:0:120}"

# 窗口生效的判据：返回得比智能体（30s）快得多。
# 上限取窗口的十倍加两秒，容纳启动与网络开销，同时远小于智能体时长。
LIMIT_MS=$(( $(printf '%.0f' "$EXEC_WAIT") * 10000 + 2000 ))
if [ "$ELAPSED_MS" -lt "$LIMIT_MS" ]; then
  e2e_log "  ✅ 在 ${LIMIT_MS}ms 内返回——窗口生效，未挂满智能体的 30 秒"
else
  e2e_log "  ❌ 耗时 ${ELAPSED_MS}ms 超过 ${LIMIT_MS}ms——窗口未生效"
  FAILED=1
fi

# **先证明请求真的进了执行链路**：方法未找到（-32601）时请求根本没执行，
# 「返回得快」是因为它压根没跑——那样的绿是假的。
case "$RESP" in
  *"-32601"*)
    e2e_log "  ❌ 方法未找到——请求未进执行链路，本条读数无意义"
    FAILED=1 ;;
  "")
    e2e_log "  ❌ 响应为空——连接被断开而非返回快照或错误"
    FAILED=1 ;;
  *)
    e2e_log "  ✅ 请求已进执行链路且有响应体" ;;
esac

# 窗口生效还须「不是立刻返回」：真进了链路又等到了窗口，耗时应当在窗口量级附近，
# 而不是几毫秒。太快说明它在别处就返回了。
MIN_MS=$(( $(printf '%.0f' "$EXEC_WAIT") * 1000 / 2 ))
if [ "$ELAPSED_MS" -lt "$MIN_MS" ]; then
  e2e_log "  ❌ 仅 ${ELAPSED_MS}ms 就返回（窗口 ${EXEC_WAIT}s）——未真正等到窗口耗尽"
  FAILED=1
fi

# ── 消费等待窗口：流式调用应被中止，不无限出流 ──────────────
e2e_log "消费等待窗口：发流式调用，智能体持续产帧"
START=$(date +%s%N)
STREAM=$(curl -s -m 25 -N "$BASE/a2a/" -X POST -H "Content-Type: application/json" -H "A2A-Version: 1.0" \
  -d '{"jsonrpc":"2.0","id":"2","method":"SendStreamingMessage","params":{"message":{"messageId":"m-2","contextId":"c-w2","role":"ROLE_USER","parts":[{"text":"持续产帧"}]}}}' 2>/dev/null)
ELAPSED_MS=$(( ($(date +%s%N) - START) / 1000000 ))
FRAMES=$(printf '%s' "$STREAM" | grep -c '^data: ')
e2e_log "  出流 ${FRAMES} 帧，耗时 ${ELAPSED_MS}ms"

# 同理：流必须真的出过帧，否则「被中止」只是没开始
if [ "$FRAMES" -lt 1 ]; then
  e2e_log "  ❌ 出流 0 帧——流未真正开始，本条读数无意义"
  FAILED=1
fi

# 智能体每 0.05s 一帧且永不停止；窗口不生效则会一直流到 curl 超时（25s）
LIMIT_MS=$(( $(printf '%.0f' "$CONSUME_WAIT") * 10000 + 3000 ))
if [ "$ELAPSED_MS" -lt "$LIMIT_MS" ]; then
  e2e_log "  ✅ 在 ${LIMIT_MS}ms 内中止——窗口生效，未流到 curl 超时"
else
  e2e_log "  ❌ 耗时 ${ELAPSED_MS}ms——流未被窗口中止"
  FAILED=1
fi

[ "$FAILED" = "0" ] && { e2e_log "✅ 两个等待窗口在真实环境下均生效"; exit 0; }
e2e_log "❌ 等待窗口验证未通过"
exit 1
