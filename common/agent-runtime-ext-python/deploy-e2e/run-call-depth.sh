#!/usr/bin/env bash
# 调用深度收敛的真实运行环境验证（Feat-Func-004b §6.3.1.1）。
#
# ## 三段判据
#
# | 段 | 判什么 | 不验会怎样 |
# |---|---|---|
# | 路径过河 | 下一跳读到的路径 = 父路径 + 本次目标 | protobuf 转换丢键时表现为「深度恒定」而非报错 |
# | 累加生效 | 父路径越长，下一跳读到的越长 | 写死单元素时每一跳都以为自己是第一跳 |
# | 达限即拒 | 路径长度达上限时整批不发起，原因为最大调用深度 | 收敛不生效，递归调用无防护 |
#
# ## 替身的边界
#
# 只替掉「上游原始请求从哪来」（会话缓存）。层级路径由被测件按合成规则算出、
# 经真实 A2A 报文过河、由真实入站适配器读回，全程无替身。
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/_backend.sh"

PORT="${CALL_DEPTH_PORT:-18100}"
AGENT_ID="depth-echo"

_pid=$(ss -lptn "sport = :$PORT" 2>/dev/null | grep -oP 'pid=\K[0-9]+' | head -1)
[ -n "$_pid" ] && { e2e_log "清理端口 $PORT 的残留进程（pid $_pid）"; kill -9 "$_pid" 2>/dev/null; sleep 1; }

e2e_start "e2e_call_depth_server" "$PORT"
e2e_wait_health 60
e2e_log "服务就绪（端口 ${PORT}）"

FAILED=0

# 取 JSON 字段的紧凑读法：本脚本只读少数几个键，不引 jq 依赖
_field() { printf '%s' "$2" | grep -oP "\"$1\":\s*\K(\[[^]]*\]|\"[^\"]*\"|[0-9]+)" | head -1; }

# ── 一：首跳，路径应为单元素 ────────────────────────────────
e2e_log "首跳（无父路径）：下一跳应读到单元素路径"
R1=$(curl -s -m 40 "$BASE/drive-depth?parent=&limit=3" 2>/dev/null)
e2e_log "  ${R1:0:260}"
ECHO1=$(printf '%s' "$R1" | grep -oP '"inbound_path":\s*\K\[[^]]*\]')
if [ "$ECHO1" = "[\"$AGENT_ID\"]" ]; then
  e2e_log "  ✅ 下一跳读到 $ECHO1——首跳路径正确过河"
else
  e2e_log "  ❌ 下一跳读到 ${ECHO1:-空}，期望 [\"$AGENT_ID\"]"
  FAILED=1
fi

# ── 二：第三跳，路径应累加为三元素 ──────────────────────────
e2e_log "带父路径 A,B：下一跳应读到 A,B,${AGENT_ID}（累加生效）"
R2=$(curl -s -m 40 "$BASE/drive-depth?parent=A,B&limit=5" 2>/dev/null)
e2e_log "  ${R2:0:260}"
ECHO2=$(printf '%s' "$R2" | grep -oP '"inbound_path":\s*\K\[[^]]*\]')
DEPTH2=$(printf '%s' "$R2" | grep -oP '"inbound_depth":\s*\K[0-9]+')
if [ "$ECHO2" = "[\"A\",\"B\",\"$AGENT_ID\"]" ] && [ "${DEPTH2:-0}" = "3" ]; then
  e2e_log "  ✅ 下一跳读到 $ECHO2、深度 $DEPTH2——累加真实生效"
else
  e2e_log "  ❌ 下一跳读到 ${ECHO2:-空}、深度 ${DEPTH2:-空}，期望三元素、深度 3"
  e2e_log "     单元素说明南向没累加；深度对不上说明入站没读出来"
  FAILED=1
fi

# ── 三：达到上限即拒（存量冻结边界：路径长度 1、上限 1）──────
e2e_log "父路径 A、上限 1：应整批不发起，原因为最大调用深度"
R3=$(curl -s -m 40 "$BASE/drive-depth?parent=A&limit=1" 2>/dev/null)
e2e_log "  ${R3:0:260}"
case "$R3" in
  *'"outcome":"skipped"'*) SKIPPED=1 ;;
  *) SKIPPED=0 ;;
esac
case "$R3" in
  *'"skip_reason":"max_call_depth"'*) REASON=1 ;;
  *) REASON=0 ;;
esac
case "$R3" in
  *'"echo":{}'*) NOCALL=1 ;;
  *) NOCALL=0 ;;
esac
if [ "$SKIPPED$REASON$NOCALL" = "111" ]; then
  e2e_log "  ✅ 达限即拒、原因正确、且一次都没发起（回报为空即证明未发起）"
else
  e2e_log "  ❌ 跳过=$SKIPPED 原因正确=$REASON 未发起=$NOCALL——三者须同时成立"
  FAILED=1
fi

[ "$FAILED" = "0" ] && { e2e_log "✅ 深度收敛与路径累加在真实环境下生效"; exit 0; }
e2e_log "❌ 深度收敛验证未通过"
exit 1
