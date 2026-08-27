#!/usr/bin/env bash
# 断流降级的真实运行环境验证（Feat-Func-004b §7.4）。
#
# ## 判据（三条同时成立才算通过，缺一条则读数无意义）
#
# | 判据 | 不加它会怎样 |
# |---|---|
# | 代理确实切断过（broken ≥ 1） | 「调用成功」可能只是压根没断过——那不是降级生效 |
# | 主调方拿到**完整终答** | 拿到半截或错误说明降级没兜住 |
# | 连接数 ≥ 2 | 只有一个连接说明重订阅没发生，结果是首连侥幸拿到的 |
#
# ## 替身的边界
#
# 被替掉的只有「网络在此刻断开」这件事。降级判断、退避、重订阅、终态查询
# 全部是被测件在跑，走真实 socket 与真实 a2a-sdk。
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/_backend.sh"

PORT="${STREAM_FALLBACK_PORT:-18098}"
PROXY_PORT="${BREAK_PROXY_PORT:-$((PORT + 1))}"

for p in "$PORT" "$PROXY_PORT"; do
  _pid=$(ss -lptn "sport = :$p" 2>/dev/null | grep -oP 'pid=\K[0-9]+' | head -1)
  [ -n "$_pid" ] && { e2e_log "清理端口 $p 的残留进程（pid $_pid）"; kill -9 "$_pid" 2>/dev/null; sleep 1; }
done

export BREAK_PROXY_PORT="$PROXY_PORT"
export E2E_PASS_ENV="BREAK_PROXY_PORT BREAK_AFTER_CHUNKS"
e2e_start "e2e_stream_fallback_server" "$PORT"
e2e_wait_health 60
e2e_log "服务就绪（A2A ${PORT}、切断代理 ${PROXY_PORT}）"

FAILED=0

e2e_log "发起经切断代理的远端调用"
RESP=$(curl -s -m 90 "$BASE/drive-stream-fallback" 2>/dev/null)
e2e_log "  响应 ${RESP:0:400}"

case "$RESP" in
  "") e2e_log "  ❌ 响应为空——服务未返回，本条读数无意义"; FAILED=1 ;;
esac

# 判据一：断流确实发生过
BROKEN=$(printf '%s' "$RESP" | grep -oP '"broken":\s*\K[0-9]+' | head -1)
if [ "${BROKEN:-0}" -ge 1 ]; then
  e2e_log "  ✅ 代理切断过 ${BROKEN} 次——断流真实发生"
else
  e2e_log "  ❌ 代理一次都没切断（broken=${BROKEN:-未取到}）——没断过，降级无从谈起"
  FAILED=1
fi

# 判据二：重订阅确实发生过（连接数 > 1）
CONNS=$(printf '%s' "$RESP" | grep -oP '"connections":\s*\K[0-9]+' | head -1)
if [ "${CONNS:-0}" -ge 2 ]; then
  e2e_log "  ✅ 代理经手 ${CONNS} 个连接——断流后确实重连过"
else
  e2e_log "  ❌ 只有 ${CONNS:-未取到} 个连接——重订阅未发生"
  FAILED=1
fi

# 判据三：拿到完整终答
case "$RESP" in
  *'"match":true'*) e2e_log "  ✅ 终答与期望逐字一致——降级把结果兜回来了" ;;
  *) e2e_log "  ❌ 终答与期望不一致——降级未取回完整结果"; FAILED=1 ;;
esac

[ "$FAILED" = "0" ] && { e2e_log "✅ 断流降级在真实环境下生效"; exit 0; }
e2e_log "❌ 断流降级验证未通过"
exit 1
