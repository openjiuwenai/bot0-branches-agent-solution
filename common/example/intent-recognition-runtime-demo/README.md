# Intent Recognition Runtime Demo

这个 demo 通过两个独立的 Spring Boot runtime 验证 FEAT-020 意图识别模块：

- `react-agent-runtime`：真实 ReAct Agent 调用 `IntentRecognitionTool`。
- `workflow-runtime`：真实 Workflow Agent 执行包含 `IntentRecognitionComponent` 的 Workflow。

两个 runtime 都通过 `agent-service-app` 暴露 A2A `SendMessage` 接口，并使用 `JiuwenCoreAgentExtHandler` 接入 agent-core-java。两条路径共享相同的人工 AgentCard 目录：Order Agent、Weather Agent 和 Knowledge Agent。

## 1. 配置模型

分别打开以下文件并填写真实模型配置：

```text
react-agent-runtime/src/main/resources/application.yml
workflow-runtime/src/main/resources/application.yml
```

需要填写：

```yaml
openjiuwen:
  demo:
    intent:
      llm:
        provider: OpenAI
        api-key: your-chat-api-key
        api-base: https://your-chat-endpoint/v1
        model-name: your-chat-model
      reranker:
        api-key: your-reranker-api-key
        api-base: https://your-reranker-endpoint/v1
        model-name: your-reranker-model
```

reranker 使用 agent-core-java `StandardReranker` 的原生协议：

```text
POST {api-base}/rerank
Authorization: Bearer {api-key}
```

请求包含 `model`、`query`、`documents`、`top_n` 和 `return_documents`。响应必须包含 `results[]` 或 `output.results[]`，每项提供 `index` 和 `relevance_score`。

也可以使用两边相同的环境变量配置，避免在文件中保存密钥：

```bash
export CHAT_MODEL_PROVIDER=OpenAI
export CHAT_MODEL_API_KEY=your-chat-api-key
export CHAT_MODEL_API_BASE=https://your-chat-endpoint/v1
export CHAT_MODEL_NAME=your-chat-model
export RERANKER_API_KEY=your-reranker-api-key
export RERANKER_API_BASE=https://your-reranker-endpoint/v1
export RERANKER_MODEL_NAME=your-reranker-model
```

## 2. 构建

在仓库根目录执行。先安装扩展模块，再构建 demo：

```bash
mvn -f common/agent-core-ext-java/pom.xml clean install

mvn -f common/agent-runtime-ext-java/pom.xml \
  -pl agent-service-adapters/agent-service-adapters-agentcore-ext \
  -am clean install

mvn -f common/example/intent-recognition-runtime-demo/pom.xml clean package
```

项目使用 Java 17。

## 3. 启动 ReAct Agent runtime

```bash
java -jar common/example/intent-recognition-runtime-demo/react-agent-runtime/target/\
intent-recognition-react-agent-runtime-0.1.0-SNAPSHOT.jar
```

默认 A2A 地址：

```text
http://127.0.0.1:18110/a2a
```

发送订单查询：

```bash
curl --fail-with-body \
  -H 'Content-Type: application/json' \
  --data @common/example/intent-recognition-runtime-demo/react-agent-runtime/\
src/main/resources/a2a-requests/order.json \
  http://127.0.0.1:18110/a2a
```

这条请求会经过真实 chat LLM。LLM 必须调用 `intent_recognition` Tool，Tool 再通过真实 reranker 选择目标。

## 4. 启动 Workflow runtime

在另一个终端启动：

```bash
java -jar common/example/intent-recognition-runtime-demo/workflow-runtime/target/\
intent-recognition-workflow-runtime-0.1.0-SNAPSHOT.jar
```

默认 A2A 地址：

```text
http://127.0.0.1:18111/a2a
```

发送相同订单查询：

```bash
curl --fail-with-body \
  -H 'Content-Type: application/json' \
  --data @common/example/intent-recognition-runtime-demo/workflow-runtime/\
src/main/resources/a2a-requests/order.json \
  http://127.0.0.1:18111/a2a
```

这条请求由真实 chat LLM 选择 `IntentRecognitionWorkflow`，然后执行以下图：

```text
Start -> IntentRecognitionComponent -> End
```

## 5. 验证结果

两条请求都应返回成功的 JSON-RPC 响应，业务结果包含：

```json
{
  "matched": true,
  "target": {
    "name": "Order Agent"
  },
  "reason": "MATCHED"
}
```

`target` 实际返回完整的标准 A2A AgentCard，上面仅省略了其余字段。ReAct Agent 最终文本由真实 LLM 生成；Workflow 的 End 节点使用 agent-core-java 标准 `output` 包装。

如果返回 `BELOW_SCORE_THRESHOLD` 或 `INSUFFICIENT_MARGIN`，应基于实际模型和 AgentCard calibration set 调整 YAML 中的 `score-threshold` 与 `margin-threshold`，不要直接把 demo 阈值用于生产。
