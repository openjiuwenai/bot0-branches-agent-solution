# DeepAgent 意图路由银行多 Agent 示例

本示例由五个独立 Spring Boot 服务组成，用于验证 DeepAgent 意图匹配、A2A Agent Card 目录、
本地工具路由、远端中断续接、意图变化后的重新匹配，以及复杂任务的规划与逐项执行。

| 服务 | 端口 | 职责 |
|---|---:|---|
| IntentBankRouter | 18200 | 统一入口；调用 `intent_match`，执行本地工具或委托业务 Agent |
| BalanceAgent | 18201 | 查询账户余额 |
| TransferAgent | 18202 | 追问转账信息并在确认后执行 |
| WealthAdvisorAgent | 18203 | 推荐理财产品 |
| WealthPurchaseAgent | 18204 | 追问购买信息并在确认后执行 |

所有 Agent 均由 DeepAgent 构建。IntentBankRouter 从四个业务 Agent 的 Agent Card Skill 建立意图
目录；计算器、日期和天气是只通过意图目录暴露的本地工具。入口服务关闭普通的逐 Agent Card Tool
注入，因此模型只通过 `intent_match` 选择并执行目标能力。

## 1. 准备环境

手工验证需要以下命令：

```bash
java -version
mvn -version
curl --version
python3 --version
```

建议使用 JDK 17 或项目当前声明的 JDK 版本。后续命令均从本示例目录执行：

```bash
cd common/example/bank-intent-routing-a2a-demo
```

确认端口没有被其他进程占用：

```bash
for port in 18200 18201 18202 18203 18204; do
  curl -fsS "http://127.0.0.1:${port}/health" && echo "port ${port} is already in use"
done
```

如果某个端口已有服务，请先停止该服务，或通过对应环境变量修改五个服务的端口和远端地址。

## 2. 配置真实模型

复制可提交的配置模板：

```bash
cp application-intent_local-example.yml application-intent_local.yml
```

编辑 `application-intent_local.yml`，填写 LLM 和 reranker 的真实地址、模型及密钥。该文件已被仓库
根目录的 `.gitignore` 忽略，不能提交。检查忽略规则是否生效：

```bash
git check-ignore -v application-intent_local.yml
```

五个模块都包含以下配置：

```yaml
spring:
  config:
    import: optional:file:./application-intent_local.yml
```

因此必须从本示例根目录启动五个 Jar。Spring Boot 会自动加载本地 YAML，不需要 `.env.local` 或
额外的环境变量加载器。

## 3. 构建跨仓依赖和示例

先在 `agent-runtime-java` 仓安装当前 Runtime：

```bash
cd /path/to/agent-runtime-java
mvn -pl :agent-service-app -am clean install -DskipTests -Drevision=0.1.1.post1
```

再在 `agent-solution` 仓安装 Core 扩展和 Runtime 扩展：

```bash
cd /path/to/agent-solution
mvn -f common/agent-core-ext-java/pom.xml clean install
mvn -f common/agent-runtime-ext-java/pom.xml clean install
```

最后回到示例目录完成打包：

```bash
cd common/example/bank-intent-routing-a2a-demo
mvn clean package
```

构建成功后应生成以下五个 Jar：

```text
balance-agent-runtime/target/intent-bank-balance-agent-runtime-0.1.0.jar
transfer-agent-runtime/target/intent-bank-transfer-agent-runtime-0.1.0.jar
wealth-advisor-agent-runtime/target/intent-bank-wealth-advisor-agent-runtime-0.1.0.jar
wealth-purchase-agent-runtime/target/intent-bank-wealth-purchase-agent-runtime-0.1.0.jar
intent-agent-runtime/target/intent-bank-intent-agent-runtime-0.1.0.jar
```

## 4. 启动五个服务

以下命令在后台启动服务，并将日志统一保存到 `/tmp/bank-intent-manual`。保持当前终端不要退出。

```bash
export MANUAL_DIR=/tmp/bank-intent-manual
mkdir -p "$MANUAL_DIR"

java -jar balance-agent-runtime/target/intent-bank-balance-agent-runtime-0.1.0.jar \
  >"$MANUAL_DIR/balance.log" 2>&1 & echo $! >"$MANUAL_DIR/balance.pid"

java -jar transfer-agent-runtime/target/intent-bank-transfer-agent-runtime-0.1.0.jar \
  >"$MANUAL_DIR/transfer.log" 2>&1 & echo $! >"$MANUAL_DIR/transfer.pid"

java -jar wealth-advisor-agent-runtime/target/intent-bank-wealth-advisor-agent-runtime-0.1.0.jar \
  >"$MANUAL_DIR/wealth-advisor.log" 2>&1 & echo $! >"$MANUAL_DIR/wealth-advisor.pid"

java -jar wealth-purchase-agent-runtime/target/intent-bank-wealth-purchase-agent-runtime-0.1.0.jar \
  >"$MANUAL_DIR/wealth-purchase.log" 2>&1 & echo $! >"$MANUAL_DIR/wealth-purchase.pid"
```

等待四个业务 Agent 启动完成：

```bash
for port in 18201 18202 18203 18204; do
  until curl -fsS "http://127.0.0.1:${port}/health"; do sleep 2; done
  echo
done
```

四个业务 Agent 健康后再启动入口 Agent：

```bash
java -jar intent-agent-runtime/target/intent-bank-intent-agent-runtime-0.1.0.jar \
  >"$MANUAL_DIR/intent.log" 2>&1 & echo $! >"$MANUAL_DIR/intent.pid"

until curl -fsS http://127.0.0.1:18200/health; do sleep 2; done
echo
```

检查五个健康接口。每个响应都应包含 `"status":"healthy"`：

```bash
for port in 18200 18201 18202 18203 18204; do
  echo "===== ${port} ====="
  curl -fsS "http://127.0.0.1:${port}/health"
  echo
done
```

检查五张 Agent Card：

```bash
for port in 18200 18201 18202 18203 18204; do
  echo "===== Agent Card ${port} ====="
  curl -fsS "http://127.0.0.1:${port}/.well-known/agent-card.json" | python3 -m json.tool
done
```

重点确认业务 Agent Card 分别包含 `query_balance`、`execute_transfer`、`recommend_wealth` 和
`purchase_wealth` Skill。入口日志中应能看到 Agent Card 目录初始化或替换记录：

```bash
grep -E 'Intent catalog|Agent Card|catalog' "$MANUAL_DIR/intent.log" | tail -n 30
```

## 5. 准备手工请求工具

所有请求都发送到入口 Agent 的 A2A 流式接口 `http://127.0.0.1:18200/a2a/`。在当前终端定义下面
三个函数。`send_a2a` 会先打印完整请求，再保存并打印服务器返回的完整 SSE 响应。

```bash
send_a2a() {
  request_file="$1"
  response_file="$2"
  echo "===== REQUEST: ${request_file} ====="
  python3 -m json.tool "$request_file"
  echo "===== RESPONSE: ${response_file} ====="
  curl -fsS -N --max-time 600 -X POST http://127.0.0.1:18200/a2a/ \
    -H 'Content-Type: application/json' \
    -H 'Accept: text/event-stream' \
    --data-binary "@${request_file}" | tee "$response_file"
}

last_state() {
  python3 - "$1" <<'PY'
import json, sys
states = []
for line in open(sys.argv[1], encoding="utf-8"):
    if not line.startswith("data:"):
        continue
    result = json.loads(line[5:].strip()).get("result") or {}
    status = (result.get("statusUpdate") or {}).get("status") or {}
    if status.get("state"):
        states.append(status["state"])
print(states[-1] if states else "NO_STATE")
PY
}

task_id() {
  python3 - "$1" <<'PY'
import json, sys
ids = []
for line in open(sys.argv[1], encoding="utf-8"):
    if not line.startswith("data:"):
        continue
    result = json.loads(line[5:].strip()).get("result") or {}
    update = result.get("statusUpdate") or result.get("artifactUpdate") or {}
    if update.get("taskId"):
        ids.append(str(update["taskId"]))
print(ids[-1] if ids else "")
PY
}
```

首次请求只需要 `contextId`。当响应进入 `TASK_STATE_INPUT_REQUIRED` 时，后续请求必须同时复用原
`contextId` 和响应中的 `taskId`，这样 Runtime 才会恢复原 A2A Task。普通的新一轮对话只复用
`contextId`，不携带旧 `taskId`。

模型输出措辞可能略有变化。手工验收时应以最终 Task 状态、业务关键字段和服务日志为准。

## 6. 基础路由验证

### 6.1 远端余额 Agent

```bash
cat >"$MANUAL_DIR/balance-request.json" <<'JSON'
{
  "jsonrpc": "2.0",
  "id": "manual-balance-1",
  "method": "SendStreamingMessage",
  "params": {
    "message": {
      "role": "ROLE_USER",
      "contextId": "manual-balance-context",
      "parts": [{"text": "查询我的账户余额"}]
    }
  }
}
JSON
send_a2a "$MANUAL_DIR/balance-request.json" "$MANUAL_DIR/balance-response.sse"
last_state "$MANUAL_DIR/balance-response.sse"
```

预期最终状态为 `TASK_STATE_COMPLETED`，响应包含余额 `12800.5` 或等价展示。确认请求实际路由到
BalanceAgent：

```bash
grep 'BANK_DEMO_EXECUTION tool=query_balance' "$MANUAL_DIR/balance.log" | tail -n 1
grep -E 'Intent selected|intent_match|a2a_delegate' "$MANUAL_DIR/intent.log" | tail -n 20
```

### 6.2 入口 Agent 本地计算器

```bash
cat >"$MANUAL_DIR/calculator-request.json" <<'JSON'
{
  "jsonrpc": "2.0",
  "id": "manual-calculator-1",
  "method": "SendStreamingMessage",
  "params": {
    "message": {
      "role": "ROLE_USER",
      "contextId": "manual-calculator-context",
      "parts": [{"text": "帮我计算 6 * 7"}]
    }
  }
}
JSON
send_a2a "$MANUAL_DIR/calculator-request.json" "$MANUAL_DIR/calculator-response.sse"
last_state "$MANUAL_DIR/calculator-response.sse"
```

预期最终状态为 `TASK_STATE_COMPLETED`，结果包含 `42`。入口日志应包含本地工具执行记录：

```bash
grep 'BANK_DEMO_EXECUTION tool=bank_calculator' "$MANUAL_DIR/intent.log" | tail -n 1
```

### 6.3 日期和天气本地工具

日期请求：

```bash
cat >"$MANUAL_DIR/date-request.json" <<'JSON'
{
  "jsonrpc": "2.0",
  "id": "manual-date-1",
  "method": "SendStreamingMessage",
  "params": {
    "message": {
      "role": "ROLE_USER",
      "contextId": "manual-date-context",
      "parts": [{"text": "今天是几号"}]
    }
  }
}
JSON
send_a2a "$MANUAL_DIR/date-request.json" "$MANUAL_DIR/date-response.sse"
last_state "$MANUAL_DIR/date-response.sse"
```

天气请求：

```bash
cat >"$MANUAL_DIR/weather-request.json" <<'JSON'
{
  "jsonrpc": "2.0",
  "id": "manual-weather-1",
  "method": "SendStreamingMessage",
  "params": {
    "message": {
      "role": "ROLE_USER",
      "contextId": "manual-weather-context",
      "parts": [{"text": "深圳天气怎么样"}]
    }
  }
}
JSON
send_a2a "$MANUAL_DIR/weather-request.json" "$MANUAL_DIR/weather-response.sse"
last_state "$MANUAL_DIR/weather-response.sse"
```

两个请求的最终状态都应为 `TASK_STATE_COMPLETED`。日期响应包含当天日期，天气响应包含“深圳”；入口
日志分别包含 `current_date` 和 `weather_query` 的本地执行记录。

### 6.4 fallback

```bash
cat >"$MANUAL_DIR/fallback-request.json" <<'JSON'
{
  "jsonrpc": "2.0",
  "id": "manual-fallback-1",
  "method": "SendStreamingMessage",
  "params": {
    "message": {
      "role": "ROLE_USER",
      "contextId": "manual-fallback-context",
      "parts": [{"text": "请帮我写一首关于星空的诗"}]
    }
  }
}
JSON
send_a2a "$MANUAL_DIR/fallback-request.json" "$MANUAL_DIR/fallback-response.sse"
last_state "$MANUAL_DIR/fallback-response.sse"
```

预期最终状态为 `TASK_STATE_COMPLETED`，响应说明入口只支持银行相关能力；入口日志中的意图结果应
包含 `FALLBACK` 和 `bank-intent-fallback`。

## 7. 转账追问、确认和同 Task 续接

本场景从缺少收款人和金额的请求开始，连续进行三次续接。四次请求必须使用同一个
`manual-transfer-context`；第二至第四次还必须携带第一次响应返回的同一个 `taskId`。

### 7.1 发起信息不完整的转账

```bash
cat >"$MANUAL_DIR/transfer-1-request.json" <<'JSON'
{
  "jsonrpc": "2.0",
  "id": "manual-transfer-1",
  "method": "SendStreamingMessage",
  "params": {
    "message": {
      "role": "ROLE_USER",
      "contextId": "manual-transfer-context",
      "parts": [{"text": "我要转账"}]
    }
  }
}
JSON
send_a2a "$MANUAL_DIR/transfer-1-request.json" "$MANUAL_DIR/transfer-1-response.sse"
last_state "$MANUAL_DIR/transfer-1-response.sse"
export TRANSFER_TASK_ID="$(task_id "$MANUAL_DIR/transfer-1-response.sse")"
echo "$TRANSFER_TASK_ID"
```

预期状态为 `TASK_STATE_INPUT_REQUIRED`，问题要求补充收款人和金额，且
`TRANSFER_TASK_ID` 不是空字符串。

### 7.2 补充收款人

```bash
cat >"$MANUAL_DIR/transfer-2-request.json" <<JSON
{
  "jsonrpc": "2.0",
  "id": "manual-transfer-2",
  "method": "SendStreamingMessage",
  "params": {
    "message": {
      "role": "ROLE_USER",
      "contextId": "manual-transfer-context",
      "taskId": "${TRANSFER_TASK_ID}",
      "parts": [{"text": "收款人是李四"}]
    }
  }
}
JSON
send_a2a "$MANUAL_DIR/transfer-2-request.json" "$MANUAL_DIR/transfer-2-response.sse"
last_state "$MANUAL_DIR/transfer-2-response.sse"
```

预期仍为 `TASK_STATE_INPUT_REQUIRED`，继续询问转账金额。

### 7.3 补充金额

```bash
cat >"$MANUAL_DIR/transfer-3-request.json" <<JSON
{
  "jsonrpc": "2.0",
  "id": "manual-transfer-3",
  "method": "SendStreamingMessage",
  "params": {
    "message": {
      "role": "ROLE_USER",
      "contextId": "manual-transfer-context",
      "taskId": "${TRANSFER_TASK_ID}",
      "parts": [{"text": "金额是200元"}]
    }
  }
}
JSON
send_a2a "$MANUAL_DIR/transfer-3-request.json" "$MANUAL_DIR/transfer-3-response.sse"
last_state "$MANUAL_DIR/transfer-3-response.sse"
```

预期仍为 `TASK_STATE_INPUT_REQUIRED`，确认文案必须同时包含“李四”和“200元”。

### 7.4 确认执行

```bash
cat >"$MANUAL_DIR/transfer-4-request.json" <<JSON
{
  "jsonrpc": "2.0",
  "id": "manual-transfer-4",
  "method": "SendStreamingMessage",
  "params": {
    "message": {
      "role": "ROLE_USER",
      "contextId": "manual-transfer-context",
      "taskId": "${TRANSFER_TASK_ID}",
      "parts": [{"text": "确认"}]
    }
  }
}
JSON
send_a2a "$MANUAL_DIR/transfer-4-request.json" "$MANUAL_DIR/transfer-4-response.sse"
last_state "$MANUAL_DIR/transfer-4-response.sse"
```

预期最终状态为 `TASK_STATE_COMPLETED`，结果包含收款人李四和金额 200。确认有且仅有确认后才执行
真实业务函数：

```bash
grep 'BANK_DEMO_EXECUTION tool=execute_transfer' "$MANUAL_DIR/transfer.log" | tail -n 1
```

## 8. 理财购买确认

### 8.1 发起购买

```bash
cat >"$MANUAL_DIR/purchase-1-request.json" <<'JSON'
{
  "jsonrpc": "2.0",
  "id": "manual-purchase-1",
  "method": "SendStreamingMessage",
  "params": {
    "message": {
      "role": "ROLE_USER",
      "contextId": "manual-purchase-context",
      "parts": [{"text": "购买一万元稳盈90天"}]
    }
  }
}
JSON
send_a2a "$MANUAL_DIR/purchase-1-request.json" "$MANUAL_DIR/purchase-1-response.sse"
last_state "$MANUAL_DIR/purchase-1-response.sse"
export PURCHASE_TASK_ID="$(task_id "$MANUAL_DIR/purchase-1-response.sse")"
```

预期为 `TASK_STATE_INPUT_REQUIRED`，确认文案包含“稳盈90天”和“10000元”。

### 8.2 确认购买

```bash
cat >"$MANUAL_DIR/purchase-2-request.json" <<JSON
{
  "jsonrpc": "2.0",
  "id": "manual-purchase-2",
  "method": "SendStreamingMessage",
  "params": {
    "message": {
      "role": "ROLE_USER",
      "contextId": "manual-purchase-context",
      "taskId": "${PURCHASE_TASK_ID}",
      "parts": [{"text": "确认"}]
    }
  }
}
JSON
send_a2a "$MANUAL_DIR/purchase-2-request.json" "$MANUAL_DIR/purchase-2-response.sse"
last_state "$MANUAL_DIR/purchase-2-response.sse"
```

预期最终状态为 `TASK_STATE_COMPLETED`。验证 WealthPurchaseAgent 确实执行：

```bash
grep 'BANK_DEMO_EXECUTION tool=purchase_wealth' "$MANUAL_DIR/wealth-purchase.log" | tail -n 1
```

## 9. 同一会话中的语义指代

本场景先推荐产品，再使用“刚才推荐的第一个产品”发起新任务。第二次请求复用 `contextId`，但它是
新任务，因此不能携带第一次已经完成的 `taskId`。

### 9.1 获取推荐

```bash
cat >"$MANUAL_DIR/reference-1-request.json" <<'JSON'
{
  "jsonrpc": "2.0",
  "id": "manual-reference-1",
  "method": "SendStreamingMessage",
  "params": {
    "message": {
      "role": "ROLE_USER",
      "contextId": "manual-reference-context",
      "parts": [{"text": "推荐一款稳健的三个月理财"}]
    }
  }
}
JSON
send_a2a "$MANUAL_DIR/reference-1-request.json" "$MANUAL_DIR/reference-1-response.sse"
last_state "$MANUAL_DIR/reference-1-response.sse"
```

预期为 `TASK_STATE_COMPLETED`，推荐结果包含“稳盈90天”。

### 9.2 使用上一轮信息购买

```bash
cat >"$MANUAL_DIR/reference-2-request.json" <<'JSON'
{
  "jsonrpc": "2.0",
  "id": "manual-reference-2",
  "method": "SendStreamingMessage",
  "params": {
    "message": {
      "role": "ROLE_USER",
      "contextId": "manual-reference-context",
      "parts": [{"text": "购买刚才推荐的第一个产品，投入5000元"}]
    }
  }
}
JSON
send_a2a "$MANUAL_DIR/reference-2-request.json" "$MANUAL_DIR/reference-2-response.sse"
last_state "$MANUAL_DIR/reference-2-response.sse"
export REFERENCE_TASK_ID="$(task_id "$MANUAL_DIR/reference-2-response.sse")"
```

预期为 `TASK_STATE_INPUT_REQUIRED`，确认文案应将指代解析为“稳盈90天”，并包含“5000元”。

### 9.3 确认指代后的购买

```bash
cat >"$MANUAL_DIR/reference-3-request.json" <<JSON
{
  "jsonrpc": "2.0",
  "id": "manual-reference-3",
  "method": "SendStreamingMessage",
  "params": {
    "message": {
      "role": "ROLE_USER",
      "contextId": "manual-reference-context",
      "taskId": "${REFERENCE_TASK_ID}",
      "parts": [{"text": "确认"}]
    }
  }
}
JSON
send_a2a "$MANUAL_DIR/reference-3-request.json" "$MANUAL_DIR/reference-3-response.sse"
last_state "$MANUAL_DIR/reference-3-response.sse"
```

预期最终状态为 `TASK_STATE_COMPLETED`，结果包含“稳盈90天”和“5000元”。

## 10. 中断期间发生意图变化

本场景先进入转账确认中断，然后在同一个 A2A Task 中输入新的理财购买意图。TransferAgent 不执行
原转账，入口 Agent 使用最新语义重新调用 `intent_match`，并进入理财购买确认。

### 10.1 发起转账并等待确认

```bash
cat >"$MANUAL_DIR/change-1-request.json" <<'JSON'
{
  "jsonrpc": "2.0",
  "id": "manual-change-1",
  "method": "SendStreamingMessage",
  "params": {
    "message": {
      "role": "ROLE_USER",
      "contextId": "manual-change-context",
      "parts": [{"text": "给王五转50元"}]
    }
  }
}
JSON
send_a2a "$MANUAL_DIR/change-1-request.json" "$MANUAL_DIR/change-1-response.sse"
last_state "$MANUAL_DIR/change-1-response.sse"
export CHANGE_TASK_ID="$(task_id "$MANUAL_DIR/change-1-response.sse")"
```

预期为 `TASK_STATE_INPUT_REQUIRED`，确认文案包含“王五”和“50元”。

### 10.2 使用同一 Task 输入新意图

```bash
cat >"$MANUAL_DIR/change-2-request.json" <<JSON
{
  "jsonrpc": "2.0",
  "id": "manual-change-2",
  "method": "SendStreamingMessage",
  "params": {
    "message": {
      "role": "ROLE_USER",
      "contextId": "manual-change-context",
      "taskId": "${CHANGE_TASK_ID}",
      "parts": [{"text": "改为购买1000元稳盈90天理财"}]
    }
  }
}
JSON
send_a2a "$MANUAL_DIR/change-2-request.json" "$MANUAL_DIR/change-2-response.sse"
last_state "$MANUAL_DIR/change-2-response.sse"
```

预期仍为 `TASK_STATE_INPUT_REQUIRED`，但确认文案已经变为购买“稳盈90天”理财 1000 元，而不是确认
原来的转账。

### 10.3 确认新意图

```bash
cat >"$MANUAL_DIR/change-3-request.json" <<JSON
{
  "jsonrpc": "2.0",
  "id": "manual-change-3",
  "method": "SendStreamingMessage",
  "params": {
    "message": {
      "role": "ROLE_USER",
      "contextId": "manual-change-context",
      "taskId": "${CHANGE_TASK_ID}",
      "parts": [{"text": "确认"}]
    }
  }
}
JSON
send_a2a "$MANUAL_DIR/change-3-request.json" "$MANUAL_DIR/change-3-response.sse"
last_state "$MANUAL_DIR/change-3-response.sse"
```

预期最终状态为 `TASK_STATE_COMPLETED`，结果包含“稳盈90天”和“1000元”。确认原转账没有执行，
新理财购买执行一次：

```bash
if grep -q 'recipient=王五' "$MANUAL_DIR/transfer.log"; then
  echo 'FAIL: abandoned transfer was executed'
else
  echo 'PASS: abandoned transfer was not executed'
fi
grep 'BANK_DEMO_EXECUTION tool=purchase_wealth' "$MANUAL_DIR/wealth-purchase.log" | tail -n 1
```

## 11. 多目标转账规划并逐笔执行

该场景必须先由入口 DeepAgent 调用 `todo_create` 生成两步计划，再逐次调用意图工具。每一笔转账仍由
TransferAgent 单独处理和确认。

### 11.1 发起复杂请求

```bash
cat >"$MANUAL_DIR/plan-1-request.json" <<'JSON'
{
  "jsonrpc": "2.0",
  "id": "manual-plan-1",
  "method": "SendStreamingMessage",
  "params": {
    "message": {
      "role": "ROLE_USER",
      "contextId": "manual-plan-context",
      "parts": [{"text": "给张三和李四各转100元"}]
    }
  }
}
JSON
send_a2a "$MANUAL_DIR/plan-1-request.json" "$MANUAL_DIR/plan-1-response.sse"
last_state "$MANUAL_DIR/plan-1-response.sse"
export PLAN_TASK_ID="$(task_id "$MANUAL_DIR/plan-1-response.sse")"
```

预期为 `TASK_STATE_INPUT_REQUIRED`。在首次确认请求之前，完整 SSE 响应应先出现
`bank_plan_progress` Artifact，内容至少包括：

```text
执行计划
1. 给张三转账100元
2. 给李四转账100元
当前执行第 1/2 步
请确认是否向张三转账100元
```

### 11.2 确认第一笔转账

```bash
cat >"$MANUAL_DIR/plan-2-request.json" <<JSON
{
  "jsonrpc": "2.0",
  "id": "manual-plan-2",
  "method": "SendStreamingMessage",
  "params": {
    "message": {
      "role": "ROLE_USER",
      "contextId": "manual-plan-context",
      "taskId": "${PLAN_TASK_ID}",
      "parts": [{"text": "确认"}]
    }
  }
}
JSON
send_a2a "$MANUAL_DIR/plan-2-request.json" "$MANUAL_DIR/plan-2-response.sse"
last_state "$MANUAL_DIR/plan-2-response.sse"
```

预期仍为 `TASK_STATE_INPUT_REQUIRED`。响应应先显示“第 1/2 步已完成：给张三转账100元”，然后显示
“当前执行第 2/2 步”并要求确认给李四转账 100 元。

### 11.3 确认第二笔转账

```bash
cat >"$MANUAL_DIR/plan-3-request.json" <<JSON
{
  "jsonrpc": "2.0",
  "id": "manual-plan-3",
  "method": "SendStreamingMessage",
  "params": {
    "message": {
      "role": "ROLE_USER",
      "contextId": "manual-plan-context",
      "taskId": "${PLAN_TASK_ID}",
      "parts": [{"text": "确认"}]
    }
  }
}
JSON
send_a2a "$MANUAL_DIR/plan-3-request.json" "$MANUAL_DIR/plan-3-response.sse"
last_state "$MANUAL_DIR/plan-3-response.sse"
```

预期最终状态为 `TASK_STATE_COMPLETED`，汇总结果同时包含张三、李四和两笔 100 元转账。检查入口和
转账日志：

```bash
grep 'BANK_DEMO_TOOL_CALL tool=todo_create' "$MANUAL_DIR/intent.log" | tail -n 1
grep 'BANK_DEMO_TOOL_CALL tool=intent_match' "$MANUAL_DIR/intent.log" | tail -n 2
grep 'BANK_DEMO_EXECUTION tool=execute_transfer' "$MANUAL_DIR/transfer.log" | tail -n 2
```

日志顺序应为一次 `todo_create`、两次单点 `intent_match`；TransferAgent 应分别执行张三和李四的
转账，而不是一次执行两个收款人。

## 12. 自动化端到端验收

手工验证完成后，可运行自动化脚本复核全部场景。如果第 4 节启动的服务仍在运行，请先按第 13 节
停止服务，避免端口冲突。

```bash
bash smoke-bank-intent.sh
```

需要保留自动化脚本启动的五个进程日志和响应作为验收证据时使用：

```bash
BANK_INTENT_KEEP_ARTIFACTS=true bash smoke-bank-intent.sh
```

Windows PowerShell 使用：

```powershell
./smoke-bank-intent.ps1 -KeepArtifacts
```

自动脚本统一使用 A2A `SendStreamingMessage`，打印每次完整 JSON-RPC 请求和完整 SSE 事件序列，并
校验 Task 状态、用户可见结果、规划 Artifact、业务 Tool 执行次数及执行顺序。

## 13. 停止服务

完成验证后停止第 4 节启动的五个进程：

```bash
for name in intent balance transfer wealth-advisor wealth-purchase; do
  pid_file="$MANUAL_DIR/${name}.pid"
  if [ -f "$pid_file" ]; then
    kill "$(cat "$pid_file")" 2>/dev/null || true
  fi
done
```

确认端口已经释放：

```bash
for port in 18200 18201 18202 18203 18204; do
  if curl -fsS "http://127.0.0.1:${port}/health" >/dev/null 2>&1; then
    echo "port ${port} is still in use"
  else
    echo "port ${port} stopped"
  fi
done
```

手工请求、完整 SSE 响应和服务日志保存在 `/tmp/bank-intent-manual`，需要时可直接归档该目录。
