# agent-service-adapters-agentcore-ext 设计文档

## 1. 目标

`agent-service-adapters-agentcore-ext` 是 `agent-service-adapters-agentcore` 的增强模块，不修改 `vendor/agent-runtime-java` 上游代码，在 `JiuwenCoreAgentHandler` 现有执行链路上补充：

- 读取配置的远端 A2A Agent Card URL。
- 从远端 card 生成可注入给 JiuwenCoreAgent 的工具。
- LLM 调用该工具时由 AgentCore rail 触发 interrupt。
- interrupt 输出对齐当前 `A2AEnabledServeOrchestrator`：`message` 作为远端输入，`context.agentName` 定位远端 agent，`context._interrupt_kind=a2a_delegate` 标识远端委托。

主链路保持不变：

```text
HTTP / A2A ingress
  -> ServeOrchestrator
  -> AgentHandler
  -> JiuwenCoreAgentExtHandler
  -> AgentCore Runner
  -> BaseInterruptRail
  -> QueryChunk.TYPE_INTERRUPT
```

本模块只负责让 AgentCore 产生远端调用 interrupt，不在 handler 内直接调用远端 A2A。

## 2. 目录结构

参考上游 `agent-service-adapters-agentcore` 的 `agentfw`、`autoconfigure`、`external` 分层。远端 card、tool、rail 都属于对 AgentCore 的外部能力注入，统一放到 `external` 下，不再拆 `remote` / `rail` / `tool` 三个目录。

```text
common/agent-runtime-ext-java/agent-service-adapters/agent-service-adapters-agentcore-ext
|-- agentcore-ext-design.md
|-- pom.xml
|-- src/main/java/com/openjiuwen/service/adapters/agentcore/ext
|   |-- agentfw
|   |   `-- JiuwenCoreAgentExtHandler.java
|   |-- autoconfigure
|   |   `-- AgentCoreExtAutoConfiguration.java
|   |-- external
|   |   |-- RemoteA2aProperties.java
|   |   |-- RemoteA2aAgentCardCache.java
|   |   |-- RemoteA2aToolInstaller.java
|   |   `-- RemoteA2aInterruptRail.java
|   `-- package-info.java
|-- src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
`-- src/test/java/com/openjiuwen/service/adapters/agentcore/ext
    |-- autoconfigure
    |   `-- AgentCoreExtAutoConfigurationTest.java
    `-- external
        |-- RemoteA2aAgentCardCacheTest.java
        |-- RemoteA2aToolInstallerTest.java
        `-- RemoteA2aInterruptRailTest.java
```

包职责：

- `agentfw`：handler 增强，负责在父类执行入口前安装远端工具。
- `autoconfigure`：Spring Boot 自动配置，负责创建 ext handler 和 external 相关 bean。文件收敛为一个 `AgentCoreExtAutoConfiguration`，内部用方法和条件拆分 handler 与 remote A2A beans。
- `external`：远端 A2A card 发现、tool spec 映射、OpenJiuwen tool card/rail 安装、interrupt rail。`RemoteA2aToolSpec` 不单独成文件，作为 `RemoteA2aAgentCardCache` 内部 record。

## 3. 核心实现

### 3.1 JiuwenCoreAgentExtHandler

继承 `JiuwenCoreAgentHandler`，覆盖 `query()` 和 `streamQuery()`，但不复制父类主执行逻辑。

继承的目的：

- 复用父类 `start()` / `stop()` / `clearSession()`。
- 复用父类 `query()` / `streamQuery()` 里的 Runner 调用、流式循环、chunk normalize、错误处理。
- 保持 `agentcore-ext` 是 `agentcore` 增强版，而不是新的 runtime。

覆盖执行入口只做一件事：在调用父类前安装远端 A2A 工具。

不在 v1 中复制父类 `query()` / `streamQuery()` 的原因：

- 父类后续如果调整输入构造、输出聚合、interrupt normalize、异常处理，ext 不需要同步复制。
- ext 当前只需要在主执行入口前增加一个函数。
- v1 远端工具注入只支持具体 Agent 实例；`agent-id` 字符串模式仍可走上游 `agentcore` 普通执行链路，但不承诺远端工具注入。

执行逻辑：

```java
public final class JiuwenCoreAgentExtHandler extends JiuwenCoreAgentHandler {
    private final RemoteA2aToolInstaller remoteToolInstaller;

    public JiuwenCoreAgentExtHandler(Object agent,
                                     MiddlewareAdapterRegistrar registrar,
                                     RemoteA2aToolInstaller remoteToolInstaller) {
        super(agent, registrar);
        this.remoteToolInstaller = remoteToolInstaller;
    }

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

    private void installBeforeRun() {
        remoteToolInstaller.install(getAgent());
    }
}
```

实现要求：

- `query()` / `streamQuery()` 的主执行行为完全交给父类。
- 只在执行前增加 `installBeforeRun()`。
- 安装失败不能影响无远端工具的普通查询，除非是明确的配置错误。
- `getAgent()` 是可安装工具的具体 agent 实例时，v1 支持 `com.openjiuwen.core.singleagent.BaseAgent` 和 `com.openjiuwen.harness.deep_agent.DeepAgent`。
- `getAgent()` 是 `String agentId` 时，installer 记录日志并跳过远端工具安装，父类继续按上游 `agent-id` 模式执行。

`agent-id` 模式的处理：

- `openjiuwen.service.agent-id` + `handler=agentcore`：保持上游行为，不启用远端工具注入。
- `openjiuwen.service.agent-id` + `handler=agentcore-ext`：handler 可以继续执行普通查询；远端工具安装在 `installBeforeRun()` 中识别到 `String agentId` 后打日志跳过。

跳过安装的原因：父类 `super.query()` 会把 `String agentId` 交给 Runner，Runner 再从 `ResourceMgr` 调 `Supplier.get()` 获取实际 agent。installer 在 `super.query()` 前无法保证拿到和 Runner 本次执行完全相同的实例。

实现前必须补一个最小 spike：在 `super.query()` / `super.streamQuery()` 前动态添加 tool 和 rail，确认本次 Runner 执行能看到新增工具并能触发 `BaseInterruptRail`。源码上 ReActAgent 执行时会读取 `AbilityManager.listToolInfo()` 并通过 `AbilityManager.execute(...)` 执行工具，设计上可行；但 v1 需要用单元测试或最小集成测试锁住这个假设。

同一个 spike 也要覆盖：

- `BaseInterruptRail.reject(...)` 是否仍会写 `_skip_tool`、`ToolCallInputs.toolResult/toolMsg`，以及 `AbilityManager` 是否仍按 `_skip_tool` 跳过真实 Tool 查找。
- DeepAgent 是否仍公开 `getAgent()`，返回的内部 ReActAgent 是否在 query 间稳定，且安装到该内部 ReActAgent 的 rail 能被 DeepAgent 本次执行看到。

### 3.2 RemoteA2aProperties

建议配置：

```yaml
openjiuwen:
  service:
    handler: agentcore-ext
    agentcore-ext:
      remote-a2a:
        agents:
          - url: http://localhost:18091
            name: agent-b
```

字段：

- `agents[].url`：远端 runtime base URL 或 card URL。
- `agents[].name`：可选本地注入工具名。配置后按用户显式指定的名字使用，不做小写/kebab-case 规范化；只做底线合法性校验和重名处理。

### 3.3 RemoteA2aAgentCardCache

职责：

- 根据配置 URL 拉取远端 `AgentCard`。
- 按 URL 维护发现状态，生成当前可用的 `RemoteA2aToolSpec` 快照。
- 首次安装前同步刷新所有 `PENDING` URL。
- 单个 URL 刷新成功后进入 `READY`，固定该 URL 的 card/spec，不再刷新该 URL。
- 刷新失败的 URL 进入 `FAILED`，只由后台定时任务继续重试，避免每次 query 被慢 URL 阻塞。

线程模型：

- cache 内部维护 `volatile Map<String, Entry>`。
- `Entry` 是不可变 record，包含 url、name、state、spec。
- `refreshPendingOnce()` 只刷新 `PENDING` URL，构建完整新 map 后一次性替换。
- 后台 retry 任务只刷新 `FAILED` URL。
- 读取 `availableToolSpecs()` 不加锁。
- `READY` entry 不再刷新。
- `scheduleRetryUntilAllReady()` 只在存在 `FAILED` URL 时启动后台重试。
- `scheduleRetryUntilAllReady()` 内部用 `AtomicBoolean scheduled` 保证幂等；已调度时再次调用直接返回。
- 全部 URL 进入 `READY`，或没有 `FAILED` URL 时，取消后台任务并把 `scheduled` 置回 `false`。
- refresh 修改 cache snapshot 和调用 registry.register 在同一个 cache refresh 锁内完成；当前 `A2ARemoteAgentCardRegistry` 本身使用 `ConcurrentHashMap`，并发 register 是安全的，但仍由 cache 控制同一 URL 的状态迁移顺序。

状态：

```java
enum State {
    PENDING,
    READY,
    FAILED
}
```

对外方法：

```java
List<RemoteA2aToolSpec> availableToolSpecs();

void refreshPendingOnce();

void scheduleRetryUntilAllReady();
```

URL 处理：

- 配置是 base URL 时，通过 A2A SDK resolver 或 `/.well-known/agent-card.json` 获取 card。
- 配置是 `/.well-known/agent-card.json` 或 `/.well-known/agent.json` 时，先归一化为 runtime base key。
- card 发现结果必须能同步注册到 `A2ARemoteAgentCardRegistry`，确保编排层可按 `agentName` 解析远端 URL。
- v1 不把 endpoint 放入 interrupt context，由编排层从 registry 读取。

cache 到 registry 的数据流：

1. 对配置 URL 拉取原始 `AgentCard`。
2. 按工具命名规则生成 `remoteAgentId/toolName`，并用同一个值作为 registry name。
3. 校验 `AgentCard.supportedInterfaces[0].url` 可被 `A2ARemoteAgentCardRegistry.resolveUrl(name)` 读取；如果 card 缺少可调用 URL，则该 URL 进入 `FAILED`，不生成工具。
4. 调用 `registry.register(remoteAgentId, card)` 保存原始 card。
5. 生成 `RemoteA2aToolSpec`。spec 不保存 endpoint，interrupt context 也不保存 endpoint；后续编排层只通过 `agentName -> registry.resolveUrl(agentName)` 找远端地址。

### 3.4 RemoteA2aToolSpec

远端 card 映射为工具契约。v1 不单独拆 mapper 文件，也不单独保留 `RemoteA2aToolSpec.java`；映射逻辑和 spec record 都放在 `RemoteA2aAgentCardCache` 内部。

映射规则：

- 没有 skills 的 card 不生成工具。
- v1 是“一个远端 agent/card -> 一个文本透传工具”。
- 多 skill card 不拆成多个本地工具；description 合并 skills 描述，输入仍使用统一文本 envelope。
- 配置了 `agents[].name` 时，该值就是本地注入工具名和 `remoteAgentId`。实现只 `trim` 前后空白，然后做底线合法性校验；不改大小写，不把空格、下划线、短横线等字符改写成其他形式。
- 没配置 `agents[].name` 时，才从远端 `card.name` 推导本地工具名；推导值统一规范化为小写 kebab-case，避免把远端展示名直接暴露成本地可调用工具名。
- `card.name` 为空或规范化后为空时，使用 `remote-agent`。
- 重名时在最终工具名后追加序号，例如 `agent-b-2`、`agent-b-3`；如果显式配置名为 `Agent B`，则冲突名生成 `Agent B-2`。
- `toolName` 默认等于 `remoteAgentId`。
- `description` 由 card skills 的 description 拼接生成。
- `inputSchema` 使用统一 envelope；v1 不映射远端 skill 的结构化 inputSchema。

命名边界：

- `agents[].name` 是本地调用约定，优先级高于远端 card。推荐配置稳定、清晰、模型易调用的名字，例如 `agent-b` 或 `versatile-agent`。
- 显式配置名的合法性校验只用于阻止空值、控制字符等明显不可作为 tool name 的值；不要把它当作自动规范化入口。
- 自动推导名来自远端 `card.name`，而 `card.name` 更接近远端展示名，不保证适合作为本地工具名，所以必须规范化后再注入。
- `remoteAgentId`、registry name、`ToolCard.id`、`ToolCard.name`、`BaseInterruptRail` 拦截的 `toolName` 必须使用同一个最终工具名。

`RemoteA2aToolSpec` 作为 cache 内部 record：

```java
public record RemoteA2aToolSpec(
        String remoteAgentId,
        String toolName,
        String description,
        Map<String, Object> inputSchema) {
}
```

统一输入 schema 会原样写入 `ToolCard.inputParams`。rail 不提供真实 Tool 实例，LLM 能看到的工具入参完全来自 `ToolCard.inputParams`。

统一输入 schema：

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

### 3.5 RemoteA2aToolInstaller

职责：

- 从 `RemoteA2aAgentCardCache.availableToolSpecs()` 读取当前工具列表。
- 把每个 spec 转成 AgentCore `ToolCard`。
- 为本次新增 spec 创建 `RemoteA2aInterruptRail`，把 `ToolCard` 放入 rail 的 `getTools()`。
- 注册 `RemoteA2aInterruptRail`；`BaseAgent.registerRail()` 会把 rail 自带的 `ToolCard` 加到 agent ability manager。

spec 到 `ToolCard` 的字段映射：

```java
ToolCard.builder()
        .id(spec.toolName())
        .name(spec.toolName())
        .description(spec.description())
        .inputParams(spec.inputSchema())
        .build();
```

`ToolCard.id`、`ToolCard.name` 和 `BaseInterruptRail` 拦截的 `toolName` 必须完全一致。

支持对象：

- `com.openjiuwen.core.singleagent.BaseAgent`：调用 `agent.registerRail(remoteA2aInterruptRail)`。不注册真实 Tool 实例，工具可见性来自 rail 的 `getTools()`。
- `String agentId` 场景记录 info/warn 日志并跳过安装，避免安装对象和 Runner 实际执行对象不一致。
- `com.openjiuwen.harness.deep_agent.DeepAgent`：rail 注册到内部 ReActAgent，即 `deepAgent.getAgent().registerRail(remoteA2aInterruptRail)`。不走 `deepAgent.registerHarnessTool(...)`，因为本设计没有真实 Tool 实例，只有 rail 携带的 `ToolCard`。`DeepAgent` 源码使用 Lombok `@Getter` 暴露内部 `ReActAgent agent`。

安装规则：

- 每次 `query()` / `streamQuery()` 前调用。
- 先调用 `RemoteA2aAgentCardCache.refreshPendingOnce()`。
- 如果存在 `FAILED` URL，调用 `scheduleRetryUntilAllReady()` 启动幂等后台重试。
- 读取 `availableToolSpecs()` 并安装当前已成功发现的工具；没有可用 spec 时跳过安装。
- 如果 agent 是 `String agentId`，记录“agent-id mode cannot install remote A2A tools in v1”并返回。
- 对同一 agent 实例幂等。
- 如果 ability manager 已有同名 tool card，不重复添加 card。
- installer 按目标 agent 的 `toolName` 记录已安装集合；同名工具不重复注册。
- 首批成功 URL 注册一个 rail；后续失败 URL 成功后，installer 在下一次 query 前只为新增 spec 注册新的 rail。AgentCore 的 `AgentCallbackManager.registerRail(...)` 支持多个 rail 并存，并会把每个 rail 自带的 `ToolCard` 加入 ability manager。

ReActAgent 与 DeepAgent 支持结论：

- ReActAgent 继承 `BaseAgent`，工具列表来自 `AbilityManager.listToolInfo()`，工具执行走 `AbilityManager.execute(...)`；BaseAgent 的 `registerRail(AgentRail)` 会初始化 rail、注册 callback，并把 rail 自带 tool card 加入 ability manager。
- DeepAgent 外层不是 `BaseAgent`，但内部持有 ReActAgent；配置 rail 也是注册到内部 ReActAgent。本设计直接把 `RemoteA2aInterruptRail` 注册到内部 ReActAgent。
- 因此 v1 可以同时支持 ReActAgent/BaseAgent 和 DeepAgent，但 installer 必须写成两个明确分支，不能只按 `BaseAgent` 强转。

### 3.6 Rail 携带 ToolCard

远端 A2A 工具不需要真实 Tool 实例，参考 `agent-service-a2a-test` 的 `A2AToolRail`：

- `RemoteA2aInterruptRail` 继承 `BaseInterruptRail`。
- 构造时把所有远端 `toolName` 传给 `BaseInterruptRail`，用于 beforeToolCall 拦截。
- 同时为每个远端工具创建 `ToolCard`，加入 rail 的 `getTools()`。
- `BaseAgent.registerRail()` 会把 `rail.getTools()` 加到 ability manager，LLM 因此能看到这些工具。

```text
LLM calls remote tool
  -> RemoteA2aInterruptRail intercepts
  -> return InterruptRequest
```

关键约束：对匹配的远端 toolName，rail 不能放行到真实工具执行。首次调用必须 `interrupt(...)`；resume 时必须 `reject(resumeToolResult)` 并设置跳过工具执行。否则 AgentCore 会继续查找真实 Tool 实例，出现 tool not found。

### 3.7 RemoteA2aInterruptRail

继承 `BaseInterruptRail`，构造时传入所有远端 toolName。

首次工具调用：

- 解析 `ToolCall.getName()` 找到 `RemoteA2aToolSpec`。
- 解析 `ToolCall.getArguments()`。
- 生成 `InterruptRequest`。
- `InterruptRequest.message` 写入远端 user message。
- `InterruptRequest.context` 写入 `agentName` 和 `_interrupt_kind`。
- 交给 `BaseInterruptRail.interrupt(request)`，由 `BaseInterruptRail.applyDecision()` 抛出 `ToolInterruptException`；后续 Runner 把该异常转换成 `OutputSchema(type="__interaction__")`，再由 `JiuwenCoreAgentHandler.normalizeChunk()` 规整成 `QueryChunk.TYPE_INTERRUPT`。

resume：

- `BaseInterruptRail.getUserInput()` 从 `ctx.extra["_resume_user_input"]` 读取 resume 输入；如果是 `InteractiveInput`，优先按 `toolCallId` 取对应值。
- 如果 `resumeInput != null`，不要放行到真实工具查找。
- 返回 `reject(resumeToolResult)`。
- 否则原样返回 resumeInput。

`reject(resumeToolResult)` 的注入机制：

1. `BaseInterruptRail.applyDecision()` 收到 `RejectResult` 后写入 `ctx.extra["_skip_tool"]=true`。
2. 同时调用 `ToolCallInputs.setToolResult(resumeToolResult)`。
3. 如果没有显式 `ToolMessage`，`BaseInterruptRail` 用 `String.valueOf(resumeToolResult)` 和当前 `toolCallId` 构造 `ToolMessage`。
4. `AbilityManager` 看到 `_skip_tool=true` 后不再查找真实 Tool 实例，直接返回 `ToolExecutionEntry(toolResult, toolMessage)`。
5. ReActAgent 把该 `ToolMessage` 加回上下文，LLM 基于远端结果继续生成最终回答。

因此第二轮 resume 不能返回 `approve()`；`approve()` 会继续执行真实工具查找，而远端 A2A 工具只有 rail 携带的 `ToolCard`，没有真实 Tool 实例。

interrupt 输出经过父类 `normalizeChunk()` 后，编排层应看到：

```json
{
  "type": "__interaction__",
  "message": "Find hotels in Beijing",
  "toolName": "agent-b",
  "toolCallId": "call-xxx",
  "context": {
    "_interrupt_kind": "a2a_delegate",
    "agentName": "agent-b"
  }
}
```

字段来源：

- `message`：只取 tool arguments 中的 `remoteInput` 字符串；没有 `remoteInput` 时回退为原始 arguments 字符串。`RemoteA2aInterruptRail` 不解析远端业务参数结构。
- `context.agentName`：来自 `RemoteA2aToolSpec.remoteAgentId`，必须能被 `A2ARemoteAgentCardRegistry` 解析。
- `toolName` / `toolCallId`：由 AgentCore interrupt request 透出，父类 `JiuwenCoreAgentHandler.toInterruptData()` 会提升到 data 顶层。
- rail 按 `agent-service-a2a-test` 的 `A2AToolRail` 约定输出 `_stream_mode=sse`。Agent A 到 Agent B 这次远端 A2A client 是否走流式，由 interrupt context 显式声明；编排层只读取该字段并选择 `callStreaming()` 或 `callSync()`。

v1 不输出 `runtime.remote.*` 字段。endpoint 和远端 task 由现有 A2A 编排层按 `agentName` 从 registry 解析。

## 4. 执行流程

### 4.1 启动

```text
Spring Boot
  -> AgentCoreExtAutoConfiguration
  -> bind RemoteA2aProperties
  -> create RemoteA2aAgentCardCache
  -> create RemoteA2aToolInstaller
  -> wait for custom AgentHandler
```

### 4.2 Card 刷新

```text
RemoteA2aToolInstaller.install(agent)
  -> RemoteA2aAgentCardCache.refreshPendingOnce()
  -> resolve AgentCard for PENDING URLs
  -> map successful cards to RemoteA2aToolSpec
  -> publish availableToolSpecs snapshot for READY URLs
  -> schedule retry while any URL is FAILED
```

刷新策略：

- 第一次安装前触发一次刷新。
- 单个 URL 成功后固定该 URL 的 card/spec，不再刷新该 URL。
- 单个 URL 失败不影响其他已成功工具安装。
- 失败 URL 不在 query 线程同步重试，只由后台任务按固定间隔重试。
- 后台重试调度幂等，重复 install 不会重复创建定时任务。
- 全部 URL 成功后停止定时重试。
- 如果一部分 URL 成功、一部分失败，installer 先安装成功项；失败项后续成功后，在下一次 query 前补装。

### 4.3 本地查询触发远端工具

```text
ServeOrchestrator
  -> JiuwenCoreAgentExtHandler.streamQuery(request)
  -> RemoteA2aToolInstaller.install(getAgent())
  -> super.streamQuery(request)
  -> Runner.runAgentStreaming(agent, buildInputs(request), conversationId, ...)
  -> LLM selects remote tool
  -> RemoteA2aInterruptRail returns InterruptRequest
  -> JiuwenCoreAgentHandler.normalizeChunk()
  -> QueryChunk.TYPE_INTERRUPT
  -> ServeOrchestrator / A2A layer handles remote invocation
```

### 4.4 远端结果 resume

```text
A2A layer gets remote result
  -> resume same local conversation
  -> AgentCore resumes interrupted tool call
  -> RemoteA2aInterruptRail receives resumeInput
  -> reject(resumeToolResult)
  -> AgentCore treats remote result as tool output
  -> local LLM continues
  -> final answer
```

## 5. Auto-configuration

自动配置收敛为一个文件：`AgentCoreExtAutoConfiguration`，机制对齐 `agent-service-adapters-versatile`：模块通过 Spring Boot auto-configuration 被宿主应用加载，但不自动注册 `AgentHandler`。auto-config 只负责绑定 properties 和提供 remote A2A 辅助 bean；真正的 `AgentHandler` 由宿主应用显式声明。

### 5.1 Handler 声明方式

`agentcore-ext` 不自动创建 `AgentHandler`，也不通过 `agent-id` 创建可注入远端工具的 handler。宿主应用需要显式声明 `AgentHandler`，并传入具体 agent 实例：

```java
@SpringBootApplication
public class AgentCoreExtQueryDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentCoreExtQueryDemoApplication.class, args);
    }

    @Bean
    AgentHandler agentHandler(BaseAgent agent,
                              MiddlewareAdapterRegistrar registrar,
                              RemoteA2aToolInstaller installer) {
        return new JiuwenCoreAgentExtHandler(agent, registrar, installer);
    }
}
```

如果宿主应用已经在其他配置类里创建了 agent，也可以只声明 handler：

```java
@Bean
AgentHandler agentHandler(BaseAgent agent,
                          MiddlewareAdapterRegistrar registrar,
                          RemoteA2aToolInstaller installer) {
    return new JiuwenCoreAgentExtHandler(agent, registrar, installer);
}
```

这里的 `RemoteA2aToolInstaller` 是构造函数注入的 Spring bean。不要在 `JiuwenCoreAgentExtHandler` 构造函数里手动 new `RemoteA2aAgentCardCache` / `RemoteA2aToolInstaller`；cache、installer、registry、HTTP client、后台 retry 等依赖由 Spring 管理。

DeepAgent 场景同样由业务显式声明：

```java
@SpringBootApplication
public class AgentCoreExtDeepAgentDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentCoreExtDeepAgentDemoApplication.class, args);
    }

    @Bean
    AgentHandler agentHandler(DeepAgent agent,
                              MiddlewareAdapterRegistrar registrar,
                              RemoteA2aToolInstaller installer) {
        return new JiuwenCoreAgentExtHandler(agent, registrar, installer);
    }
}
```

等价的独立配置类写法：

```java
@Bean
AgentHandler agentHandler(DeepAgent agent,
                          MiddlewareAdapterRegistrar registrar,
                          RemoteA2aToolInstaller installer) {
    return new JiuwenCoreAgentExtHandler(agent, registrar, installer);
}
```

对应配置：

```yaml
openjiuwen:
  service:
    handler: agentcore-ext
    agentcore-ext:
      remote-a2a:
        agents:
          - url: http://localhost:18091
            name: agent-b
```

如果业务只配置 `openjiuwen.service.agent-id`，本模块不会创建 ext handler；如果业务自己用 `String agentId` 构造 ext handler，installer 在运行时打日志并跳过远端工具安装。

### 5.2 与上游 agentcore 的关系

上游 `agentcore` 使用：

```text
openjiuwen.service.handler=agentcore
```

ext 使用：

```text
openjiuwen.service.handler=agentcore-ext
```

不要抢上游 `agentcore` 条件。`handler=agentcore-ext` 是宿主层约定值，只用于启用 properties 和 remote A2A 辅助 bean；它不触发 `AgentHandler` 自动创建。

### 5.3 external bean 条件

```text
openjiuwen.service.agentcore-ext.remote-a2a.agents[0].url is present
```

未配置远端 URL 时，installer 不持有 cache，handler 行为退化为普通 `JiuwenCoreAgentHandler`。

### 5.4 Auto-config 分工

| Auto-configuration | Bean | 条件 |
| --- | --- | --- |
| `AgentCoreExtAutoConfiguration` | `RemoteA2aProperties` | 配置绑定 |
| `AgentCoreExtAutoConfiguration` | `RemoteA2aAgentCardCache` | `handler=agentcore-ext` 且存在 `remote-a2a.agents[].url` |
| `AgentCoreExtAutoConfiguration` | `RemoteA2aToolInstaller` | 有 cache 时持有 cache，否则不持有 cache |
| `AgentCoreExtAutoConfiguration` | `AgentHandler` | 不创建 |

## 6. 与 A2A 编排层的契约

本模块输出的是标准 AgentCore interrupt：

- `QueryChunk.TYPE_INTERRUPT`
- normalized payload 中 `type="__interaction__"`
- context 中包含 `_interrupt_kind=a2a_delegate`
- context 中包含 `agentName`
- data 顶层包含 `message`、`toolName`、`toolCallId`

当前 `A2AEnabledServeOrchestrator` 处理步骤：

1. 从 `context.agentName` 读取远端 agent 名。
2. 从 data 顶层 `message` 读取远端 A2A user message。
3. 从 interrupt context 读取 `_stream_mode`；值为 `sse` 时使用流式 A2A client 调用远端 agent，否则使用同步调用。
4. 远端流式输出时按现有 A2A 流式规则透传或聚合。
5. 远端返回 INPUT_REQUIRED 时保存 shadow task。
6. 用户 resume 时把远端结果作为本地 AgentCore resume input。

endpoint 解析沿用当前编排层：按 `agentName` 从 `A2ARemoteAgentCardRegistry` 查询。v1 不通过 interrupt context 传 endpoint，也不传 stream timeout。

## 7. 错误处理

- 远端 card 拉取失败：记录日志，失败 URL 进入定时重试；已成功 URL 不受影响。
- card 无 skills：不注入工具。
- handler 只有 `String agentId`：记录日志并跳过远端工具安装，普通查询继续。
- agent 类型不支持安装：记录 warning 并跳过远端工具安装，普通查询继续。
- rail 对远端 toolName 放行：这是实现错误，会导致 AgentCore 查找不存在的真实 Tool 实例；测试必须保证首次调用 interrupt、resume 时 reject。
- resume 输入不匹配：优先按 toolCallId 取值，取不到就原样返回。
- 多并发安装：installer 必须幂等，不能无限重复注册 rail。

## 8. 测试

### 8.1 单元测试

`RemoteA2aAgentCardCacheTest`

- URL 归一化。
- 发现到 card 后可按 `agentName` 注册到 `A2ARemoteAgentCardRegistry`。
- 无 skills 不生成 spec。
- 重名 agentId 稳定去重。
- `refreshPendingOnce()` 只同步刷新 `PENDING` URL。
- 单个 URL 成功后进入 `READY` 且不再刷新。
- 单个 URL 失败后进入 `FAILED`，query 线程不再同步刷新该 URL。
- `scheduleRetryUntilAllReady()` 重复调用不会重复创建定时任务。
- 部分成功时 `availableToolSpecs()` 返回已成功项。
- 全部 URL 成功后停止定时任务。
- registry register 与 cache 状态更新的并发边界明确，重复刷新不会产生状态回退。

`RemoteA2aToolInstallerTest`

- BaseAgent 注册 rail 后，rail 携带的 `ToolCard` 进入 ability manager。
- DeepAgent 内部 ReActAgent 注册 rail 后，rail 携带的 `ToolCard` 进入内部 ReActAgent 的 ability manager。
- DeepAgent 的 rail 注册到内部 ReActAgent。
- DeepAgent 内部 ReActAgent 实例稳定性由最小 spike 或集成测试覆盖。
- `String agentId` 只打日志并跳过安装。
- ability manager 已有 tool 时不重复添加。
- rail 注册幂等。
- `ToolCard.id/name` 与 `BaseInterruptRail` 拦截的 `toolName` 一致。
- 远端 toolName 首次调用不会放行到真实 Tool 查找。

`RemoteA2aInterruptRailTest`

- 首次 tool call 触发 interrupt。
- 生成的 `ToolCard.inputParams` 包含统一 `remoteInput` schema。
- normalized data 顶层包含 `message`、`toolName`、`toolCallId`。
- context 包含 `agentName`、`_interrupt_kind=a2a_delegate`。
- JSON arguments 正常解析。
- 非 JSON arguments 时，`message` 回退为原始 arguments 字符串。
- resumeInput 通过 `reject(...)` 注回，`AbilityManager` 看到 `_skip_tool=true` 后不会查找真实 Tool 实例。
- `reject(...)` 生成的 `ToolMessage.toolCallId` 与原始 tool call id 一致。

### 8.2 自动配置测试

- 用户自定义 `AgentHandler` 时不创建默认 handler。
- `handler=agentcore` 时不创建 `AgentHandler`。
- `handler=agentcore-ext` 时也不创建 `AgentHandler`。
- 无远端配置时创建不持有 cache 的 installer。
- `handler=agentcore-ext` 不用 `agent-id` 自动创建 ext handler。
- 存在 `BaseAgent` 或 `DeepAgent` bean 且没有自定义 `AgentHandler` 时，auto-config 仍不创建 `AgentHandler`。
- 业务显式声明 `AgentHandler` 时，通过构造函数注入 `RemoteA2aToolInstaller`。
- auto-configuration imports 只注册 `AgentCoreExtAutoConfiguration`。

### 8.3 集成测试

基于 `agent-service-a2a-test` 增加：

- Agent B 暴露 card + skills。
- Agent A 配置 Agent B card URL。
- Agent A 自动注入远端工具。
- 用户请求触发 Agent A 远端 tool interrupt。
- A2A 编排层调用 Agent B。
- Agent B INPUT_REQUIRED 时 Agent A 对外返回 INPUT_REQUIRED。
- resume 后远端结果注回 Agent A，Agent A 输出最终答案。

## 9. 实施顺序

1. 补 `pom.xml` 和 auto-configuration imports。
2. 实现 `RemoteA2aProperties`，并在 `RemoteA2aAgentCardCache` 内部实现 `RemoteA2aToolSpec` record。
3. 实现 `RemoteA2aAgentCardCache.refreshPendingOnce()` 和失败 URL 幂等定时重试。
4. 实现 `RemoteA2aInterruptRail`、`RemoteA2aToolInstaller`，由 rail 携带动态生成的 `ToolCard`。
5. 补最小 spike/测试，确认 `super.query()` 前安装的 tool 和 rail 对本次 Runner 执行可见。
6. 实现 `JiuwenCoreAgentExtHandler`：入口前执行 `installBeforeRun()`，随后直接调用 `super.query()` / `super.streamQuery()`。
7. 实现单文件 `AgentCoreExtAutoConfiguration`。
8. 补单元测试。
9. 补端到端测试。

## 10. 验收标准

- 业务显式提供具体 agent 实例时，可以创建 `JiuwenCoreAgentExtHandler`。
- `JiuwenCoreAgentExtHandler` 通过构造函数接收 Spring 管理的 `RemoteA2aToolInstaller`，不在构造函数内部创建 cache/installer。
- 具体 agent 实例为 ReActAgent/BaseAgent 或 DeepAgent 时，都可以安装远端 A2A tool 和 rail。
- 仅配置 `agent-id` 时普通查询继续，远端工具安装被跳过并输出明确日志。
- 配置远端 card URL 后，card skills 转成 AgentCore tool。
- card 发现成功后，原始 `AgentCard` 按 `remoteAgentId` 注册到 `A2ARemoteAgentCardRegistry`，编排层可用 `agentName` 解析远端 URL。
- `super.query()` / `super.streamQuery()` 前安装的 tool 和 rail 对本次 Runner 执行可见。
- DeepAgent 场景下，安装到内部 ReActAgent 的 tool 和 rail 对本次 DeepAgent 执行可见。
- resume 时 `reject(resumeToolResult)` 后，AgentCore 不查找真实 Tool 实例，远端结果作为 `ToolMessage` 回到 LLM 上下文。
- `ToolCard.id/name`、`RemoteA2aToolSpec.toolName`、`BaseInterruptRail` 拦截 toolName 三者一致。
- `PENDING` URL 只在 install 路径首次同步刷新；`FAILED` URL 只由后台任务重试，不拖慢后续 query。
- 后台 retry 调度幂等，重复 query 不会创建重复定时任务。
- 多个远端 URL 中部分失败时，已成功 URL 的工具仍可安装；失败 URL 后续成功后可补装。
- LLM 调用远端 tool 时产生 `QueryChunk.TYPE_INTERRUPT`。
- interrupt data 与当前 `A2AEnabledServeOrchestrator` 对齐：`message`、`toolName`、`toolCallId` 在顶层，`agentName` 和 `_interrupt_kind` 在 context。
- resume 后远端结果能作为工具结果回到 AgentCore。
- 远端不可用不影响本地普通查询。
- 无 skills 的 card 不注入工具。
- 不修改 `vendor/agent-runtime-java` 上游源码。
