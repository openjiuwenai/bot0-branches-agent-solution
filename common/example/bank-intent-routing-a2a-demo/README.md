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

## 使用 smoke 脚本快速验证

smoke 脚本会自动构建示例，按顺序启动四个业务 Agent 和入口 Agent，检查健康状态与 Agent Card，
执行全部端到端场景，并在验证结束后停止服务。脚本可从任意工作目录运行。

运行前需要准备 JDK 17、Maven 和可访问的 LLM、reranker。Bash 脚本还需要 `curl` 和 Python 3；
PowerShell 脚本需要 PowerShell 7 或 Windows PowerShell。五个默认端口 `18200` 至 `18204` 不能被
其他进程占用。首次运行前还需将当前功能分支的 Runtime、Core 扩展和 Runtime 扩展安装到本地
Maven 仓库，具体命令见“手工逐步验证”的“构建跨仓依赖和示例”。

### 配置真实模型

复制可提交的配置模板：

```bash
cd /path/to/agent-solution/common/example/bank-intent-routing-a2a-demo
cp application-intent_local-example.yml application-intent_local.yml
```

编辑 `application-intent_local.yml`，填写 LLM 和 reranker 的真实地址、模型及密钥。该文件已被仓库
根目录的 `.gitignore` 忽略，不能提交。

五个模块都包含以下配置：

```yaml
spring:
  config:
    import: optional:file:./application-intent_local.yml
```

Spring Boot 会自动加载本地 YAML，不需要 `.env.local` 或额外的环境变量加载器。smoke 脚本会自动
使用本示例目录作为工作目录；手工运行 Jar 时也必须从本示例目录启动。

### 执行脚本

Linux、macOS 或 Git Bash：

```bash
bash /path/to/agent-solution/common/example/bank-intent-routing-a2a-demo/smoke-bank-intent.sh
```

PowerShell：

```powershell
& "C:\path\to\agent-solution\common\example\bank-intent-routing-a2a-demo\smoke-bank-intent.ps1"
```

脚本会打印每次发送的完整 JSON-RPC 报文、收到的完整 SSE 事件和每个场景的 `PASS` 结果。最后看到
以下内容表示全部验证通过：

```text
PASS: business routing and exact execution audit
All bank intent routing scenarios passed.
```

脚本覆盖以下场景：

| 场景 | 预期行为 |
|---|---|
| 余额、理财推荐 | 分别路由到正确的远端业务 Agent |
| 计算器、日期、天气 | 执行入口 Agent 的本地意图函数 |
| fallback | 返回银行能力范围提示，不调用业务 Agent |
| 转账追问与确认 | 补齐收款人和金额，在同一 A2A Task 中确认后执行 |
| 理财购买确认 | 在同一 A2A Task 中确认后执行购买 |
| 语义指代 | 根据同一会话的推荐结果解析“刚才推荐的第一个产品” |
| 意图变化 | 转账中断期间切换到理财购买，重新执行意图匹配 |
| 多目标转账 | 先创建计划，再逐笔路由、确认和执行 |

成功时脚本默认停止五个服务并删除临时产物。需要保留本次运行的日志和 A2A 请求、响应时使用：

```bash
BANK_INTENT_KEEP_ARTIFACTS=true \
  bash /path/to/agent-solution/common/example/bank-intent-routing-a2a-demo/smoke-bank-intent.sh
```

```powershell
& "C:\path\to\agent-solution\common\example\bank-intent-routing-a2a-demo\smoke-bank-intent.ps1" `
  -KeepArtifacts
```

Bash 脚本可通过 `BANK_INTENT_REQUEST_TIMEOUT_SECONDS` 调整单次请求超时；PowerShell 使用
`-RequestTimeoutSeconds`：

```bash
BANK_INTENT_REQUEST_TIMEOUT_SECONDS=900 bash smoke-bank-intent.sh
```

```powershell
./smoke-bank-intent.ps1 -RequestTimeoutSeconds 900
```

验证失败时脚本会停止已启动的服务，打印临时目录位置并保留日志；应先查看 `intent.log` 和对应业务
Agent 日志。PowerShell 运行时，标准输出和错误日志分别保存为 `*.out.log` 和 `*.err.log`。

## 手工逐步验证

以下章节用于开发者手工启动服务、逐条发送完整 A2A 报文，并观察每一步的 Task 状态和执行日志。
后续命令均从本示例目录执行：

```bash
cd /path/to/agent-solution/common/example/bank-intent-routing-a2a-demo
```

### 1. 构建跨仓依赖和示例

构建前请确认 `agent-core-java`、`agent-runtime-java` 和 `agent-solution` 使用相互配套的版本。
先在 `agent-runtime-java` 仓安装当前配套 Runtime：

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

### 2. 启动五个服务

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

### 3. 读取流式响应

所有业务请求都直接发送到入口 Agent 的 A2A 流式接口 `http://127.0.0.1:18200/a2a/`，不直接调用
四个业务 Agent。下面每条 `curl` 命令都会实时打印完整 SSE 响应，并通过 `tee` 保存到
`$MANUAL_DIR`。

流式响应由多行 `data:` 事件组成。可直接观察或检索响应文件：

```bash
# 查看完整响应
less "$MANUAL_DIR/transfer-1-response.sse"

# 查看最后一个 Task 状态
grep -o 'TASK_STATE_[A-Z_]*' "$MANUAL_DIR/transfer-1-response.sse" | tail -n 1

# 查看响应中的外层 Task ID
grep -o '"taskId":"[^"]*"' "$MANUAL_DIR/transfer-1-response.sse" | head -n 1
```

首次请求只需要 `contextId`。首轮进入 `TASK_STATE_INPUT_REQUIRED` 后，从响应中复制 `taskId`，将后续
报文中的 `TASK_ID_FROM_FIRST_RESPONSE` 替换为该值；后续请求必须同时复用原 `contextId` 和
`taskId`，Runtime 才会恢复原 A2A Task。普通的新一轮对话只复用 `contextId`，不携带已完成 Task
的 `taskId`。

模型输出措辞可能略有变化。手工验收时应以最终 Task 状态、业务关键字段和服务日志为准。

### 4. 基础路由验证

#### 4.1 远端余额 Agent

```bash
curl -sS -N --max-time 600 -X POST http://127.0.0.1:18200/a2a/ \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  --data-binary @- <<'JSON' | tee "$MANUAL_DIR/balance-response.sse"
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
```

预期最终状态为 `TASK_STATE_COMPLETED`，响应包含余额 `12800.5` 或等价展示。确认请求实际路由到
BalanceAgent：

```bash
grep 'BANK_DEMO_EXECUTION tool=query_balance' "$MANUAL_DIR/balance.log" | tail -n 1
grep -E 'Intent selected|intent_match|a2a_delegate' "$MANUAL_DIR/intent.log" | tail -n 20
```

#### 4.2 入口 Agent 本地计算器

```bash
curl -sS -N --max-time 600 -X POST http://127.0.0.1:18200/a2a/ \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  --data-binary @- <<'JSON' | tee "$MANUAL_DIR/calculator-response.sse"
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
```

预期最终状态为 `TASK_STATE_COMPLETED`，结果包含 `42`。入口日志应包含本地工具执行记录：

```bash
grep 'BANK_DEMO_EXECUTION tool=bank_calculator' "$MANUAL_DIR/intent.log" | tail -n 1
```

#### 4.3 日期和天气本地工具

日期请求：

```bash
curl -sS -N --max-time 600 -X POST http://127.0.0.1:18200/a2a/ \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  --data-binary @- <<'JSON' | tee "$MANUAL_DIR/date-response.sse"
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
```

天气请求：

```bash
curl -sS -N --max-time 600 -X POST http://127.0.0.1:18200/a2a/ \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  --data-binary @- <<'JSON' | tee "$MANUAL_DIR/weather-response.sse"
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
```

两个请求的最终状态都应为 `TASK_STATE_COMPLETED`。日期响应包含当天日期，天气响应包含“深圳”；入口
日志分别包含 `current_date` 和 `weather_query` 的本地执行记录。

#### 4.4 fallback

```bash
curl -sS -N --max-time 600 -X POST http://127.0.0.1:18200/a2a/ \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  --data-binary @- <<'JSON' | tee "$MANUAL_DIR/fallback-response.sse"
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
```

预期最终状态为 `TASK_STATE_COMPLETED`，响应说明入口只支持银行相关能力；入口日志中的意图结果应
包含 `FALLBACK` 和 `bank-intent-fallback`。

### 5. 转账追问、确认和同 Task 续接

本场景从缺少收款人和金额的请求开始，连续进行三次续接。四次请求必须使用同一个
`manual-transfer-context`；第二至第四次还必须携带第一次响应返回的同一个 `taskId`。

#### 5.1 发起信息不完整的转账

```bash
curl -sS -N --max-time 600 -X POST http://127.0.0.1:18200/a2a/ \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  --data-binary @- <<'JSON' | tee "$MANUAL_DIR/transfer-1-response.sse"
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
```

预期状态为 `TASK_STATE_INPUT_REQUIRED`，问题要求补充收款人和金额。从响应中复制外层 `taskId`，
在后续三条报文中替换 `TASK_ID_FROM_FIRST_RESPONSE`。

#### 5.2 补充收款人

```bash
curl -sS -N --max-time 600 -X POST http://127.0.0.1:18200/a2a/ \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  --data-binary @- <<'JSON' | tee "$MANUAL_DIR/transfer-2-response.sse"
{
  "jsonrpc": "2.0",
  "id": "manual-transfer-2",
  "method": "SendStreamingMessage",
  "params": {
    "message": {
      "role": "ROLE_USER",
      "contextId": "manual-transfer-context",
      "taskId": "TASK_ID_FROM_FIRST_RESPONSE",
      "parts": [{"text": "收款人是李四"}]
    }
  }
}
JSON
```

预期仍为 `TASK_STATE_INPUT_REQUIRED`，继续询问转账金额。

#### 5.3 补充金额

```bash
curl -sS -N --max-time 600 -X POST http://127.0.0.1:18200/a2a/ \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  --data-binary @- <<'JSON' | tee "$MANUAL_DIR/transfer-3-response.sse"
{
  "jsonrpc": "2.0",
  "id": "manual-transfer-3",
  "method": "SendStreamingMessage",
  "params": {
    "message": {
      "role": "ROLE_USER",
      "contextId": "manual-transfer-context",
      "taskId": "TASK_ID_FROM_FIRST_RESPONSE",
      "parts": [{"text": "金额是200元"}]
    }
  }
}
JSON
```

预期仍为 `TASK_STATE_INPUT_REQUIRED`，确认文案必须同时包含“李四”和“200元”。

#### 5.4 确认执行

```bash
curl -sS -N --max-time 600 -X POST http://127.0.0.1:18200/a2a/ \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  --data-binary @- <<'JSON' | tee "$MANUAL_DIR/transfer-4-response.sse"
{
  "jsonrpc": "2.0",
  "id": "manual-transfer-4",
  "method": "SendStreamingMessage",
  "params": {
    "message": {
      "role": "ROLE_USER",
      "contextId": "manual-transfer-context",
      "taskId": "TASK_ID_FROM_FIRST_RESPONSE",
      "parts": [{"text": "确认"}]
    }
  }
}
JSON
```

预期最终状态为 `TASK_STATE_COMPLETED`，结果包含收款人李四和金额 200。确认有且仅有确认后才执行
真实业务函数：

```bash
grep 'BANK_DEMO_EXECUTION tool=execute_transfer' "$MANUAL_DIR/transfer.log" | tail -n 1
```

### 6. 理财购买确认

#### 6.1 发起购买

```bash
curl -sS -N --max-time 600 -X POST http://127.0.0.1:18200/a2a/ \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  --data-binary @- <<'JSON' | tee "$MANUAL_DIR/purchase-1-response.sse"
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
```

预期为 `TASK_STATE_INPUT_REQUIRED`，确认文案包含“稳盈90天”和“10000元”。复制响应中的外层
`taskId`，替换下一条报文中的 `TASK_ID_FROM_FIRST_RESPONSE`。

#### 6.2 确认购买

```bash
curl -sS -N --max-time 600 -X POST http://127.0.0.1:18200/a2a/ \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  --data-binary @- <<'JSON' | tee "$MANUAL_DIR/purchase-2-response.sse"
{
  "jsonrpc": "2.0",
  "id": "manual-purchase-2",
  "method": "SendStreamingMessage",
  "params": {
    "message": {
      "role": "ROLE_USER",
      "contextId": "manual-purchase-context",
      "taskId": "TASK_ID_FROM_FIRST_RESPONSE",
      "parts": [{"text": "确认"}]
    }
  }
}
JSON
```

预期最终状态为 `TASK_STATE_COMPLETED`。验证 WealthPurchaseAgent 确实执行：

```bash
grep 'BANK_DEMO_EXECUTION tool=purchase_wealth' "$MANUAL_DIR/wealth-purchase.log" | tail -n 1
```

### 7. 同一会话中的语义指代

本场景先推荐产品，再使用“刚才推荐的第一个产品”发起新任务。第二次请求复用 `contextId`，但它是
新任务，因此不能携带第一次已经完成的 `taskId`。

#### 7.1 获取推荐

```bash
curl -sS -N --max-time 600 -X POST http://127.0.0.1:18200/a2a/ \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  --data-binary @- <<'JSON' | tee "$MANUAL_DIR/reference-1-response.sse"
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
```

预期为 `TASK_STATE_COMPLETED`，推荐结果包含“稳盈90天”。

#### 7.2 使用上一轮信息购买

```bash
curl -sS -N --max-time 600 -X POST http://127.0.0.1:18200/a2a/ \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  --data-binary @- <<'JSON' | tee "$MANUAL_DIR/reference-2-response.sse"
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
```

预期为 `TASK_STATE_INPUT_REQUIRED`，确认文案应将指代解析为“稳盈90天”，并包含“5000元”。复制
本次响应中的外层 `taskId`，替换确认报文中的 `TASK_ID_FROM_SECOND_RESPONSE`。

#### 7.3 确认指代后的购买

```bash
curl -sS -N --max-time 600 -X POST http://127.0.0.1:18200/a2a/ \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  --data-binary @- <<'JSON' | tee "$MANUAL_DIR/reference-3-response.sse"
{
  "jsonrpc": "2.0",
  "id": "manual-reference-3",
  "method": "SendStreamingMessage",
  "params": {
    "message": {
      "role": "ROLE_USER",
      "contextId": "manual-reference-context",
      "taskId": "TASK_ID_FROM_SECOND_RESPONSE",
      "parts": [{"text": "确认"}]
    }
  }
}
JSON
```

预期最终状态为 `TASK_STATE_COMPLETED`，结果包含“稳盈90天”和“5000元”。

### 8. 中断期间发生意图变化

本场景先进入转账确认中断，然后在同一个 A2A Task 中输入新的理财购买意图。TransferAgent 不执行
原转账，入口 Agent 使用最新语义重新调用 `intent_match`，并进入理财购买确认。

#### 8.1 发起转账并等待确认

```bash
curl -sS -N --max-time 600 -X POST http://127.0.0.1:18200/a2a/ \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  --data-binary @- <<'JSON' | tee "$MANUAL_DIR/change-1-response.sse"
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
```

预期为 `TASK_STATE_INPUT_REQUIRED`，确认文案包含“王五”和“50元”。复制响应中的外层 `taskId`，
替换后续两条报文中的 `TASK_ID_FROM_FIRST_RESPONSE`。

#### 8.2 使用同一 Task 输入新意图

```bash
curl -sS -N --max-time 600 -X POST http://127.0.0.1:18200/a2a/ \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  --data-binary @- <<'JSON' | tee "$MANUAL_DIR/change-2-response.sse"
{
  "jsonrpc": "2.0",
  "id": "manual-change-2",
  "method": "SendStreamingMessage",
  "params": {
    "message": {
      "role": "ROLE_USER",
      "contextId": "manual-change-context",
      "taskId": "TASK_ID_FROM_FIRST_RESPONSE",
      "parts": [{"text": "改为购买1000元稳盈90天理财"}]
    }
  }
}
JSON
```

预期仍为 `TASK_STATE_INPUT_REQUIRED`，但确认文案已经变为购买“稳盈90天”理财 1000 元，而不是确认
原来的转账。

#### 8.3 确认新意图

```bash
curl -sS -N --max-time 600 -X POST http://127.0.0.1:18200/a2a/ \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  --data-binary @- <<'JSON' | tee "$MANUAL_DIR/change-3-response.sse"
{
  "jsonrpc": "2.0",
  "id": "manual-change-3",
  "method": "SendStreamingMessage",
  "params": {
    "message": {
      "role": "ROLE_USER",
      "contextId": "manual-change-context",
      "taskId": "TASK_ID_FROM_FIRST_RESPONSE",
      "parts": [{"text": "确认"}]
    }
  }
}
JSON
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

### 9. 多目标转账规划并逐笔执行

该场景必须先由入口 DeepAgent 调用 `todo_create` 生成两步计划，再逐次调用意图工具。每一笔转账仍由
TransferAgent 单独处理和确认。

#### 9.1 发起复杂请求

```bash
curl -sS -N --max-time 600 -X POST http://127.0.0.1:18200/a2a/ \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  --data-binary @- <<'JSON' | tee "$MANUAL_DIR/plan-1-response.sse"
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

复制响应中的外层 `taskId`，替换后续两条确认报文中的 `TASK_ID_FROM_FIRST_RESPONSE`。

#### 9.2 确认第一笔转账

```bash
curl -sS -N --max-time 600 -X POST http://127.0.0.1:18200/a2a/ \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  --data-binary @- <<'JSON' | tee "$MANUAL_DIR/plan-2-response.sse"
{
  "jsonrpc": "2.0",
  "id": "manual-plan-2",
  "method": "SendStreamingMessage",
  "params": {
    "message": {
      "role": "ROLE_USER",
      "contextId": "manual-plan-context",
      "taskId": "TASK_ID_FROM_FIRST_RESPONSE",
      "parts": [{"text": "确认"}]
    }
  }
}
JSON
```

预期仍为 `TASK_STATE_INPUT_REQUIRED`。响应应先显示“第 1/2 步已完成：给张三转账100元”，然后显示
“当前执行第 2/2 步”并要求确认给李四转账 100 元。

#### 9.3 确认第二笔转账

```bash
curl -sS -N --max-time 600 -X POST http://127.0.0.1:18200/a2a/ \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  --data-binary @- <<'JSON' | tee "$MANUAL_DIR/plan-3-response.sse"
{
  "jsonrpc": "2.0",
  "id": "manual-plan-3",
  "method": "SendStreamingMessage",
  "params": {
    "message": {
      "role": "ROLE_USER",
      "contextId": "manual-plan-context",
      "taskId": "TASK_ID_FROM_FIRST_RESPONSE",
      "parts": [{"text": "确认"}]
    }
  }
}
JSON
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

### 10. 停止服务

完成验证后停止“启动五个服务”步骤创建的进程：

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
