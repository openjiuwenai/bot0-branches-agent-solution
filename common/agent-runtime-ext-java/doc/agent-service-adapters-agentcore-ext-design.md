# agent-service-adapters-agentcore-ext 源码设计说明

## 1. 文档定位

本文档基于当前源码说明 `agent-service-adapters-agentcore-ext` 的实现边界、运行链路和配置关系。目标模块为：

```text
common/agent-runtime-ext-java/agent-service-adapters/agent-service-adapters-agentcore-ext
```

当前模块是 `agent-service-adapters-agentcore` 的轻量增强层。它不负责远端 Agent Card 发现，不直接调用远端 A2A，也不自动创建业务 `AgentHandler`；它只在 `JiuwenCoreAgentHandler` 执行前读取 runtime 已发现的远端 agent registry，把远端 agent 注入成 AgentCore 可见工具，并通过 rail 把工具调用转换成 `a2a_delegate` interrupt。

## 2. 模块结构

当前源码结构：

```text
agent-service-adapters-agentcore-ext
|-- pom.xml
|-- src/main/java/com/openjiuwen/service/adapters/agentcore/ext
|   |-- agentfw/
|   |   `-- JiuwenCoreAgentExtHandler.java
|   |-- autoconfigure/
|   |   `-- AgentCoreExtAutoConfiguration.java
|   `-- external/
|       |-- RemoteA2aInterruptRail.java
|       `-- RemoteA2aToolInstaller.java
`-- src/main/resources/META-INF/spring/
    `-- org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

文件职责：

| 文件 | 当前职责 |
| --- | --- |
| `pom.xml` | 独立 jar module，依赖 runtime spec/app、上游 agentcore adapter、AgentCore、Spring Boot autoconfigure、Jackson、SLF4J |
| `JiuwenCoreAgentExtHandler` | 继承 `JiuwenCoreAgentHandler`，在 `query()` / `streamQuery()` 调用父类前执行远端工具安装 |
| `AgentCoreExtAutoConfiguration` | 注册 `RemoteA2aToolInstaller` bean，不注册 `AgentHandler` |
| `RemoteA2aToolInstaller` | 从 `A2ARemoteAgentCardRegistry` 读取远端 agent entry，生成 tool spec，向 `BaseAgent` 或 `DeepAgent` 内部 agent 注册 `RemoteA2aInterruptRail` |
| `RemoteA2aInterruptRail` | 继承 `BaseInterruptRail`，暴露远端工具 `ToolCard`，首次工具调用产生 A2A 委托 interrupt，resume 时用 `reject(...)` 注回远端结果 |
| `AutoConfiguration.imports` | Spring Boot 自动装配入口，内容为 `AgentCoreExtAutoConfiguration` |

## 3. 接入边界

当前运行链路：

```text
ServeRequest
  -> JiuwenCoreAgentExtHandler.query()/streamQuery()
  -> RemoteA2aToolInstaller.install(getAgent())
  -> JiuwenCoreAgentHandler.query()/streamQuery()
  -> Runner.runAgentStreaming(...)
  -> AgentCore tool call
  -> RemoteA2aInterruptRail
  -> QueryChunk.TYPE_INTERRUPT 或 QueryResponse.result._interrupt
  -> A2AEnabledServeOrchestrator
  -> A2ARemoteAgentClient
```

`agentcore-ext` 只处理本地 AgentCore 工具注入和 interrupt 生成；远端发现、远端通信、shadow task、resume 循环由 runtime app 编排层处理。远端 URL 不写入 `agentcore-ext` 的 tool spec，也不写入 interrupt context；下游按 `agentName` 回查 `A2ARemoteAgentCardRegistry`。

## 4. JiuwenCoreAgentExtHandler

`JiuwenCoreAgentExtHandler` 继承上游 `JiuwenCoreAgentHandler`，构造函数接收：

```java
Object agent
MiddlewareAdapterRegistrar middlewareAdapterRegistrar
RemoteA2aToolInstaller remoteToolInstaller
```

当前覆盖的方法只有：

```java
@Override
public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
    installBeforeRun();
    super.streamQuery(request, observer);
}

@Override
public QueryResponse query(ServeRequest request) {
    installBeforeRun();
    return super.query(request);
}
```

`installBeforeRun()` 只调用：

```java
remoteToolInstaller.install(getAgent());
```

因此主执行行为仍由父类负责，包括 `ServeRequest` 到 AgentCore input 的构造、Runner 调用、流式 chunk 归一化、非流式 `QueryResponse` 聚合、异常处理和会话释放。`agentcore-ext` 没有复制父类执行逻辑，也没有改变业务请求到 Runner 的输入结构。

## 5. Auto-configuration

`AgentCoreExtAutoConfiguration` 当前只创建一个 bean：

```java
@Bean
@ConditionalOnMissingBean
RemoteA2aToolInstaller remoteA2aToolInstaller(ObjectProvider<A2ARemoteAgentCardRegistry> registry) {
    return RemoteA2aToolInstaller.create(registry.getIfAvailable());
}
```

关键行为：

```text
1. 不绑定 agentcore-ext 专属 properties。
2. 不读取 openjiuwen.service.handler 条件。
3. 不创建 AgentHandler。
4. 如果容器内没有 A2ARemoteAgentCardRegistry，仍创建 RemoteA2aToolInstaller，但 installer 内 registry 为 null。
5. 如果业务自己定义 RemoteA2aToolInstaller，auto-config 不覆盖。
```

业务必须显式声明 `AgentHandler`，例如：

```java
@Bean
AgentHandler agentHandler(DeepAgent agent,
                          MiddlewareAdapterRegistrar registrar,
                          RemoteA2aToolInstaller installer) {
    return new JiuwenCoreAgentExtHandler(agent, registrar, installer);
}
```

## 6. 远端 Agent 来源

当前模块不拉取远端 card，也没有自己的远端 properties 或 cache。远端 agent 来源是 runtime 内置 A2A 能力：

```text
openjiuwen.service.a2a.remote-agents
  -> A2AAgentCardDiscovery
  -> A2ARemoteAgentCardRegistry.register(name, card, timeoutSeconds)
  -> RemoteA2aToolInstaller.install(agent)
  -> registry.getAll()
```

Agent A 侧配置示例：

```yaml
openjiuwen:
  service:
    handler: agentcore-ext
    a2a:
      remote-agents:
        - name: versatile-agent
          url: http://127.0.0.1:18091
```

`A2AAgentCardDiscovery` 在应用 ready 后读取 `remote-agents`，用 `remote.url` 拼出：

```text
<remote.url without trailing slash>/.well-known/agent-card.json
```

发现成功后注册：

```java
registry.register(remote.getName(), card, remote.getTimeoutSeconds());
```

因此 registry entry 的 `name()` 来自 Agent A 配置的 `remote-agents[].name`，card 来自 Agent B 暴露的 A2A card。`agentcore-ext` 消费的是 registry entry，不直接消费 `remote-agents[].url`。

## 7. 远端 Card 和工具描述

Agent B 的 A2A card 由 runtime `AgentCardController` 生成。通信 URL 生成逻辑是：

```text
baseUrl = openjiuwen.service.a2a.public-url 非空时使用该值
baseUrl = public-url 为空时使用当前 HTTP request 的 scheme/serverName/serverPort
jsonRpcUrl = baseUrl 去掉末尾斜杠 + openjiuwen.service.a2a.json-rpc-path
```

`json-rpc-path` 默认是 `/a2a`。因此本地直连 demo 中，Agent A 访问 Agent B 的 well-known card 后，card 内会带上类似：

```text
http://127.0.0.1:18091/a2a
```

远端工具描述来自发现到的 card。`RemoteA2aToolInstaller.description(...)` 当前规则：

```text
1. card.skills 非空，并且存在非空 skill.description：
   取所有非空 skill.description，trim 后用换行符合并。
2. 否则，如果 card.description 非空：
   使用 card.description.trim()。
3. 否则：
   使用默认描述 Delegate this request to remote A2A agent '<name>'.
```

Agent B 侧如果希望大模型按能力选择工具，应至少在 card 中提供 `skills[].description`。示例最小配置：

```yaml
openjiuwen:
  service:
    a2a:
      skills:
        - id: "banking_workflow"
          name: "Banking Workflow"
          description: "Process banking workflow requests such as account balance lookup, transfer preparation, and follow-up business steps through the configured Versatile HTTP/SSE workflow."
```

`a2a.agent-description` 不是通信必需项；当 `skills[].description` 存在时，也不是工具描述的首选来源。它主要是 card 自描述和 skill 描述缺失时的 fallback。

## 8. RemoteA2aToolInstaller

### 8.1 输入和状态

`RemoteA2aToolInstaller` 通过静态工厂创建：

```java
RemoteA2aToolInstaller.create(A2ARemoteAgentCardRegistry registry)
```

内部状态：

```java
private final A2ARemoteAgentCardRegistry registry;
private final Map<Object, Set<String>> installedRemoteAgentNames =
        Collections.synchronizedMap(new WeakHashMap<>());
```

`installedRemoteAgentNames` 以目标 `BaseAgent` 实例为 key，记录已安装的远端 agent name，避免重复安装。`WeakHashMap` 允许 agent 实例不再被外部引用后释放。

### 8.2 支持的 agent 类型

`install(Object agent)` 当前支持：

| agent 类型 | 行为 |
| --- | --- |
| `BaseAgent` / `ReActAgent` | 直接向该 agent 注册 `RemoteA2aInterruptRail` |
| `DeepAgent` | 取 `deepAgent.getAgent()`，向内部 agent 注册 rail |
| `String agentId` | 记录 `agent-id mode cannot install remote A2A tools in v1` 并跳过 |
| 其他对象或 null | 记录 warning 并跳过 |
| registry 为 null | 直接返回 |

### 8.3 工具生成规则

安装流程：

```text
1. registry == null 时返回。
2. String agentId 模式返回。
3. 解析目标 BaseAgent：BaseAgent 直接使用，DeepAgent 使用内部 getAgent()。
4. 从 installedRemoteAgentNames 获取该 agent 已安装 name 集合。
5. 在 synchronized(installedNames) 内读取 registry.getAll()。
6. 过滤掉已安装的 entry.name()。
7. 将每个 entry 映射成 RemoteA2aToolSpec。
8. 如果没有新增 spec，返回。
9. 用新增 spec 创建一个 RemoteA2aInterruptRail。
10. target.registerRail(rail)。
11. 将 spec.remoteAgentId() 加入已安装集合。
```

当前工具名规则：

```text
entry.name() 为 null 或 blank 时跳过。
非空 entry.name() 原样使用，不 trim，不正则校验，不大小写归一，不替换空格或其他字符。
```

`RemoteA2aToolSpec` 是内部 record：

```java
public record RemoteA2aToolSpec(
        String remoteAgentId,
        String toolName,
        String description,
        Map<String, Object> inputSchema) {
}
```

当前映射：

```text
remoteAgentId = entry.name()
toolName = entry.name()
description = card.skills[].description 合并值；否则 card.description；否则默认描述
inputSchema = 固定 remoteInput envelope
```

固定 input schema：

```json
{
  "type": "object",
  "properties": {
    "remoteInput": {
      "type": "string",
      "description": "Text to send as the remote A2A user message."
    }
  },
  "required": ["remoteInput"],
  "additionalProperties": true
}
```

当前粒度是“一个 registry entry -> 一个本地远端委托工具”。多个 skill 不会拆成多个工具，只会把多个非空 `skill.description` 合并成同一个工具的 description。

### 8.4 Rail 注册

每次有新增 spec 时创建一个新的 `RemoteA2aInterruptRail`：

```java
RemoteA2aInterruptRail rail = new RemoteA2aInterruptRail(newSpecs);
target.registerRail(rail);
```

`BaseAgent.registerRail(rail)` 进入 AgentCore rail 注册流程。对本模块来说，关键结果是：rail 暴露的 `ToolCard` 会进入 agent 的能力管理，模型侧能看到注入工具。`agentcore-ext` 不注册真实 Tool 实例；远端工具可见性来自 rail 携带的 `ToolCard`。

## 9. RemoteA2aInterruptRail

`RemoteA2aInterruptRail` 继承 `BaseInterruptRail`。

构造函数接收 `List<RemoteA2aToolSpec>`，并执行：

```text
1. 把所有 spec.toolName() 传给 BaseInterruptRail，作为拦截工具名集合。
2. 构建 specsByToolName，不可变 map。
3. 为每个 spec 创建 ToolCard，加入 rail.getTools()。
```

ToolCard 映射：

```java
ToolCard.builder()
        .id(spec.toolName())
        .name(spec.toolName())
        .description(spec.description())
        .inputParams(spec.inputSchema())
        .build();
```

### 9.1 首次工具调用

`resolveInterrupt(ctx, toolCall, resumeInput)` 中，如果 `resumeInput == null`：

```text
1. 优先从 ToolCallInputs.getToolName() 取工具名；没有时取 ToolCall.getName()。
2. 按工具名查找 RemoteA2aToolSpec。
3. 从 toolCall.arguments 提取 message。
4. 构造 InterruptRequest。
5. 返回 interrupt(request)。
```

message 提取规则：

```text
toolCall.arguments 是 JSON object 且 remoteInput 是非空字符串 -> 使用 remoteInput。
JSON 解析失败、remoteInput 不存在或为空 -> 使用原始 arguments 字符串。
toolCall 或 arguments 为 null -> 空字符串。
```

InterruptRequest context：

```json
{
  "agentName": "<remoteAgentId>",
  "_interrupt_kind": "a2a_delegate",
  "_stream_mode": "sse"
}
```

`agentName` 是后续 runtime 远端路由键，来自 `entry.name()`；`_stream_mode` 当前固定为 `sse`。

### 9.2 resume

如果 `resumeInput != null`，`RemoteA2aInterruptRail` 返回：

```java
reject(resumeInput)
```

`BaseInterruptRail` 的 reject 语义会让 AgentCore 跳过真实工具查找，把 `resumeInput` 作为当前工具调用结果注回模型上下文。这保证远端 A2A 工具只需要 `ToolCard` 和 rail，不需要本地真实 Tool 实例。

## 10. 与 A2A 编排层的契约

本模块输出的是标准 AgentCore interrupt。经 `JiuwenCoreAgentHandler` 归一化后，下游会看到流式 `QueryChunk.TYPE_INTERRUPT`，非流式会在 `QueryResponse.result._interrupt` 中携带 interrupt 数据。典型结构：

```json
{
  "type": "__interaction__",
  "message": "{\"intent\":\"查询账户余额\",\"query\":\"先查询尾号为4241的银行卡余额，再转账5元给李四\"}",
  "toolName": "versatile-agent",
  "toolCallId": "call-xxx",
  "context": {
    "agentName": "versatile-agent",
    "_interrupt_kind": "a2a_delegate",
    "_stream_mode": "sse"
  }
}
```

`A2AEnabledServeOrchestrator` 当前消费规则：

```text
1. 流式模式捕获 QueryChunk.TYPE_INTERRUPT；非流式模式读取 QueryResponse.result._interrupt。
2. 从 context._interrupt_kind 读取 kind；等于 a2a_delegate 时进入远端委托。
3. 从 context.agentName 读取远端 agent name。
4. 从 message 读取远端 user message。
5. 流式路径中，_stream_mode == sse 时调用 callStreaming，否则调用 callSync。
6. 非流式 query 路径当前直接调用 callSync。
7. 远端返回最终结果后构造 resume ServeRequest，并把结果作为本地 interrupted tool 的输入。
8. 远端 INPUT_REQUIRED 时保存 shadow task，并向客户端返回 interrupt。
```

远端通信阶段由 `A2ARemoteAgentClient` 处理：

```text
registry.get(agentName)
  -> RemoteAgentEntry(card, timeoutSeconds)
  -> Client.builder(card)
       .clientConfig(streaming flag)
       .withTransport(JSONRPCTransport.class, new JSONRPCTransportConfig())
       .build()
  -> sendMessage(MessageSendParams)
```

因此真正通信地址来自远端 card 的 `supportedInterfaces[0].url()` / SDK transport 解析结果，而不是 `agentcore-ext` 生成的 interrupt。

shadow task 保存的数据包括：

```text
_agent_name
_remote_url
_remote_task_id
_stream_mode
```

其中 `_remote_url` 来自 `registry.resolveUrl(agentName)`，用于记录和恢复上下文；resume 调用远端时仍以 `_agent_name` 回查 registry。

## 11. 配置和使用方式

`agentcore-ext` 自身没有专属配置前缀。实际需要三类配置或代码：

### 11.1 Agent A：handler 选择和 remote-agents

```yaml
openjiuwen:
  service:
    handler: agentcore-ext
    a2a:
      remote-agents:
        - name: versatile-agent
          url: http://127.0.0.1:18091
```

`handler=agentcore-ext` 是宿主应用约定；当前 auto-config 不根据这个值创建 handler。

`remote-agents[].name` 是发现后的 registry key，也是 `toolName`、`remoteAgentId` 和 interrupt context 中的 `agentName`。`remote-agents[].url` 只用于发现 card，不直接进入工具注入结果。

### 11.2 Agent B：card skill 描述

```yaml
openjiuwen:
  service:
    a2a:
      skills:
        - id: "banking_workflow"
          name: "Banking Workflow"
          description: "Process banking workflow requests such as account balance lookup, transfer preparation, and follow-up business steps through the configured Versatile HTTP/SSE workflow."
```

这段配置会进入 Agent B card 的 `skills`，Agent A 发现 card 后，`agentcore-ext` 用 `skills[].description` 生成注入工具的 description。大模型按工具描述选择是否调用远端工具，而不是靠 system prompt 硬编码工具名。

如果 Agent B 位于代理、容器、NAT 或网关后面，并且自动生成的 card 通信 URL 不是 Agent A 可访问地址，需要配置 `openjiuwen.service.a2a.public-url`。本地直连 demo 不需要。

### 11.3 显式声明 AgentHandler

业务需要显式声明：

```java
@Bean
AgentHandler agentHandler(BaseAgent agent,
                          MiddlewareAdapterRegistrar registrar,
                          RemoteA2aToolInstaller installer) {
    return new JiuwenCoreAgentExtHandler(agent, registrar, installer);
}
```

DeepAgent 场景：

```java
@Bean
AgentHandler agentHandler(DeepAgent agent,
                          MiddlewareAdapterRegistrar registrar,
                          RemoteA2aToolInstaller installer) {
    return new JiuwenCoreAgentExtHandler(agent, registrar, installer);
}
```

## 12. 当前实现结论

当前 `agentcore-ext` 已实现：

```text
1. 独立 Maven module。
2. Spring Boot auto-configuration 注册 RemoteA2aToolInstaller。
3. 显式 JiuwenCoreAgentExtHandler，入口前安装远端工具，主逻辑复用 JiuwenCoreAgentHandler。
4. 从 A2ARemoteAgentCardRegistry.getAll() 读取已注册远端 agent。
5. 将 registry entry name 原样映射为 AgentCore ToolCard 的 name/id 和远端路由 key。
6. 从远端 card.skills[].description / card.description 生成工具描述。
7. 支持 BaseAgent/ReActAgent 和 DeepAgent 内部 agent。
8. 支持同一 agent 实例幂等安装、增量安装和并发安装保护。
9. 首次远端工具调用产生 a2a_delegate interrupt。
10. resume 时通过 reject(resumeInput) 跳过真实工具查找，把远端结果作为 tool result 注回 AgentCore。
```

当前实现边界：

```text
1. 不自动创建 AgentHandler。
2. 不提供 agentcore-ext 专属 properties。
3. 不拉取远端 Agent Card。
4. 不缓存 card/spec。
5. 不把一个 card 的多个 skill 拆成多个工具。
6. 不做工具名规范化；除 null/blank 外，entry.name() 原样使用。
7. 不支持 String agentId 模式下的远端工具安装。
8. 不注册真实 Tool 实例，远端工具只由 rail 携带 ToolCard 暴露。
```
