#!/usr/bin/env bash
#
# send-requests.sh — 用 curl 向 versatile-a2a-adapter-demo 的 A2A 入口发送请求。
#
# 用法:
#   ./send-requests.sh                              # 三轮 SendStreamingMessage（流式）
#   ./send-requests.sh --round 2 --stream           # 只发第 2 轮，流式
#   ./send-requests.sh --round 3 --non-stream       # 只发第 3 轮，非流式 SendMessage
#   ./send-requests.sh --all --non-stream           # 三轮全部非流式
#   ./send-requests.sh --custom                     # 用脚本内置示例发一发自定义 body
#   ./send-requests.sh --url http://127.0.0.1:9090/a2a/
#
# 参数说明:
#   --round <1|2|3|all>   发送第几轮，默认 all
#   --stream              使用 SendStreamingMessage（默认，SSE 流式返回）
#   --non-stream          使用 SendMessage（一次性 JSON 返回）
#   --url <URL>           本地 A2A 入口，默认 http://127.0.0.1:18080/a2a/
#   --custom              发送一发自定义 body 的示例请求
#
# ============================================================================
# 输入的 body / headers 是怎么传给 Versatile 的
# ============================================================================
# A2A JSON-RPC 请求体如下。adapter 只认 params.metadata 里的三类东西:
#
#   ┌─ params.message.parts[0].text
#   │    文本，若为 {"query": "...", "intent": "..."} JSON，则解析出 query/intent；
#   │    否则整段文本作为 query。adapter 会用它们【覆盖】最终远端 body 的
#   │    custom_data.inputs.query / .intent。  ← 每轮真正变化的内容放在这里
#   │
#   ├─ params.metadata.body        (→ 远端 Versatile HTTP body)
#   │    ├─ custom_data            ← 远端 body 的【基底】：整个 custom_data
#   │    │    会原样成为远端 body 的顶层字段
#   │    │     └─ inputs           ← 远端 body.inputs 的基底，query/intent 会被
#   │    │                           上面的 message.text 覆盖
#   │    └─ 其它顶层字段 (agent_id / input / conversation_id / timeout /
#   │        role_id / role_name / stream ...)
#   │         默认【不会】进入远端请求！除非 application.yml 配置了
#   │         interrupt.resume-request-template.body，用 {字段名} 占位符引用它们
#   │
#   ├─ params.metadata.headers     (→ 远端 HTTP 请求头)
#   │    按 application.yml 的 forward-header-whitelist 过滤后透传，
#   │    再叠加 headers-template（template 优先级最高，同名覆盖）
#   │
#   └─ params.metadata.query       (→ 拼到远端 URL 上的 query 参数)
#
# 一句话: message.text 决定 query/intent；metadata.body.custom_data 决定远端
# body 基底；metadata.headers 按白名单透传；metadata.query 进 URL。
# 完整说明见 common/example/versatile-a2a-adapter-demo/README.md 与
# VersatileRequestExtractor.java。
# ============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEMO_DIR="$(dirname "$SCRIPT_DIR")"

# 请求体 JSON 目录。外部目录优先，便于生产直接改/挂载，无需重建镜像:
#   生产包/挂载: $DEMO_DIR/a2a-requests/（与 jar 同级，可 -v 挂载覆盖）
#   仓库/镜像内置: $DEMO_DIR/src/main/resources/a2a-requests/（兜底默认三轮）
REQ_DIR="$DEMO_DIR/a2a-requests"
if [[ ! -d "$REQ_DIR" ]]; then
  REQ_DIR="$DEMO_DIR/src/main/resources/a2a-requests"
fi

A2A_URL="http://127.0.0.1:18080/a2a/"
ROUND="all"
METHOD="SendStreamingMessage"   # 流式；--non-stream 时改为 SendMessage
ACCEPT="text/event-stream"      # 非流式时改为 application/json
CUSTOM="false"
REQ_FILE=""                     # --file 指定任意请求 JSON

while [[ $# -gt 0 ]]; do
  case "$1" in
    --round)    ROUND="$2"; shift 2 ;;
    --stream)   METHOD="SendStreamingMessage"; ACCEPT="text/event-stream"; shift ;;
    --non-stream) METHOD="SendMessage"; ACCEPT="application/json"; shift ;;
    --url)      A2A_URL="$2"; shift 2 ;;
    --custom)   CUSTOM="true"; shift ;;
    --file)     REQ_FILE="$2"; shift 2 ;;
    --all)      ROUND="all"; shift ;;
    -h|--help)  sed -n '1,60p' "$0"; exit 0 ;;
    *)          echo "Unknown option: $1" >&2; exit 1 ;;
  esac
done

# ---------- 发送一轮：读 request JSON（--file 指定，或 REQ_DIR/request-N.json），按需改 method 后 curl ----------
send_round() {
  local n="$1"
  local file="$REQ_FILE"
  if [[ -z "$file" ]]; then
    file="$REQ_DIR/request-$n.json"
  fi
  if [[ ! -f "$file" ]]; then
    echo "ERROR: request file not found: $file" >&2
    echo "  -- 若已挂载外部目录，确认文件在挂载点下；或改用 --file <path> 指定" >&2
    exit 1
  fi
  local json
  if [[ "$METHOD" == "SendMessage" ]]; then
    json="$(sed 's/"method": "SendStreamingMessage"/"method": "SendMessage"/' "$file")"
  else
    json="$(cat "$file")"
  fi

  echo
  echo "========== Round $n  ($METHOD)  $(basename "$file") =========="
  echo "> POST $A2A_URL"
  echo "$json"

  if [[ "$ACCEPT" == "text/event-stream" ]]; then
    # -N 关闭缓冲，逐行打印 SSE 流
    curl -sS -N -X POST "$A2A_URL" \
      -H "Content-Type: application/json; charset=utf-8" \
      -H "Accept: text/event-stream" \
      -H "stream: true" \
      -H "x-invoke-mode: DEBUG" \
      -H "x-language: zh-cn" \
      --data-binary "$json"
  else
    curl -sS -X POST "$A2A_URL" \
      -H "Content-Type: application/json; charset=utf-8" \
      -H "Accept: application/json" \
      -H "stream: true" \
      -H "x-invoke-mode: DEBUG" \
      -H "x-language: zh-cn" \
      --data-binary "$json"
  fi
  echo
}

# ---------- 自定义 body 示例：演示各字段如何映射到远端 Versatile 请求 ----------
send_custom() {
  # text = 解析出 query / intent 的 JSON 字符串（内部引号需转义为 \"）
  local text='{\"query\":\"帮我查一下尾号4241的卡余额\",\"intent\":\"查询账户余额\"}'

  local json
  json=$(cat <<EOF
{
  "jsonrpc": "2.0",
  "id": "versatile-a2a-custom-1",
  "method": "$METHOD",
  "params": {
    "message": {
      "role": "ROLE_USER",
      "contextId": "versatile-a2a-custom",
      "parts": [ { "text": "$text" } ]
    },
    "metadata": {
      "body": {
        "agent_id": "main_planner",
        "conversation_id": "custom-session-001",
        "timeout": "300",
        "role_id": "1",
        "role_name": "手机银行",
        "stream": true,
        "custom_data": {
          "inputs": {
            "wap_userName": "张三"
          },
          "memory_inputs": {},
          "globals": {},
          "plugin_configs": [],
          "long_term_memory": {
            "enable_retrieve": true,
            "enable_extract": true
          }
        }
      },
      "headers": {
        "stream": "true",
        "x-invoke-mode": "DEBUG",
        "x-language": "zh-cn"
      },
      "query": {
        "workspace_id": "11",
        "type": "controller"
      }
    }
  }
}
EOF
)

  echo
  echo "========== Custom ($METHOD) =========="
  echo "> POST $A2A_URL"
  echo "$json"

  if [[ "$ACCEPT" == "text/event-stream" ]]; then
    curl -sS -N -X POST "$A2A_URL" \
      -H "Content-Type: application/json; charset=utf-8" \
      -H "Accept: text/event-stream" \
      -H "stream: true" \
      -H "x-invoke-mode: DEBUG" \
      -H "x-language: zh-cn" \
      --data-binary "$json"
  else
    curl -sS -X POST "$A2A_URL" \
      -H "Content-Type: application/json; charset=utf-8" \
      -H "Accept: application/json" \
      -H "stream: true" \
      -H "x-invoke-mode: DEBUG" \
      -H "x-language: zh-cn" \
      --data-binary "$json"
  fi
  echo
}

# ---------- 分发 ----------
if [[ "$CUSTOM" == "true" ]]; then
  send_custom
  exit 0
fi

# --file 指定任意请求 JSON 时只发一次
if [[ -n "$REQ_FILE" ]]; then
  send_round 1
  exit 0
fi

case "$ROUND" in
  1)    send_round 1 ;;
  2)    send_round 2 ;;
  3)    send_round 3 ;;
  all)  send_round 1; send_round 2; send_round 3 ;;
  *)    echo "ERROR: --round must be 1|2|3|all, got: $ROUND" >&2; exit 1 ;;
esac

echo
echo "Done. 想观察最终发给远端 Versatile 的请求，看服务端日志:"
if [[ -f "$DEMO_DIR/target/demo.log" ]]; then
  echo "  tail -f $DEMO_DIR/target/demo.log | grep -E 'Versatile (remote|outbound) request'"
else
  echo "  tail -f $DEMO_DIR/logs/demo.log | grep -E 'Versatile (remote|outbound) request'"
fi
