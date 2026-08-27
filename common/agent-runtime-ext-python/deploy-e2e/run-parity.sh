#!/usr/bin/env bash
# 双侧对等比对（真 socket）：同一批请求打存量与本版两个真实服务，比对完整响应。
#
# ## 它比进程内差分多覆盖什么
#
# 进程内的三层差分（函数级、往返级、流级）都走 ASGI 传输——不经网络栈、不经真实
# HTTP 服务器。本脚本起两个 uvicorn，用 curl 发真请求，多覆盖的是：
#
# | 多出的 | 为什么进程内看不到 |
# |---|---|
# | 真 socket 与真 HTTP 栈 | ASGI 传输直接调应用对象，不经 TCP 与 HTTP 解析 |
# | 服务器层的响应头与分块传输 | uvicorn 自己加的头、SSE 的分块编码 |
# | 存量的完整启动链路 | 进程内比对不跑启动钩子，本脚本跑 |
#
# 本项目已多次实证 wire 契约缺陷只在真 socket 下暴露，故这一层不可省。
#
# ## 前置
#
# - **Redis**：存量启动即连接。脚本自行起容器；已有实例时设 LEGACY_REDIS_PORT 复用。
# - **存量仓**：默认在 ../../openJiuwen/agent-runtime-mvp，可用 LEGACY_ROOT 覆盖。
#
# 二者任一不可得即**未判**——不是通过。基准不在场时走 `unjudged`（见下），
# 它把「没判」与「判过且通过」分开：
#
# - 默认（本地开发）：打印醒目的未判标记并退 0，**结语不写「通过」**——
#   否则 `make e2e` 会被一次环境缺失整条中止，而那不是代码问题；
# - `GATE_REQUIRE_BASELINES=1`：退 3（`tools/gate_exit.py` 的「基准不在场」态），
#   由调用侧提升为失败。这就是「把存量作为显式前置」的开关。
#
# **为什么不能一律退 0**：`tools/gate_exit.py` 的判词逐字是「**3 与 0 更必须分开**：
# 把『没判』记成『判过且通过』，就是门禁在基准不在场时反而更容易绿」。
# 本脚本是对外兼容最强的一道，它静默变绿的代价最大。
# 同族问题此前已登记为 R-13（`internal/ledger/ISSUE-LEDGER.md` 的 `「六原则门禁的独立复核（已闭合 · G-1~G-8」`，对象是黄金基线）。
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/_backend.sh"

# 基准不在场的统一出口：**未判 ≠ 通过**。
unjudged() {
  e2e_log "⏭ 未判（不是通过）：$1"
  if [ "${GATE_REQUIRE_BASELINES:-0}" = "1" ]; then
    e2e_log "   GATE_REQUIRE_BASELINES=1 → 提升为失败（退出码 3）"
    exit 3
  fi
  e2e_log "   本次未验证任何对外兼容性；要强制在场请置 GATE_REQUIRE_BASELINES=1"
  exit 0
}

LEGACY_ROOT="${LEGACY_ROOT:-$HERE/../../openJiuwen/agent-runtime-mvp}"
LEGACY_PORT="${LEGACY_PORT:-18099}"
MINE_PORT="${MINE_PORT:-18098}"
REDIS_PORT="${LEGACY_REDIS_PORT:-16379}"
# **容器名必须可覆盖**：端口早就可覆盖（`LEGACY_PORT`／`MINE_PORT`／`LEGACY_REDIS_PORT`），
# 唯独容器名是写死的，于是两份工作树并发跑这道脚本时，后起的一份 `docker run --name`
# 撞名起不来（被判 unjudged），先结束的那一份的 `cleanup_parity` 还会
# `docker rm -f parity-redis` 把另一份正在用的 Redis 删掉——表现是对方跑到一半
# 存量侧突然连不上 Redis，看起来像存量的偶发故障。
# 2026-08-27 两个 session 并发作业时实撞。
REDIS_NAME="${PARITY_REDIS_NAME:-parity-redis}"

[ -d "$LEGACY_ROOT" ] || { unjudged "存量仓不存在（$LEGACY_ROOT）"; }

# 存量须用它自己的解释器——它的依赖装在自己的虚拟环境里
LEGACY_PY="${LEGACY_PY:-$LEGACY_ROOT/.venv/bin/python}"
[ -x "$LEGACY_PY" ] || { unjudged "存量虚拟环境不可用（$LEGACY_PY）"; }

# ── 清理上一次的残留 ────────────────────────────────────────
# 前一次异常退出（超时、变异验证中断）会留下监听进程，端口被占则本次起的服务
# 连不上，表现为「复原后仍然红」——那是假读数，会把人引向不存在的回归。
# 清理函数定义在下方，此处内联同样的逻辑（trap 尚未挂上）
for _port in "$LEGACY_PORT" "$MINE_PORT"; do
  _pid=$(ss -lptn "sport = :$_port" 2>/dev/null | grep -oP 'pid=\K[0-9]+' | head -1)
  [ -n "$_pid" ] && { e2e_log "清理上一次的残留进程（端口 $_port，pid $_pid）"; kill -9 "$_pid" 2>/dev/null; }
done
sleep 1

# ── Redis ───────────────────────────────────────────────────
REDIS_STARTED=0
if ! (echo > "/dev/tcp/127.0.0.1/$REDIS_PORT") 2>/dev/null; then
  command -v docker >/dev/null 2>&1 || { unjudged "无 Redis 且无容器运行时"; }
  docker run -d --rm --name "$REDIS_NAME" -p "$REDIS_PORT:6379" redis:7-alpine >/dev/null 2>&1 \
    || { unjudged "Redis 容器起不来"; }
  REDIS_STARTED=1
  sleep 2
fi

# ── 存量仓的包路径：主三个 + runtime 下各子包 ────────────────
RUNTIME_PKGS=$(find "$LEGACY_ROOT/runtime" -maxdepth 2 -type d -name "openjiuwen_*" 2>/dev/null \
  | xargs -r -n1 dirname | sort -u | tr '\n' ':')
# **仓根不可省**：存量引导件（legacy_boot.py）要 import 我方的 `oracle_support`
# ——那是跑存量时需要的我方补件，此前它在 `applications/a2a_service/common/` 下，
# 随存量副本一起被找到；副本删除后它迁到了仓根的 `oracle_support/`。
# 漏掉仓根时的表现是 legacy_boot 导入失败，而失败发生在子进程里、
# 只在存量侧的日志里露面，看起来像存量起不来。
LEGACY_PYPATH="$LEGACY_ROOT/foundation:$LEGACY_ROOT/server:$LEGACY_ROOT/service:${RUNTIME_PKGS}$LEGACY_ROOT/applications/a2a_service:$HERE:$HERE/.."

kill_by_port() {
  local pid
  pid=$(ss -lptn "sport = :$1" 2>/dev/null | grep -oP 'pid=\K[0-9]+' | head -1)
  [ -n "$pid" ] && kill -9 "$pid" 2>/dev/null
  return 0
}

cleanup_parity() {
  # **按端口杀，不只杀记录的 PID**：存量以 `( ... ) &` 起在子 shell 里，
  # 记录到的是外壳的 PID，杀它留下 uvicorn 继续监听。下一次运行就会连到
  # 上一次的残留服务——那时源码已经变了，读数却来自旧进程。
  [ -n "${LEGACY_PID:-}" ] && kill "$LEGACY_PID" 2>/dev/null
  [ -n "${MINE_PID:-}" ] && kill "$MINE_PID" 2>/dev/null
  kill_by_port "$LEGACY_PORT"
  kill_by_port "$MINE_PORT"
  [ "$REDIS_STARTED" = "1" ] && docker rm -f "$REDIS_NAME" >/dev/null 2>&1
  return 0
}
trap cleanup_parity EXIT

# ── 起存量 ──────────────────────────────────────────────────
e2e_log "起存量服务（真 socket，端口 $LEGACY_PORT）"
( cd "$LEGACY_ROOT/applications/a2a_service" \
  && PYTHONPATH="$LEGACY_PYPATH" REDIS_HOST=127.0.0.1 REDIS_PORT="$REDIS_PORT" REDIS_DB=0 \
     PARITY_FIXED_EVENTS=1 \
     "$LEGACY_PY" -m uvicorn legacy_boot:app --port "$LEGACY_PORT" --log-level warning \
     > "$HERE/.e2e-logs/legacy.log" 2>&1 ) &
LEGACY_PID=$!

for _ in $(seq 1 40); do
  sleep 1
  [ "$(curl -s -o /dev/null -w '%{http_code}' -m 2 "http://127.0.0.1:$LEGACY_PORT/health")" = "200" ] && break
done
[ "$(curl -s -o /dev/null -w '%{http_code}' -m 2 "http://127.0.0.1:$LEGACY_PORT/health")" = "200" ] \
  || { tail -5 "$HERE/.e2e-logs/legacy.log" 2>/dev/null; unjudged "存量服务未就绪"; }
e2e_log "  存量就绪"

# ── 起本版 ──────────────────────────────────────────────────
# **与存量同一个 Redis 实例、同一个库**：双写场景要的正是两侧写同一个键面，
# 各连各的实例就退化成两个互不相干的写入，验不到覆盖与否。
export PARITY_REDIS_HOST=127.0.0.1
export PARITY_REDIS_PORT="$REDIS_PORT"
export PARITY_REDIS_DB=0
export E2E_PASS_ENV="PARITY_REDIS_HOST PARITY_REDIS_PORT PARITY_REDIS_DB"
e2e_start "e2e_parity_server" "$MINE_PORT"
e2e_wait_health 60
e2e_log "  本版就绪"

# ── 逐例比对 ────────────────────────────────────────────────
# **每次运行用唯一会话标识**：存量按会话与全局两个维度限流，
# 连续运行时复用同一标识会撞上前一次的限流窗口，返回 100001「系统超负载」——
# 那是存量的真实对外行为，不是缺陷，但会让比对间歇性失败且看起来像回归。
RUN_ID="${RUN_ID:-$(date +%s)-$$}"
ROUTE="/v1/proj-1/agents/agent-1/conversations/conv-$RUN_ID"
FAILED=0

compare() {
  local name="$1"; shift
  local mine legacy mine_code legacy_code
  # **用 $BASE 而非写死端口**：容器后端把服务映射到宿主端口、本机后端直接监听，
  # 两者的可达地址不同，e2e_start 已把正确地址放进 $BASE。写死端口时容器后端全部拿到 000。
  mine=$(curl -s -m 15 -w $'\n%{http_code}' "$BASE$1" "${@:2}")
  legacy=$(curl -s -m 15 -w $'\n%{http_code}' "http://127.0.0.1:$LEGACY_PORT$1" "${@:2}")
  mine_code="${mine##*$'\n'}"; legacy_code="${legacy##*$'\n'}"
  mine="${mine%$'\n'*}"; legacy="${legacy%$'\n'*}"
  if [ "$mine_code" != "$legacy_code" ]; then
    e2e_log "  ❌ $name 状态码不同：本版 $mine_code、存量 $legacy_code"; FAILED=1; return
  fi
  if [ "$mine" != "$legacy" ]; then
    e2e_log "  ❌ $name 响应体不同"
    e2e_log "     本版 ${mine:0:150}"
    e2e_log "     存量 ${legacy:0:150}"
    FAILED=1; return
  fi
  e2e_log "  ✅ $name（$mine_code，字节一致）"
}

e2e_log "执行前三关的真 socket 对等比对"
compare "内容类型非 JSON" "$ROUTE" -X POST -H "Content-Type: text/plain" -d "x"
compare "请求体非合法 JSON" "$ROUTE" -X POST -H "Content-Type: application/json" -d "{bad"
compare "通道未匹配" "/v1/unmatched" -X POST -H "Content-Type: application/json" -d "{}"
compare "路径不可路由" "/completely/elsewhere" -X POST -H "Content-Type: application/json" -d "{}"

# ── 取消端点：**此前整条对外端点没有对等覆盖** ──────────────
# 它有独立的路径判定与独立的 404 信封，两侧各自实现。判定写错时状态码与信封都会变，
# 而没有任何东西会发现——本仓 2026-08-11 归一取消侧判定时实测过一次代价：
# 改一处路由模板，取消端点回 200 而分发端点回 404，两个端点对同一条路径给出相反判断。
compare "取消·正常路径" "$ROUTE/cancel" -X POST -H "Content-Type: application/json" -d "{}"
compare "取消·通道未匹配" "/v1/unmatched/cancel" -X POST -H "Content-Type: application/json" -d "{}"

# ── 执行中面：SSE 流的字节比对 ─────────────────────────────
# 两侧都注入了产出同一批事件的确定性替身，故出流应当逐字节一致。
# 真实智能体输出不确定，无替身则无法比对——这一点写在 legacy_boot 的文档里。

# ── 阻塞聚合路径：**此前只比过 SSE，这条路整条没有对等覆盖** ──
# 它是另一条独立的出口（`_aggregate`，drain 执行流后投影为存量信封），
# 与 SSE 那条不共用投影代码。本仓实测过它出对外差异的代价：远端 504 时
# 本版返回 500 纯文本，而存量捕获异常后照常返回 200 与聚合信封——
# **集成方按 200 加信封写的解析逻辑，遇到本版直接崩在状态码判断上**。
# 那次是靠人实测发现的，不是靠防线。
# **用独立会话标识**：与 SSE 那条共用时，本条先把会话推进一轮，
# 后面的 SSE 请求在两侧得到的轮次不同——实测本版 3 帧、存量 1 帧，
# 那是**测试污染**而非产品差异。会话是有状态的，比对项之间必须互不干扰。
compare "阻塞聚合·正常" "/v1/proj-1/agents/agent-1/conversations/agg-$RUN_ID" \
  -X POST -H "Content-Type: application/json" \
  -d '{"input":{"query":"查余额"},"stream":false}'

e2e_log "执行中面的真 socket SSE 流比对"

sse_mine=$(curl -s -m 20 -N "$BASE$ROUTE" -X POST \
  -H "Content-Type: application/json" \
  -d '{"input":{"query":"查余额"},"stream":true}')
sse_legacy=$(curl -s -m 20 -N "http://127.0.0.1:$LEGACY_PORT$ROUTE" -X POST \
  -H "Content-Type: application/json" \
  -d '{"input":{"query":"查余额"},"stream":true}')

# 帧数：按空行切分后计非空段
count_frames() { printf '%s' "$1" | awk 'BEGIN{RS="";n=0} {n++} END{print n}'; }
n_mine=$(printf '%s' "$sse_mine" | grep -c '^data: ')
n_legacy=$(printf '%s' "$sse_legacy" | grep -c '^data: ')

if [ "$n_mine" != "$n_legacy" ]; then
  e2e_log "  ❌ 帧数不同：本版 $n_mine、存量 $n_legacy"
  e2e_log "     本版 ${sse_mine:0:200}"
  e2e_log "     存量 ${sse_legacy:0:200}"
  FAILED=1
else
  e2e_log "  ✅ 帧数一致（$n_mine 帧）"
fi

# 帧内容：把时间位归一后逐字节比对。
# **execution_time 与 createdTime 两侧不可能相同**——一个是本次耗时、一个是产帧时刻，
# 它们的「值」不是契约，「在哪一位、什么类型」才是。归一只替换值，键名、位置、
# 其余全部字符仍逐字节比。
# 心跳序号同理归一：两侧的**计数作用域**是已裁定的有意偏离——存量按会话累加
# （`_next_heartbeat_seq`，模块级字典、从不清理），我方按单次出流从 1 递增
# （`agent_runtime/adapters/inbound/rest/router.py` 的 `heartbeat_seq`），
# 依据是存量重启后也从 1 开始、起始值本就不是契约（2026-08-18 独立复核 · 对外兼容 V-1）。
# 归一掉的只是**值**：键名 `seq`、它在 data 里的位置、整数类型仍逐字节比——
# 哪一侧漏补这个键，两边的帧文本立刻不同，照样转红。
normalize_time() {
  printf '%s' "$1" \
    | sed -E 's/"execution_time": [0-9.e-]+/"execution_time": <T>/g' \
    | sed -E 's/"createdTime": [0-9]+/"createdTime": <T>/g' \
    | sed -E 's/"seq": [0-9]+/"seq": <N>/g'
}
# ── 先规范化键序，再归一时间与序号 ──
# **顺序不可颠倒**：归一把 `"execution_time": <T>` 写成非法 JSON，之后再解析必然
# 逐行抛错、逐行原样返回，规范化静默失效——而它的恒等性自检也一起失效
# （所有行都解析失败＝没有一行进入自检），读数是绿的、实际什么都没做。
# 2026-08-27 实撞：先归一后规范化，三轮全红且自检报绿。
if ! sse_mine_c=$(printf '%s' "$sse_mine" | python3 "$HERE/parity_canon_keys.py"); then
  e2e_log "  ❌ 本版侧键序规范化不是恒等变换——它在掩盖序列化风格差异，比对不可信"
  FAILED=1
  sse_mine_c="$sse_mine"
fi
if ! sse_legacy_c=$(printf '%s' "$sse_legacy" | python3 "$HERE/parity_canon_keys.py"); then
  e2e_log "  ❌ 存量侧键序规范化不是恒等变换——它在掩盖序列化风格差异，比对不可信"
  FAILED=1
  sse_legacy_c="$sse_legacy"
fi

sse_mine_n=$(normalize_time "$sse_mine_c")
sse_legacy_n=$(normalize_time "$sse_legacy_c")

# 键序规范化的说明：**只排 `custom_rsp_data.data` 那一层的键**。
# 存量那一层的键序每次运行都不同——`dict_to_a2a` 走 protobuf `Struct`，map 往返不保序，
# 三轮实测三种顺序（2026-08-27）。键序不构成契约（JSON 对象无序，存量自己都不稳定），
# 而键集、值与其余全部字符仍逐字节比。
# **不做这一步的后果不是漏判，是 flaky**：多键 data 的帧一进对等面，比对就随机红。
# 规范化件自带恒等性自检（空/单键 data 的帧必须原样返回），不成立即退 3。


# 归一必须真的生效——否则下面的比对退化成比原文，永远不同却看不出原因
case "$sse_mine_n" in
  *"<T>"*) : ;;
  *) e2e_log "  ❌ 时间位归一未命中，比对不可信"; FAILED=1 ;;
esac

# 心跳帧必须真的出现在两侧出流里——替身产了它、投影层却把它吞掉时，
# 两侧同样是「都没有」，逐字节比对照样绿。这一条把「吞掉」与「一致」分开。
for _side in mine legacy; do
  eval "_txt=\$sse_${_side}_n"
  case "$_txt" in
    *'"seq": <N>'*) : ;;
    *) e2e_log "  ❌ ${_side} 侧出流里没有带序号的心跳帧——要么帧被吞，要么序号没补"; FAILED=1 ;;
  esac
done

if [ "$sse_mine_n" != "$sse_legacy_n" ]; then
  e2e_log "  ❌ SSE 字节流不同"
  # **落盘再逐行 diff，不截前 220 字符**：两条流的差异往往在第三、四帧，
  # 而前 220 字符落在第一帧里——两侧看起来一模一样，读日志的人拿不到任何线索。
  # 2026-08-27 实证：心跳帧进对等面后首次出差异，靠这段预览完全定位不到。
  printf '%s\n' "$sse_mine_n" | sed 's/^data: //' | tr -d '\r' > "$HERE/.e2e-logs/sse-mine.txt"
  printf '%s\n' "$sse_legacy_n" | sed 's/^data: //' | tr -d '\r' > "$HERE/.e2e-logs/sse-legacy.txt"
  e2e_log "     完整两侧已落盘：$HERE/.e2e-logs/sse-{mine,legacy}.txt"
  diff -u "$HERE/.e2e-logs/sse-legacy.txt" "$HERE/.e2e-logs/sse-mine.txt" \
    | head -40 | while IFS= read -r _line; do e2e_log "     | $_line"; done
  FAILED=1
else
  e2e_log "  ✅ SSE 字节流一致"
fi

# 媒体类型与哨兵帧
ct_mine=$(curl -s -m 20 -o /dev/null -D - "$BASE$ROUTE" -X POST \
  -H "Content-Type: application/json" -d '{"input":{"query":"x"},"stream":true}' \
  | grep -i "^content-type:" | tr -d '\r')
ct_legacy=$(curl -s -m 20 -o /dev/null -D - "http://127.0.0.1:$LEGACY_PORT$ROUTE" -X POST \
  -H "Content-Type: application/json" -d '{"input":{"query":"x"},"stream":true}' \
  | grep -i "^content-type:" | tr -d '\r')
if [ "$ct_mine" = "$ct_legacy" ]; then
  e2e_log "  ✅ 媒体类型一致（${ct_mine#*: }）"
else
  e2e_log "  ❌ 媒体类型不同：本版 [$ct_mine]、存量 [$ct_legacy]"; FAILED=1
fi

case "$sse_mine$sse_legacy" in
  *"[DONE]"*) e2e_log "  ❌ 出现哨兵帧——存量代码从不产出它"; FAILED=1 ;;
  *) e2e_log "  ✅ 两侧都无哨兵帧" ;;
esac

# ── 共享键面：两个真实进程对同一个键的双写 ─────────────────
# **这一段验的不是响应，是副作用**：两侧都会把上游原始请求写进
# `session:{会话标识}:request`，混部期的问题是「谁写谁的、会不会互相覆盖」。
# 进程内判据验不到它——那里只有一个进程，构造不出两个写者。
#
# 三条判据：
#   一、存量先写时本版不覆盖（本版用 SETNX，根设计 §3.2 登记的更严之处）
#   二、本版先写时存量不覆盖（存量自己的「读到即不写」）
#   三、两侧写出的字段集相等（少 agent_id 会让存量的续轮恢复读到空）

read_shared_key() {
  # 用存量的解释器连 Redis 读键——它的虚拟环境里必有 redis 库（启动就连）。
  # 不依赖 redis-cli（本机未装）与容器名（Redis 可能是复用的既有实例）。
  "$LEGACY_PY" - "$1" <<'PYEOF' 2>/dev/null
import asyncio, os, sys
import redis.asyncio as aioredis

async def main():
    r = aioredis.Redis(host="127.0.0.1", port=int(os.environ["REDIS_PORT_FOR_PROBE"]), db=0)
    v = await r.get(sys.argv[1])
    sys.stdout.write(v.decode("utf-8") if v else "")
    await r.aclose()

asyncio.run(main())
PYEOF
}
export REDIS_PORT_FOR_PROBE="$REDIS_PORT"

post_once() {  # $1=基址 $2=会话标识 $3=追踪标记
  curl -s -m 20 -o /dev/null \
    "$1/v1/proj-1/agents/agent-1/conversations/$2" -X POST \
    -H "Content-Type: application/json" -H "X-Parity-Mark: $3" \
    -d '{"input":{"query":"查余额"},"stream":false}'
}

e2e_log "共享键面的双写比对（两个真实进程写同一个键）"

# 先确认本版这一侧真的接了 Redis——没接的话下面三条全会「通过」而毫无意义
CONV_PROBE="conv-probe-$RUN_ID"
post_once "$BASE" "$CONV_PROBE" "probe" >/dev/null
PROBE=$(read_shared_key "session:$CONV_PROBE:request")
if [ -z "$PROBE" ]; then
  e2e_log "  ❌ 本版未写入共享键——写侧未接 Redis，本段读数无意义"
  FAILED=1
else
  e2e_log "  ✅ 本版写侧已接真实 Redis（前置确认）"

  # ── 一：存量先写，本版不覆盖 ──
  CONV_L="conv-legacy-first-$RUN_ID"
  post_once "http://127.0.0.1:$LEGACY_PORT" "$CONV_L" "legacy-first" >/dev/null
  AFTER_LEGACY=$(read_shared_key "session:$CONV_L:request")
  post_once "$BASE" "$CONV_L" "mine-second" >/dev/null
  AFTER_BOTH=$(read_shared_key "session:$CONV_L:request")
  if [ -z "$AFTER_LEGACY" ]; then
    e2e_log "  ❌ 存量未写入该键，本条无法归因"
    FAILED=1
  elif [ "$AFTER_LEGACY" = "$AFTER_BOTH" ]; then
    e2e_log "  ✅ 存量先写后本版不覆盖（字节未变）"
  else
    e2e_log "  ❌ 本版覆盖了存量写入的值"
    e2e_log "     存量写入 ${AFTER_LEGACY:0:150}"
    e2e_log "     本版写后 ${AFTER_BOTH:0:150}"
    FAILED=1
  fi

  # ── 二：本版先写，存量不覆盖 ──
  CONV_M="conv-mine-first-$RUN_ID"
  post_once "$BASE" "$CONV_M" "mine-first" >/dev/null
  AFTER_MINE=$(read_shared_key "session:$CONV_M:request")
  post_once "http://127.0.0.1:$LEGACY_PORT" "$CONV_M" "legacy-second" >/dev/null
  AFTER_BOTH2=$(read_shared_key "session:$CONV_M:request")
  if [ -z "$AFTER_MINE" ]; then
    e2e_log "  ❌ 本版未写入该键，本条无法归因"
    FAILED=1
  elif [ "$AFTER_MINE" = "$AFTER_BOTH2" ]; then
    e2e_log "  ✅ 本版先写后存量不覆盖（字节未变）"
  else
    e2e_log "  ❌ 存量覆盖了本版写入的值"
    e2e_log "     本版写入 ${AFTER_MINE:0:150}"
    e2e_log "     存量写后 ${AFTER_BOTH2:0:150}"
    FAILED=1
  fi

  # ── 三：两侧写出的字段集相等 ──
  field_set() { printf '%s' "$1" | "$LEGACY_PY" -c "
import json, sys
try:
    print(','.join(sorted(json.load(sys.stdin))))
except Exception as exc:
    print(f'<解析失败: {exc}>')
"; }
  F_LEGACY=$(field_set "$AFTER_LEGACY")
  F_MINE=$(field_set "$AFTER_MINE")
  if [ "$F_LEGACY" = "$F_MINE" ] && [ -n "$F_MINE" ]; then
    e2e_log "  ✅ 两侧字段集相等：$F_MINE"
  else
    e2e_log "  ❌ 字段集不同——存量 [$F_LEGACY]、本版 [$F_MINE]"
    e2e_log "     少 agent_id 会让存量的续轮恢复读到空"
    FAILED=1
  fi
fi

[ "$FAILED" = "0" ] && { e2e_log "✅ 真 socket 对等比对通过（执行前三关 + 执行中面 + 共享键双写）"; exit 0; }
e2e_log "❌ 真 socket 对等比对发现差异"
exit 1
