# agent-core-ext-java

`agent-core-ext-java` 是基于 `agent-core-java 0.1.13` 的独立 Java 17 意图扩展工程。模块在初始化阶段接收目标对象并编译不可变候选目录，运行时只接收用户请求，通过调用方注入的 `Reranker` 完成全目录评分、目标聚合和失败关闭的接受门判断。

首个目标适配器支持 A2A v1.0.1，直接使用官方 A2A Java SDK `1.0.0.Final` 的 `AgentCard`，不定义协议镜像 DTO。

## 能力边界

- `IntentRecognizer<T>`：协议无关的共享识别内核。
- `A2AAgentCardIntentAdapter`：官方 AgentCard 深快照、资格过滤和候选文档生成。
- `IntentRecognitionTool<T>`：可注册到 ReAct Agent 或 DeepAgent 的 Tool。
- `IntentRecognitionComponent<T>`：可挂接到 Workflow 的 Component。
- 不拉取或刷新 AgentCard，不访问 Agent URL，不调用被选中的 Agent。
- 不创建 HTTP Client；模型请求由注入的 `agent-core-java` `Reranker` 完成。

## 构建

```bash
mvn clean verify
```

`verify` 会执行 `.devtools` 中与仓库 CodeCheck 对齐的 Eclipse 格式校验和 Checkstyle 近似白盒规则。也可以在开发过程中单独执行：

```bash
mvn formatter:validate
mvn checkstyle:check
```

格式校验失败时使用 `mvn formatter:format` 统一格式。远端 CodeCheck 仍是类型和控制流规则的最终判定来源。

项目使用 `maven.compiler.release=17`。生产阈值必须来自实际目标目录的 calibration set，不应直接复制示例值。

## 初始化

```java
RerankerConfig rerankerConfig = new RerankerConfig();
rerankerConfig.setApiBase("http://127.0.0.1:8080");
rerankerConfig.setModelName("Alibaba-NLP/gte-multilingual-reranker-base");
rerankerConfig.setTimeout(3.0);
Reranker reranker = new StandardReranker(rerankerConfig);

IntentRecognizerConfig intentConfig = IntentRecognizerConfig.builder()
        .scoreThreshold(0.62)       // 仅为 API 示例，必须重新校准
        .marginThreshold(0.12)      // 仅为 API 示例，必须重新校准
        .candidateFormatVersion("a2a-v1")
        .modelVersion("gte-multilingual-reranker-base")
        .build();

A2AEligibilityPolicy eligibilityPolicy = new A2AEligibilityPolicy(
        Set.of("JSONRPC"),
        Set.of("1.0"),
        Set.of(),
        Set.of("text/plain"),
        (card, requirements) -> credentialsCanSatisfy(requirements),
        card -> trustedCardRegistry.contains(card));

A2AAgentCardIntentAdapter adapter = new A2AAgentCardIntentAdapter(eligibilityPolicy);
IntentRecognizer<AgentCard> recognizer = IntentRecognizers.<AgentCard>builder()
        .targets(agentCards)
        .targetAdapter(adapter)
        .reranker(reranker)
        .config(intentConfig)
        .build();

A2AAgentCardResultEncoder encoder = new A2AAgentCardResultEncoder();
```

`agentCards` 由上游通过标准 A2A discovery、认证和签名验证后传入。构建 recognizer 后，外部 Card 变化不会影响当前目录；目录更新必须新建 recognizer 和框架适配器并由应用层原子替换。

## Agent Tool

Tool 业务输入固定为：

```json
{"utterance":"查询订单物流"}
```

`kwargs` 是可选框架上下文，不属于业务 Schema，模块不读取或写入 Session。

```java
String toolId = "intent_recognition_order-router_" + UUID.randomUUID();
IntentRecognitionTool<AgentCard> tool = new IntentRecognitionTool<>(
        recognizer,
        encoder,
        new IntentRecognitionToolConfig(toolId, "intent_recognition"));

Runner.resourceMgr().addTool(tool, reactAgent.getCard().getId());
reactAgent.getAbilityManager().add(tool.getCard());

DeepAgentConfig deepAgentConfig = DeepAgentConfig.builder()
        .tools(List.of(tool))
        .build();
```

`toolId` 必须在 `Runner.resourceMgr()` 生命周期内全局唯一。多个 Agent 可以使用相同的 LLM 可见名称 `intent_recognition`，但必须使用不同资源 ID。

## Workflow Component

```java
workflow.addWorkflowComp(
        "intent",
        new IntentRecognitionComponent<>(recognizer, encoder),
        Map.of("utterance", "${start.query}"));
```

节点输出为可寻址 Map：

```text
${intent.matched}
${intent.target}
${intent.reason}
```

Tool 和 Workflow 使用相同 recognizer 与 encoder，因此匹配结论和 AgentCard 字段一致。

## 输出与失败关闭

命中时返回完整官方 AgentCard JSON 结构：

```json
{"matched":true,"target":{"name":"Order Agent"},"reason":"MATCHED"}
```

输入无效、目录为空、分数不足、目标冲突、scorer 异常或编码失败时，`target` 固定为 `null`：

```json
{"matched":false,"target":null,"reason":"INSUFFICIENT_MARGIN"}
```

完整 reason 集合见 `IntentRecognitionReason`。

## 安全责任

- Card 来源认证、签名验证和凭证管理由上游完成。
- `contentTrustEvaluator` 必须确认 Card 文本可以进入 Agent LLM 上下文；签名有效不等于内容安全。
- URL、provider、安全配置和签名不会进入相关性候选文档。
- required extension、协议 binding/version、输入 media type 和有效安全要求均在目录编译阶段过滤。
