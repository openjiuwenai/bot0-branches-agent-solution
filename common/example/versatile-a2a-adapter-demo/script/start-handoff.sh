#!/usr/bin/env bash
#
# start-handoff.sh — 启动 versatile-controller-handoff-demo 的 layer1 + layer2 双 runtime，
# 供容器/宿主机长时间调测（区别于 local-e2e.sh 的"跑完十场景自动停止"）。
#
# 用法:
#   ./start-handoff.sh            # 启动 layer2 (:18092) + layer1 (:18091)，等待就绪
#   ./start-handoff.sh --stop     # 停止两个 runtime（PID 记录在 .handoff-l{1,2}.pid）
#
# 关键环境变量（可覆盖）:
#   L1_PORT       一级 runtime 端口，默认 18091（/v1/query 调试入口）
#   L2_PORT       二级 runtime 端口，默认 18092
#   HANDOFF_DIR   demo 模块目录；默认依次探测:
#                   仓库布局:  <本脚本>/../../versatile-controller-handoff-demo
#                   容器布局:  /app/controller-handoff-demo
#   JAR           显式指定 jar；默认 $HANDOFF_DIR/target/versatile-controller-handoff-demo-*.jar
#
# 启动后:
#   调试入口    http://127.0.0.1:${L1_PORT}/v1/query   （一级，含 mock 控制器）
#   二级入口    http://127.0.0.1:${L2_PORT}/v1/query
#   日志        $HANDOFF_DIR/target/layer{1,2}.log
#   场景关键字  查询本地工作流 / 帮我转调订机票 / 退回 / 触发控制器异常 /
#               无目标 / 越权 / 不可达 / 补充信息 / 循环 / 超时
#               （十场景验收用 demo 模块的 scripts/local-e2e.sh，需 SKIP_BUILD=1）
#
# 示例:
#   curl -s -X POST "http://127.0.0.1:18091/v1/query" -H "Content-Type: application/json" \
#     -H "X-User-ID: u-42" \
#     -d '{"conversation_id":"debug-1","stream":true,"messages":[{"role":"user","content":"帮我转调订机票"}]}'

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
L1_PID_FILE="$SCRIPT_DIR/.handoff-l1.pid"
L2_PID_FILE="$SCRIPT_DIR/.handoff-l2.pid"

export L1_PORT="${L1_PORT:-18091}"
export L2_PORT="${L2_PORT:-18092}"

# ---------- 处理 --stop ----------
if [[ "${1:-}" == "--stop" ]]; then
  for f in "$L1_PID_FILE" "$L2_PID_FILE"; do
    if [[ -f "$f" ]]; then
      PID="$(cat "$f")"
      if kill -0 "$PID" 2>/dev/null; then
        echo "Stopping handoff runtime (pid=$PID) ..."
        kill "$PID"
      else
        echo "PID $PID not running, removing stale pid file."
      fi
      rm -f "$f"
    fi
  done
  echo "All handoff runtimes stopped."
  exit 0
fi

# ---------- 探测 demo 目录（仓库布局 / 容器布局） ----------
if [[ -z "${HANDOFF_DIR:-}" ]]; then
  for d in "$(dirname "$SCRIPT_DIR")/versatile-controller-handoff-demo" "/app/controller-handoff-demo"; do
    if [[ -d "$d" ]]; then HANDOFF_DIR="$d"; break; fi
  done
fi
if [[ -z "${HANDOFF_DIR:-}" || ! -d "$HANDOFF_DIR" ]]; then
  echo "ERROR: versatile-controller-handoff-demo not found (set HANDOFF_DIR)" >&2
  exit 1
fi

# ---------- 解析 jar ----------
JAR="${JAR:-}"
if [[ -z "$JAR" ]]; then
  JAR="$(ls "$HANDOFF_DIR"/target/versatile-controller-handoff-demo-*.jar 2>/dev/null | head -1 || true)"
fi
if [[ -z "$JAR" || ! -f "$JAR" ]]; then
  echo "ERROR: jar not found under $HANDOFF_DIR/target/ (versatile-controller-handoff-demo-*.jar)" >&2
  echo "Build it first (from repo root):" >&2
  echo "  mvn -pl common/agent-runtime-ext-java/agent-service-adapters/agent-service-adapters-versatile-controller-handoff -f common/agent-runtime-ext-java/pom.xml install" >&2
  echo "  mvn -f common/example/versatile-controller-handoff-demo/pom.xml clean package -DskipTests" >&2
  exit 1
fi

LOG_DIR="$HANDOFF_DIR/target"

# ---------- 启动 ----------
# 先起 layer2：layer1 启动时的静态发现需要立刻拉到 layer2 的 agent card
start_layer() {
  local name="$1" port="$2" pid_file="$3"
  local log="$LOG_DIR/${name}.log"
  echo "Starting $name (port=$port, log=$log)"
  nohup java -jar "$JAR" \
    --spring.profiles.active="${name},mock-controller" \
    --server.port="$port" \
    >"$log" 2>&1 &
  echo $! > "$pid_file"
}

wait_health() {
  # 就绪探测：/v1/query 缺 conversation_id 应返回 400，证明 servlet 与链路已就绪
  local port="$1" name="$2"
  printf "  %-7s " "$name:"
  for i in $(seq 1 90); do
    local status
    status=$(curl -s -o /dev/null -w "%{http_code}" --max-time 2 -X POST "http://127.0.0.1:${port}/v1/query" \
      -H "Content-Type: application/json" -d '{}' 2>/dev/null || echo 000)
    if [ "$status" = "400" ]; then
      echo "UP (port $port)"
      return 0
    fi
    printf "."
    sleep 1
  done
  echo " TIMEOUT (check $LOG_DIR/${name}.log)"
  return 1
}

start_layer layer2 "$L2_PORT" "$L2_PID_FILE"
wait_health "$L2_PORT" layer2
start_layer layer1 "$L1_PORT" "$L1_PID_FILE"
wait_health "$L1_PORT" layer1

# ---------- 等待静态发现（layer1 拉到 layer2 的 card 后转调才立即可用） ----------
printf "  %-7s " "discover:"
for i in $(seq 1 90); do
  if grep -q "Discovered remote agent 'agent_card_l2'" "$LOG_DIR/layer1.log" 2>/dev/null; then
    echo "agent_card_l2 ready"
    break
  fi
  if [[ "$i" == "90" ]]; then
    echo " TIMEOUT (转调场景首次调用可能要等 30s 发现重试)"
  fi
  sleep 1
done

echo
echo "Ready. 十场景旅程验收（自动起停）:  SKIP_BUILD=1 $HANDOFF_DIR/scripts/local-e2e.sh"
echo "调试入口: curl -X POST http://127.0.0.1:${L1_PORT}/v1/query -H 'Content-Type: application/json' \\"
echo "  -H 'X-User-ID: u-42' -d '{\"conversation_id\":\"debug-1\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"帮我转调订机票\"}]}'"
echo "停止: ./start-handoff.sh --stop"
