---
title: Agent Client SDK V1 设计提案
status: proposed
authority: non-authoritative
module: agent-client
audience:
  - agent-client 维护者
  - agent-bus / agent-gateway 维护者
  - agent-runtime 维护者
  - SDK 下游应用开发者
updated: 2026-07-14
---

# Agent Client SDK V1 设计提案

> **⚠️ 已被 version-scope 事实要求取代（2026-07-21）：** 本模块两大特性的 authoritative 事实来源现为
> `version-scope/FEAT-006-standard-agent-client-invocation.md`（标准调用）与
> `version-scope/FEAT-007-local-tool-registration-and-execution.md`（本地工具），实现级细化见 L2
> `architecture/L2-Low-Level-Design/agent-client/Feat-Func-006-*.md` / `Feat-Func-007-*.md`。
> 本文保留为**模块级设计理由 / 决策记录 / 路线图**参考；其中的需求/接口/线协议表述若与上述 FEAT-006/007 或 L2 文档冲突，**一律以 FEAT-006/007 与 L2 为准**。
>
> 重要声明：本文是 proposed / non-authoritative 设计草案，不是当前实现说明，也不是已经冻结的线协议。
> 文中出现的类型、包名、接口、状态、错误码和阶段划分都需要 Lucio、agent-bus、agent-runtime
> 及首批下游共同评审。未经协议评审和契约测试，不得把本文作为已发布兼容性承诺。
>
> 配套文档：
> - client↔gateway 线协议（request/response 原始格式）：现以 L2
>   `architecture/L2-Low-Level-Design/agent-client/Feat-Func-006-*.md` §3.5 与
>   `Feat-Func-007-*.md` §3.5 为准，**已对齐 runtime `architecture/L2-Low-Level-Design/agent-runtime/Feat-Func-009-*.md`**。
> - 端侧接入最佳实践与测试方法：`agent-client/docs/getting-started.md`
> - 2026-07-14 口头对齐（待书面确认）：client↔gateway 采用标准 A2A 协议（JSON-RPC +
>   SSE），即本文 L-01/L-02 的方向已初步敲定；本地工具多轮驱动叠加在 A2A `INPUT_REQUIRED`
>   / 续跑语义上（对应 L-05/L-06）。
> - **⚠️ 线协议对齐更新（2026-07-17）**：原 `ascend-client-tools/v1` 结构化扩展已按 `Feat-Func-009`
>   裁剪——工具目录走 `params.metadata.clientTools`（`name`/`description`/`inputSchema`），调用意图走
>   `_interrupt`（`_interrupt_kind=client_tool`），结果走普通 TextPart observation 文本（单 pending、
>   不回传 `toolCallId`）。结构化结果 / outcome 枚举 / `tool_call_id` 回传 / 并行工具降级为客户端内部
>   模型与 V1.1 诉求（见 Feat-Func-007 §9）。**唯一保留差异是拓扑：client 连 gateway**（对 gateway 的
>   要求见 Feat-Func-006 §8 / Feat-Func-007 §8）。
> - 术语对齐：本文 `serverTaskId` 即 A2A `message.taskId`；`toolCallId` 由服务端 `_interrupt` 给出、
>   client 本地去重用（V1 不回传）；`clientInvocationId` 的 UNKNOWN 恢复由 A2A 必填 `messageId`
>   + gateway 去重承担，不再单设字段。

## 1. 目标与范围

本文把两项口头需求拆解为一版可实施的 Agent Client SDK 方案：

1. 提供标准化智能体服务调用 API，并在客户端管理一次调用的本地状态投影。
2. 提供本地工具的标准化 SPI 与注册管理，使远端智能体能够驱动端侧工具，并通过多轮请求返回执行结果。

这里的“客户端状态管理”只表示 SDK 对服务端事实的本地投影、关联、恢复和订阅管理，不表示
SDK 获得服务端 Task 的写权限。服务端 Task 的最终状态、状态转换和结果仍由 agent-runtime
拥有；agent-bus / agent-gateway 负责入口治理、路由和消息交付，也不成为 Task owner。

本文优先沉淀跨业务共性：

- 稳定、框架中立的 Java API 与 SPI。
- 调用、流、工具三类生命周期。
- 幂等、超时、取消、重试、背压和恢复语义。
- 租户、身份、trace、审计和敏感数据边界。
- 可替换 transport、状态存储和集成适配器。

本文不引入具体业务 Agent、业务工具名称、客户私有权限模型、客户 Redis 组件或某个下游应用的定制字段。

## 2. 仓库现状与证据

以下是 2026-07-14 的仓库事实。它们用于约束方案，不表示缺失能力已经实现。

### 2.1 agent-client 仍是占位模块

- agent-client 的生产包明确写明 SDK implementation lands in a later wave：
  agent-client/src/main/java/com/huawei/ascend/client/package-info.java:2-7。
- client SPI 包也只是为了模块规则保留的 placeholder：
  agent-client/src/main/java/com/huawei/ascend/client/spi/package-info.java:2-7。
- 模块元数据描述的目标包括 HTTP client、Task Cursor、SSE/Webhook receiver，但仍标记 skeleton：
  agent-client/module-metadata.yaml:6-15。
- 根 POM 当前版本为 0.2.0-SNAPSHOT，而 agent-client 父版本仍为 0.1.0-SNAPSHOT：
  pom.xml:20-21 与 agent-client/pom.xml:20-22。实施前必须先修复构建基线。
- agent-client 当前没有 HTTP、A2A、agent-bus 或 JSON codec 的生产依赖：
  agent-client/pom.xml:29-40。

### 2.2 架构已经规定 client 的职责和负面边界

模块责任卡把 agent-client 定位为业务应用或个人本地环境中的 SDK / local capability endpoint，
其目标职责包括 request packaging、Task Cursor、SSE、S2C callback、本地工具和调试证据：
docs/architecture/l0/04-modules/module-responsibility-cards.md:140-160。

同一责任卡明确禁止 client：

- 直接调用 compute-control 内部 route。
- 导入 agent-runtime、agent-core 或 agent-middleware。
- 写服务端 Task / Session 状态。
- 绕过 S2C contract 返回本地工具结果。

对应证据为：
docs/architecture/l0/04-modules/module-responsibility-cards.md:149-154，
以及 agent-client/src/test/java/com/huawei/ascend/client/architecture/EdgeToComputeDirectLinkArchTest.java:11-25。

### 2.3 允许的 Ingress SPI 尚未形成运行链

IngressGateway 被定义为 client 到 compute-control 的单一跨平面入口：
agent-bus/src/main/java/com/huawei/ascend/bus/spi/ingress/IngressGateway.java:3-18。

但同一文件明确写明 interface shipped; no runtime binding：
agent-bus/src/main/java/com/huawei/ascend/bus/spi/ingress/IngressGateway.java:20-24。
其契约也仍是 design_only / runtime_enforced: false：
docs/contracts/ingress-envelope.v1.yaml:28-36。

当前 IngressEnvelope 只表达 RUN_CREATE、RUN_GET、RUN_CANCEL、RUN_RESUME：
agent-bus/src/main/java/com/huawei/ascend/bus/spi/ingress/IngressEnvelope.java:19-27,62-65。
它尚不能完整表达 A2A Message Parts、accepted 与 stream-ready 分离、input-required、
工具调用、多轮结果回传和事件游标。

因此 V1 不能把现有 IngressGateway 或 ingress-envelope/v1 当成已经可调用的生产接口。

### 2.4 agent-runtime 已有较完整的 A2A 服务端表面

当前 runtime 已提供：

- Agent Card 标准及兼容发现入口：
  agent-runtime/src/main/java/com/huawei/ascend/runtime/boot/AgentCardController.java:27-35。
- POST /a2a 与 POST /a2a/ JSON-RPC 入口：
  agent-runtime/src/main/java/com/huawei/ascend/runtime/boot/A2aJsonRpcController.java:65-75。
- SendStreamingMessage 与 SubscribeToTask：
  agent-runtime/src/main/java/com/huawei/ascend/runtime/boot/A2aJsonRpcController.java:118-142。
- SendMessage、GetTask、ListTasks、CancelTask 与 Push Notification Config CRUD：
  agent-runtime/src/main/java/com/huawei/ascend/runtime/boot/A2aJsonRpcController.java:185-206。
- AgentRuntimeHandler 框架中立执行缝：
  agent-runtime/src/main/java/com/huawei/ascend/runtime/engine/spi/AgentRuntimeHandler.java:17-62。
- OUTPUT、COMPLETED、FAILED、INPUT_REQUIRED 到 A2A 表面的映射：
  agent-runtime/src/main/java/com/huawei/ascend/runtime/engine/a2a/A2aResultRouter.java:60-125。

这说明 SDK 的 northbound 语义应向 A2A Task / Message / SSE 对齐，但不表示 SDK 公共 API
应该暴露 org.a2aproject 类型。A2A 类型只能存在于 transport / codec adapter 内部。

### 2.5 当前 Task 状态默认只在内存中

RuntimeAutoConfiguration 默认装配 InMemoryTaskStore、InMemoryPushNotificationConfigStore
和 InMemoryQueueManager：
agent-runtime/src/main/java/com/huawei/ascend/runtime/boot/RuntimeAutoConfiguration.java:61-92。

仓库目标文档描述了 Redis-backed TaskStore，但当前生产代码尚不能据此声称已经具备 durable Task。
SDK 必须假设服务端状态可能在进程重启后丢失，并把“服务端找不到 Task”和“本地状态丢失”
作为可诊断错误，而不是自行重建或伪造 Task。

### 2.6 bus forwarding 与事件转发仍有 PoC / draft 成分

ForwardingEnvelope 已经具备 tenant、trace、correlation、idempotency、route、capability、
deadline 和 payloadRef 等治理字段，并明确禁止承载 payload body、token stream 和 Task state：
agent-bus/src/main/java/com/huawei/ascend/bus/forwarding/spi/ForwardingEnvelope.java:6-20,27-38。

但当前 A2A forwarding adapter 是同步等待远端终态的 Stage-15 PoC，并把 INPUT_REQUIRED
也视作投递 ACK：
agent-bus/src/main/java/com/huawei/ascend/bus/forwarding/runtime/transport/a2a/A2aForwardingDeliveryPort.java:34-58。
其消息正文目前只是 forwarding message id，payloadRef 放在 metadata：
同文件 217-236。

version-scope 中 FEAT-011、FEAT-012 为 active，但 FEAT-013、FEAT-014、FEAT-015、
FEAT-016、FEAT-017 仍包含 draft 状态：
version-scope/README.md:49-56。V1 必须通过正式 wire contract 和可执行 contract test
确认实际可用表面，不能只依据状态标签推断实现已落地。

### 2.7 Registry 与 A2A Agent Card 尚未统一

agent-runtime northbound 使用 org.a2aproject.sdk.spec.AgentCard，而 agent-bus registry
定义了另一套 com.huawei.ascend.bus.spi.registry.AgentCard：

- agent-runtime/src/main/java/com/huawei/ascend/runtime/boot/AgentCardController.java:5,19。
- agent-bus/src/main/java/com/huawei/ascend/bus/spi/registry/AgentCard.java:25-42。

Registry 已有内部发现和 opaque route handle 解析 SPI：
agent-bus/src/main/java/com/huawei/ascend/bus/spi/registry/AgentDiscoveryService.java:54-113。
但当前 HTTP controller 只有 register 和 deregister：
agent-bus/src/main/java/com/huawei/ascend/bus/registry/runtime/api/MvpRegistryController.java:50-80,98-114。

SDK 不应再定义第三套 Agent Card。需要由 Bus / Runtime 明确 registry card 是标准 A2A Card
的投影、扩展还是独立注册命令 DTO。

### 2.8 本地工具已有可借鉴语义，但没有 client 闭环

agent-middleware 已定义：

- Skill 生命周期和 execute/suspend 语义：
  agent-middleware/src/main/java/com/huawei/ascend/middleware/skill/spi/Skill.java:24-66。
- tenant-scoped SkillRegistry：
  agent-middleware/src/main/java/com/huawei/ascend/middleware/skill/spi/SkillRegistry.java:7-27。
- SkillDefinition schema：
  agent-middleware/src/main/java/com/huawei/ascend/middleware/skill/spi/SkillDefinition.java:27-54。
- success/error/suspended 结果：
  agent-middleware/src/main/java/com/huawei/ascend/middleware/skill/spi/SkillResult.java:11-40。

这些类型属于 middleware，client 的负面依赖规则禁止直接复用；只能借鉴其抽象语义。

agent-bus 还定义了 S2C callback request、response 与异步 transport SPI：

- agent-bus/src/main/java/com/huawei/ascend/bus/spi/s2c/S2cCallbackEnvelope.java:39-65。
- agent-bus/src/main/java/com/huawei/ascend/bus/spi/s2c/S2cCallbackResponse.java:24-56。
- agent-bus/src/main/java/com/huawei/ascend/bus/spi/s2c/S2cCallbackTransport.java:26-37。

当前生产代码没有形成 client 工具注册、调用投递、执行、结果 ACK、Task resume 的完整闭环，
所以 V1 必须与 agent-bus 一起冻结这个闭环。

## 3. 设计原则

### 3.1 单一状态权威

- agent-runtime 是服务端 Task 状态的唯一 owner。
- SDK 只保存本地投影、关联信息、订阅位置和工具执行状态。
- agent-gateway / agent-bus 保存路由、投递、幂等和恢复关联，不写 Task execution state。
- 查询、取消、重订阅一旦拿到 serverTaskId，就必须使用 serverTaskId；clientInvocationId
  不得替代 serverTaskId。

### 3.2 公共 API 框架中立

公共 API / SPI 只能依赖 JDK 类型和 agent-client 自有值对象：

- 并发使用 CompletionStage 与 Flow.Publisher。
- 时间使用 Instant 与 Duration。
- endpoint 使用 URI。
- 不暴露 Spring、Reactor、Jackson、A2A SDK、HTTP client 或 broker 类型。
- adapter 内部可以使用 Reactor、Jackson 或 A2A SDK，但必须在边界处转换。

### 3.3 transport 与语义解耦

SDK core 面向标准化 client domain carrier；HTTP JSON-RPC、SSE、Webhook、长轮询或后续
WebSocket 都是 transport adapter。业务应用不感知 routeHandle、topic、consumer group、
offset、runtime endpoint 或 payloadRef 的物理解析细节。

### 3.4 异步优先、阻塞显式

- 所有网络与本地工具调用默认异步。
- SDK 不在公共 API 中隐式 Thread.sleep 或无限阻塞。
- 如果提供 blocking convenience API，应放在独立可选适配层，并要求显式 Duration。
- Flow.Publisher 必须尊重 demand，并使用有界缓冲；溢出策略必须可配置和可观测。

### 3.5 至少一次交付下的幂等

- 调用创建幂等键：tenantId + idempotencyKey。
- bus 投递去重键、clientInvocationId、serverTaskId、toolCallId、callbackId 各自独立，
  不得复用一个字段承担多种语义。
- 本地工具默认按 toolCallId + attempt 去重。
- 有副作用工具必须由业务实现声明 idempotency policy，SDK 不能假设重复执行安全。

### 3.6 安全默认值

- SDK 只连接受配置和校验的 gateway public base URI，不缓存或暴露内部 runtime endpoint。
- tenant 由可信 credential / identity provider 产生；不能把调用方任意设置的 X-Tenant-Id
  当作认证。
- 日志默认不记录 prompt、tool arguments、tool result、token 或 credential。
- 大载荷使用受授权、带过期时间的引用，不在 bus 控制事件或日志中内联。

### 3.7 兼容性可治理

- Java API 使用语义化版本。
- wire envelope 必须有 schemaVersion。
- 新字段以 additive 为默认策略。
- enum 未知值在 codec 边界映射为 UNKNOWN / unsupported，不使旧 SDK 因反序列化直接崩溃。
- 每个发布版本保留 golden fixture 与 client-bus contract test。

## 4. 特性一：标准化智能体服务调用 API

### 4.1 隐含能力

口头需求背后至少包含以下建设：

1. Agent 目标表达  
   支持 agentId、能力名称和可选版本约束；client 不接触 routeHandle 或物理 URL。

2. 能力发现  
   获取标准化 AgentDescriptor，包含名称、版本、能力、输入输出模式、流式支持和工具协作支持。
   MVP 可以先支持明确 agentId，意图搜索后置。

3. 消息与内容模型  
   支持文本、结构化数据、二进制引用、artifact 引用；MVP 不直接承载大二进制。

4. 创建与推进  
   支持首次 invoke 和已有 Task 的 continue，二者不能混为一个不透明 payload。

5. 同步接受与异步完成  
   调用需要先形成 accepted / rejected / unknown，再异步观察 working、input-required 和终态。

6. Task 查询、取消与重订阅  
   Get、Cancel、Subscribe 必须使用 serverTaskId。关闭 SSE subscription 不等于取消 Task。

7. 状态投影  
   对服务端 Task、gateway 接受状态、本地连接状态分别建模，避免一个 status enum 混合三套事实。

8. UNKNOWN 恢复  
   在无法确认是否创建 Task 且尚无 serverTaskId 时，使用同一 clientInvocationId 和
   idempotencyKey 重试原始创建请求；不能另造 ResolveInvocation 私有状态权威。

9. 错误模型  
   区分 validation、authentication、authorization、route、capacity、transport、
   timeout、remote failure、protocol 和 local SDK 错误；携带 retryable 和 correlation。

10. 超时与重试  
    区分 connect timeout、accept timeout、stream idle timeout、overall deadline 和 tool deadline。
    retry policy 不得对非幂等动作盲重试。

11. 可观测性  
    统一传播 traceId、correlationId、clientInvocationId、serverTaskId、agentId 和 tenant scope；
    提供事件 listener / metrics bridge，不绑定某个 telemetry SDK。

12. 资源生命周期  
    AgentClient、InvocationCall、subscription 和 transport 都必须可关闭；应用停止时有界 drain。

### 4.2 建议公共调用对象

| 对象 | 作用 | 关键约束 |
|---|---|---|
| AgentRef | 逻辑目标 | agentId 必填；可带 capability/version constraint；无 endpoint |
| AgentDescriptor | Agent 能力投影 | 来源于 gateway/registry + A2A Card 映射；不是第三套注册事实 |
| InvocationRequest | 一次首次调用 | 包含 AgentRef、message、clientInvocationId、idempotencyKey、deadline 和 context |
| TaskContinuation | 推进已有 Task | serverTaskId 必填；携带 user input 或 tool result reference |
| ClientInvocationId | client/gateway 弱关联 ID | 在获得 serverTaskId 前用于 UNKNOWN 恢复 |
| ServerTaskId | runtime Task 权威 ID | 查询、取消、订阅和 continue 的标准输入 |
| InvocationHandle | 已接受调用句柄 | 必须携带 serverTaskId；可携带 contextId、agentId、correlation |
| InvocationSnapshot | Task 当前投影 | 包含 server Task state、message/artifact refs、版本和更新时间 |
| InvocationEvent | 流式事件 | accepted、status、content delta、artifact、input-required、error、terminal |
| InvocationCall | 一次本地调用控制器 | 暴露 accepted、events、completion、cancelSubscription；不伪造 Task |
| AgentClientError | 稳定错误 | code、message、retryable、correlation、可选 serverTaskId；cause 不上 wire |

### 4.3 建议 Java API

以下代码仅表示建议形状，不代表当前仓库已经存在这些类型。

~~~java
package com.huawei.ascend.client.api;

import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

public interface AgentClient extends AutoCloseable {
    CompletionStage<AgentDescriptor> describe(AgentRef agent);

    InvocationCall invoke(InvocationRequest request);

    CompletionStage<InvocationSnapshot> getTask(
            ServerTaskId taskId,
            RequestContext context);

    CompletionStage<InvocationSnapshot> cancelTask(
            ServerTaskId taskId,
            CancelOptions options,
            RequestContext context);

    Flow.Publisher<InvocationEvent> subscribe(
            ServerTaskId taskId,
            SubscriptionOptions options,
            RequestContext context);

    CompletionStage<InvocationHandle> continueTask(
            TaskContinuation continuation);

    @Override
    void close();
}

public interface InvocationCall extends AutoCloseable {
    CompletionStage<InvocationHandle> accepted();

    Flow.Publisher<InvocationEvent> events();

    CompletionStage<InvocationSnapshot> completion();

    ClientInvocationId clientInvocationId();

    // 只关闭本地订阅和网络资源，不取消服务端 Task。
    @Override
    void close();
}
~~~

建议 builder 是唯一复杂构造入口：

~~~java
AgentClient client = AgentClients.builder()
        .gatewayBaseUri(gatewayUri)
        .credentialProvider(credentialProvider)
        .defaultTimeouts(ClientTimeouts.defaults())
        .retryPolicy(RetryPolicy.safeDefaults())
        .build();
~~~

gatewayBaseUri 是受治理的公开入口，不是 runtime endpoint。若架构最终决定 client 通过进程内
IngressGateway 访问，应该提供另一种 TransportProvider，而不是改变 AgentClient API。

### 4.4 消息和值对象

为避免 Jackson JsonNode 或 A2A Part 泄漏，建议 client 自有内容块：

~~~java
public sealed interface ContentBlock
        permits TextBlock, StructuredBlock, BinaryReferenceBlock, ArtifactReferenceBlock {
}

public record TextBlock(String text, String mediaType) implements ContentBlock {
}

public record StructuredBlock(StructuredData value, String schemaRef)
        implements ContentBlock {
}

public record BinaryReferenceBlock(
        java.net.URI uri,
        String mediaType,
        Long sizeBytes,
        String checksum) implements ContentBlock {
}
~~~

StructuredData 只允许 null、boolean、number、string、list 和 string-keyed object，
在构造时深拷贝和校验。transport adapter 负责把它映射到 JSON / A2A DataPart。

### 4.5 错误与异常

异步方法通过 CompletionStage exceptional completion 报告错误。建议公共异常：

~~~java
public final class AgentClientException extends RuntimeException {
    private final ClientError error;
}

public record ClientError(
        ClientErrorCode code,
        String message,
        boolean retryable,
        String correlationId,
        ServerTaskId taskId,
        java.util.Map<String, String> safeDetails) {
}
~~~

ClientErrorCode 的 MVP 稳定集合建议包括：

- INVALID_REQUEST
- AUTHENTICATION_FAILED
- AUTHORIZATION_DENIED
- AGENT_NOT_FOUND
- ROUTE_UNAVAILABLE
- CAPACITY_EXCEEDED
- ACCEPT_TIMEOUT
- TASK_NOT_FOUND
- TASK_CONFLICT
- STREAM_INTERRUPTED
- DEADLINE_EXCEEDED
- PROTOCOL_ERROR
- REMOTE_TASK_FAILED
- LOCAL_TOOL_FAILED
- SDK_CLOSED
- INTERNAL

远端未知错误码保留在 safeDetails.remoteCode，不动态扩展 Java enum。

## 5. 特性二：本地工具 SPI 与注册管理

### 5.1 隐含能力

“远端智能体驱动端侧工具并多轮返回结果”至少包含：

1. 工具描述  
   稳定 toolKey、displayName、description、semantic version、input JSON Schema、
   output JSON Schema、side-effect level、timeout 和并发限制。

2. 工具实现 SPI  
   业务实现只处理 client 自有 ToolInvocation / ToolResult，不处理 A2A、HTTP、broker 或 Spring 类型。

3. 注册生命周期  
   register、replace、unregister、list、find；重复 key/version 的行为必须明确。

4. 本地执行调度  
   schema validation、权限判断、并发限制、deadline、取消、线程模型和有界队列。

5. 远端可见性  
   SDK 把工具目录的受控投影注册给 gateway / bus；不得默认把实现类、配置或本地地址暴露出去。

6. 工具调用交付  
   远端产生 tool-call intent，经受控 S2C / callback 通道到 SDK。client 不直接消费内部 broker。

7. 至少一次去重  
   重复投递同一 toolCallId 不重复执行有副作用工具；返回先前结果或当前执行句柄。

8. 多轮 resume  
   Task 进入 INPUT_REQUIRED 或等价受控等待态，SDK 执行工具并通过标准 continue / result ingress
   返回；runtime 接受后恢复 Task。这个循环可以发生多次。

9. 结果交付确认  
   本地执行成功不等于远端已接收。必须分别记录 execution outcome 与 delivery ACK。

10. 失败和超时  
    validation error、policy denied、queue full、execution error、timeout、cancel、
    result delivery error 必须可区分。

11. 工具安全  
    side-effect、required permissions、data classification、sandbox requirement 和审计策略
    必须可声明；SDK 不替业务应用做权限决策。

12. 可观测性  
    toolCallId、callbackId、serverTaskId、toolKey、attempt、duration、outcome 和 trace 关联，
    但不默认记录参数和结果正文。

### 5.2 建议业务对象

| 对象 | 作用 | owner |
|---|---|---|
| ToolDescriptor | 工具公开描述 | 业务应用定义，SDK 校验并投影 |
| ToolKey / ToolVersion | 稳定标识与兼容版本 | 业务应用 |
| LocalTool | 执行 SPI | 业务应用实现 |
| ToolRegistration | 一次本地注册句柄 | SDK |
| ToolCatalogRevision | 工具目录版本 | SDK；gateway 保存投影 |
| ToolInvocation | 一次远端驱动调用 | runtime 创建意图；bus 交付；SDK 执行 |
| ToolCallId | 逻辑调用幂等 ID | 远端 Task owner 生成，跨重投保持稳定 |
| CallbackDeliveryId | 一次交付尝试 ID | bus / callback transport |
| ToolExecutionContext | tenant/trace/deadline/cancel/permission refs | SDK 组装 |
| ToolResult | 成功或结构化失败 | 业务工具返回，SDK 标准化 |
| ToolResultReceipt | 服务端接收 ACK | gateway / runtime 返回，SDK 保存 |
| LocalToolCallRecord | 执行与交付本地状态 | SDK；可插拔 ClientStateStore |

### 5.3 建议工具 SPI

~~~java
package com.huawei.ascend.client.tool.spi;

import java.util.concurrent.CompletionStage;

public interface LocalTool {
    ToolDescriptor descriptor();

    default CompletionStage<Void> start(ToolLifecycleContext context) {
        return java.util.concurrent.CompletableFuture.completedFuture(null);
    }

    CompletionStage<ToolResult> execute(
            ToolInvocation invocation,
            ToolExecutionContext context);

    default CompletionStage<Void> stop() {
        return java.util.concurrent.CompletableFuture.completedFuture(null);
    }
}

public interface LocalToolRegistry {
    ToolRegistration register(LocalTool tool);

    ToolRegistration replace(LocalTool tool);

    java.util.Optional<ToolDescriptor> find(ToolKey key);

    java.util.List<ToolDescriptor> list();

    CompletionStage<Void> unregister(ToolKey key);
}
~~~

建议结果使用封闭层次：

~~~java
public sealed interface ToolResult
        permits ToolSuccess, ToolFailure, ToolCanceled {
}

public record ToolSuccess(
        java.util.List<ContentBlock> content,
        java.util.Map<String, String> safeMetadata) implements ToolResult {
}

public record ToolFailure(
        ToolErrorCode code,
        String message,
        boolean retryable,
        java.util.List<ContentBlock> diagnosticContent) implements ToolResult {
}

public record ToolCanceled(String reason) implements ToolResult {
}
~~~

ToolResult 不提供 arbitrary Throwable 上送；SDK 只把白名单错误字段发给远端。

### 5.4 建议工具调用通道 SPI

工具注册表与远端交付通道是两件事。建议提供 transport-neutral channel：

~~~java
package com.huawei.ascend.client.tool.spi;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

public interface ToolCallChannel extends AutoCloseable {
    CompletionStage<ToolCatalogReceipt> publishCatalog(
            ToolCatalogSnapshot catalog);

    Flow.Publisher<ToolCallEnvelope> requests();

    CompletionStage<ToolResultReceipt> submitResult(
            ToolResultEnvelope result);

    @Override
    void close();
}
~~~

ToolCallChannel 的 HTTP/S2C/long-poll/WebSocket 实现属于 adapter。业务 LocalTool 不知道
callback URL、topic、offset、routeHandle 或 A2A Task internals。

### 5.5 推荐多轮过程

逻辑流程如下，物理 transport 需由 Bus 决策：

1. 应用创建 AgentClient，注册 LocalTool。
2. SDK 生成 ToolCatalogRevision，并向 gateway 发布允许远端使用的工具投影。
3. 应用 invoke，InvocationRequest 关联 clientInstanceId 与 toolCatalogRevision。
4. runtime 执行 Agent，产生 tool-call intent，分配稳定 toolCallId。
5. runtime 把 Task 投影为 INPUT_REQUIRED 或等价等待状态；bus 交付 ToolCallEnvelope。
6. SDK 按 toolCallId 去重，校验 schema / policy / deadline 后调用 LocalTool.execute。
7. SDK 生成 ToolResultEnvelope，使用 serverTaskId + toolCallId + resultAttempt 回传。
8. gateway / runtime 返回 ToolResultReceipt；SDK 标记结果 ACKED。
9. runtime 恢复原 Task；可能产生下一次工具调用，重复第 4 至 8 步。
10. Task 进入 completed、failed、canceled 或 rejected，SDK 完成本地投影并释放调用资源。

必须保持两个独立事实：

- ToolResult 已在本地生成。
- ToolResult 已被 Task owner 接受。

网络故障发生在两者之间时，SDK只能重投同一 toolCallId 的同一逻辑结果，不能再次执行工具。

## 6. 业务对象与状态 owner

| 业务对象 / 状态 | 权威 owner | SDK 可做什么 | SDK 不可做什么 |
|---|---|---|---|
| Agent 定义与 A2A Card | runtime；registry 保存治理投影 | 获取、缓存有 TTL 的只读描述 | 自行修改服务端能力 |
| routeHandle 与 endpoint resolution | agent-bus / gateway | 完全不感知或只持 opaque transport token | 解析、持久化或暴露物理 URL |
| server Task | agent-runtime | 保存 Task snapshot 投影并查询/取消/订阅 | 直接写状态或重建同 ID Task |
| clientInvocationId | SDK 生成，gateway 关联 | UNKNOWN 恢复与日志关联 | 替代 serverTaskId |
| idempotencyKey | SDK/调用方生成，runtime 强制 | 同一创建请求重试复用 | 与 messageId/toolCallId 混用 |
| SSE / event stream | runtime 产出，gateway 桥接 | 订阅、背压、重连、投影 | 把断连解释为 Task 失败 |
| token / artifact body | runtime / 外部对象存储 | 流式消费或按授权引用获取 | 交给 bus 长期缓存 |
| ToolDescriptor | 业务应用 | 校验、注册、发布受控投影 | 改写业务语义 |
| Tool handler | 业务应用 | 调度、超时、取消、隔离 | 把实现类发给平台 |
| tool-call intent | runtime Task owner | 接收、去重、执行本地子状态机 | 改写远端 Task 状态 |
| tool result delivery | SDK + gateway/runtime ACK | 保存结果、重投、确认 | 在无 ACK 时声称远端已恢复 |
| tenant / principal | IAM / gateway | 通过 CredentialProvider 传播可信凭据 | 信任任意用户自报 tenant header |
| trace / audit | 平台端到端；SDK 贡献 client span | 关联、导出安全事件 | 记录未脱敏正文 |
| client local state | SDK / 应用选择的 StateStore | 保存 projection、dedupe、cursor、result | 冒充平台 TaskStore |

## 7. 四层模块方案

V1 可以先在一个 Maven artifact 内按包隔离，稳定后再拆 artifact。不得为追求模块数量提前制造
循环依赖。

### 7.1 第一层：公共 API / SPI

建议包：

- com.huawei.ascend.client.api
- com.huawei.ascend.client.tool.spi
- com.huawei.ascend.client.state.spi
- com.huawei.ascend.client.auth.spi
- com.huawei.ascend.client.observation.spi

职责：

- AgentClient、InvocationCall 和 immutable carrier。
- LocalTool、LocalToolRegistry、ToolCallChannel。
- CredentialProvider、ClientStateStore、ClientObservationListener 等扩展点。
- 只依赖 java.* 和本层类型。

禁止：

- Spring annotation。
- Reactor Mono/Flux。
- Jackson JsonNode/ObjectMapper。
- A2A SDK。
- HTTP、broker、database vendor 类型。

### 7.2 第二层：Core 编排与状态

建议包：

- com.huawei.ascend.client.internal.core
- com.huawei.ascend.client.internal.invocation
- com.huawei.ascend.client.internal.tool
- com.huawei.ascend.client.internal.state

职责：

- 三套状态机及不变量。
- idempotency、UNKNOWN 恢复、tool-call 去重。
- stream subscription 生命周期。
- bounded queue、deadline、retry decision。
- ToolRegistry 默认实现。
- 将 transport event 归一为公共 InvocationEvent。

Core 不理解 A2A JSON、SSE frame 或 broker。

### 7.3 第三层：Transport / Codec Adapter

建议包：

- com.huawei.ascend.client.transport.spi
- com.huawei.ascend.client.transport.gateway
- com.huawei.ascend.client.transport.a2a
- com.huawei.ascend.client.transport.s2c

职责：

- gateway HTTP / JSON-RPC / SSE。
- A2A request/response 与 client domain carrier 映射。
- tool callback channel 的实际 wire 实现。
- auth header、trace propagation、timeout 和连接池。
- schemaVersion、未知 enum、error response 的 codec。

本层可以内部使用 A2A SDK、Jackson、JDK HttpClient 或 Reactor，但公共签名不得泄漏这些类型。

### 7.4 第四层：集成与开发体验

建议后续 artifact / package：

- agent-client-spring-boot-starter。
- com.huawei.ascend.client.spring。
- examples/client-basic。
- examples/client-local-tool。

职责：

- Spring Bean 自动装配与 ConfigurationProperties。
- 应用生命周期 start/stop。
- Micrometer / OpenTelemetry bridge。
- health、debug snapshot、结构化日志。
- 示例、测试 harness 和迁移指南。

starter 只能消费公共 API/SPI，不得让公共 API 反向依赖 Spring。

### 7.5 建议依赖方向

~~~text
integration / starter
        |
        v
transport adapters ---> transport SPI
        |                    |
        v                    v
     core orchestration / state
        |
        v
public API / SPI
~~~

依赖只能向下。工具实现只依赖 public tool SPI。

## 8. 三套状态机

### 8.1 调用状态机

这是 SDK 的本地调用投影，不替代服务端 Task 状态机。

~~~text
NEW
  |
  v
SUBMITTING ----------------------> REJECTED
  |                                  (terminal, no taskId)
  +--------> UNKNOWN
  |             |
  |             +-- same clientInvocationId + idempotencyKey --> SUBMITTING
  |             |
  |             +-- caller gives up --> ABANDONED
  |
  v
ACCEPTED (taskId required)
  |
  +--> SUBMITTED --> WORKING <------+
  |                    |            |
  |                    v            |
  |              INPUT_REQUIRED ----+  continue/tool result accepted
  |                    |
  |                    +--> AUTH_REQUIRED
  |
  +--> COMPLETED
  +--> FAILED
  +--> CANCELED
  +--> REJECTED
       (all terminal)
~~~

不变量：

- UNKNOWN 只允许出现在尚未获得 serverTaskId 时。
- ACCEPTED 以及之后的状态必须有 serverTaskId。
- 获得 serverTaskId 后，accept timeout 不得再投影为 UNKNOWN。
- stream disconnect 不改变 Task 状态。
- close InvocationCall 不等于 CancelTask。
- terminal 不允许回到 working；发现乱序旧事件时丢弃并记录诊断。

### 8.2 Stream subscription 状态机

~~~text
IDLE
  |
  v
CONNECTING ---- fatal ----> FAILED_FATAL
  |
  v
OPEN -------- task terminal --------> DRAINED
  |
  +---- local close ----------------> CLOSED
  |
  +---- retryable transport error --> RETRY_WAIT
                                         |
                                         +--> CONNECTING
                                         +--> FAILED_FATAL (budget exhausted)
~~~

不变量：

- subscription 状态与 Task 状态正交。
- Publisher subscription.cancel 只释放本地 stream。
- 重连使用 serverTaskId；若协议提供 eventCursor，则同时携带 cursor。
- 当前 runtime 未证明 token replay，重连后只保证重新获得 Task 状态和后续事件，
  不承诺补齐所有 token。
- 有界缓冲达到上限时不得无界增长；应选择 fail subscriber、drop 可丢事件或暂停读取，
  并暴露 overflow 诊断。

### 8.3 本地工具调用状态机

~~~text
RECEIVED
  |
  v
VALIDATING ---- invalid/policy denied ---> RESULT_READY(error)
  |
  v
QUEUED -------- queue timeout ----------> RESULT_READY(timeout)
  |
  v
RUNNING ------- error/timeout/cancel ----> RESULT_READY(error/timeout/canceled)
  |
  +--------------------------------------> RESULT_READY(success)
                                              |
                                              v
                                           SENDING
                                              |
                         +--------------------+-------------------+
                         |                                        |
                         v                                        v
                       ACKED                                  RETRY_WAIT
                    (terminal)                                    |
                                                                  +--> SENDING
                                                                  +--> DELIVERY_FAILED
                                                                      (terminal/manual recovery)
~~~

不变量：

- 同一 toolCallId 的重复 RECEIVED 不重复创建执行；返回当前状态或已保存结果。
- RESULT_READY 后不得重新执行工具，只能重投同一逻辑结果。
- ACKED 表示 Task owner 已接收，不只表示 HTTP write 成功。
- deadline 到达后，不再启动尚未开始的工具。
- 运行中的 cancel 是协作式；工具未响应 cancel 时按 policy 标记超时，但不得复用线程做不安全强杀。
- 工具结果正文与错误正文按敏感数据策略处理。

## 9. MVP 与后续范围

### 9.1 V1 MVP 必须包含

调用侧：

- 纯 Java AgentClient API。
- 明确 agentId 的调用；能力搜索可以后置。
- invoke、getTask、cancelTask、subscribe、continueTask。
- accepted / unknown / task projection / terminal。
- clientInvocationId、idempotencyKey、serverTaskId 分离。
- 文本、结构化数据和引用型 ContentBlock。
- gateway public endpoint transport adapter。
- A2A-compatible JSON-RPC / SSE codec adapter，但不向 API 泄漏 A2A 类型。
- 有界 stream buffer、显式 timeout、safe retry、close/drain。
- 稳定 ClientError。

工具侧：

- LocalTool、ToolDescriptor、LocalToolRegistry。
- 静态编程式注册、replace、unregister。
- input/output JSON Schema 文本与基本 schema validation hook。
- tool-call 去重、并发限制、deadline、执行结果与交付 ACK 分离。
- 一种由 Bus 评审通过的 ToolCallChannel transport。
- 至少完成两次连续本地工具调用后 Task terminal 的多轮测试。

治理侧：

- CredentialProvider 与 trace/correlation 传播。
- 默认日志脱敏。
- in-memory ClientStateStore。
- Wire golden fixtures 与 client-bus contract test。
- 无 Spring 的 plain Java 示例。

### 9.2 MVP 明确不做

- client 直连内部 runtime endpoint。
- client 直接消费 Kafka、RocketMQ、NATS 等内部 broker。
- SDK 写服务端 Task / Session 状态。
- 自定义第二套 Task 状态机或用 clientInvocationId 查询服务端 Task。
- token chunk 经 bus 事件存储或重放。
- 内联大文件、多模态大正文。
- 客户专用 IAM、Redis、日志或工具类型。
- 通用脚本执行器、任意代码执行和默认 sandbox。
- 自动发现并暴露应用中所有方法为工具。
- 无界离线缓存和无限期 result retry。
- 工具目录跨设备分布式一致性。

### 9.3 后续候选

- Spring Boot starter 与 annotation adapter。
- durable local ClientStateStore。
- WebSocket / Webhook / long-poll 的替代 ToolCallChannel。
- Agent capability search。
- 动态工具目录增量同步与 lease。
- MCP local tool adapter。
- 沙箱和受限代码执行。
- 多语言协议 SDK。
- artifact 下载器、断点续传和本地缓存。
- 完整 stream cursor / replay（需服务端协议支持）。
- offline tool execution 与人工批准恢复。

## 10. 必须确认的决策清单

未决项必须形成带 owner 和日期的决策记录；口头确认不足以冻结跨团队协议。

### 10.1 与 Lucio 必须确认

| ID | 决策 | 建议默认值 |
|---|---|---|
| L-01 | client 到平台的唯一生产入口是 gateway HTTP，还是允许同进程 IngressGateway adapter | 公共 API 不变；生产默认 gateway，进程内作为可选 transport |
| L-02 | server Task 的权威状态集合及 A2A 版本 | 直接采用 runtime 当前 A2A Task 语义，并保留 UNKNOWN 兼容映射 |
| L-03 | agent-client 是否允许 Maven 依赖 agent-bus SPI | API/core 不依赖；仅可选 adapter 依赖，避免把 bus internal 类型变成 SDK API |
| L-04 | Agent Card / registry card 的单一事实和转换 owner | A2A Card 是服务能力事实；registry card 是 bus 治理投影 |
| L-05 | 本地工具等待是否统一映射为 INPUT_REQUIRED | MVP 是；同时增加 tool-call discriminator，不能只靠 prompt 文本识别 |
| L-06 | 工具结果如何推进 Task | 明确 ContinueTask / tool-result ingress，不复用普通新建调用 |
| L-07 | clientInvocationId 的恢复语义 | 只在未获得 taskId 的 UNKNOWN 阶段使用 |
| L-08 | Java/JDK、artifact 和兼容策略 | **首版敲定 JDK 17**（只用 17 稳定特性，不依赖虚拟线程）；API/core、transport、starter 分层；SemVer。升级 21+ 时执行器可平滑替换，公共 API 不变 |
| L-09 | 本地状态持久化要求 | MVP in-memory，可插拔 StateStore；不承诺进程重启恢复 |
| L-10 | V1 是否包含 Spring starter | 先完成纯 Java MVP；starter 作为独立阶段，不反向污染 API |
| L-11 | 本地工具信任和 sandbox 边界 | MVP 只执行显式注册且进程内可信工具；不支持任意代码 |
| L-12 | 大载荷和敏感数据责任 | bus 只传引用；对象存储授权、TTL、审计另行冻结 |

### 10.2 与 agent-bus / agent-gateway 必须确认

| ID | 决策 | 必须产出 |
|---|---|---|
| B-01 | 对 client 暴露的 public endpoint 与协议版本 | OpenAPI / JSON Schema / SSE fixture |
| B-02 | 阻塞、流式、Get、Cancel、Subscribe、Continue 的 wire shape | 每个 operation 的 request/response/error |
| B-03 | CLIENT_INVOCATION_REQUESTED 到 runtime 的真实实现路径 | 可执行时序与集成测试，不只写 feature 文档 |
| B-04 | accepted、stream-ready、input-required、terminal 是否均有独立事件 | 事件目录、required fields、顺序保证 |
| B-05 | clientInvocationId、messageId、idempotencyKey、taskId 的生成和去重边界 | 字段语义矩阵 |
| B-06 | UNKNOWN 后同键重试的保存窗口与返回行为 | TTL、冲突与错误码 |
| B-07 | streamRef 是否对 client 可见、如何解析 | 建议仅 gateway 内部可见，client 只用 taskId |
| B-08 | SSE reconnect、event id、cursor 和 replay 能力 | 明确是否支持 Last-Event-ID；不支持时写清降级 |
| B-09 | ToolCallEnvelope 的交付通道 | HTTP pull/SSE/WebSocket/Webhook 中选定一种 MVP |
| B-10 | ToolResultEnvelope 的回传和 ACK 点 | ACK 必须表示 runtime/Task control plane 已可靠接收 |
| B-11 | 重复、乱序、延迟和死信语义 | toolCallId 与 callbackDeliveryId 去重规则 |
| B-12 | payloadRef / artifactRef 的解析、授权和过期 | resolver contract 与安全测试 |
| B-13 | route handle 与 registry resolver 的生产接线 | 不再使用 MapEndpointResolver PoC |
| B-14 | tenant 与 credential 传播 | gateway 鉴权后建立可信 tenant，不信任任意 header |
| B-15 | error code 和 retryable 的单一目录 | client/runtime/bus 映射表 |
| B-16 | timeout / retry / capacity SLA | accept、stream、tool、overall deadline 的默认值与上限 |
| B-17 | 工具目录注册、版本、下线和 lease | ToolCatalog contract |
| B-18 | client 是否永远不直接消费 event bus | 建议确认“是”，由 gateway 提供受控表面 |

### 10.3 与首批下游应用必须确认

| ID | 问题 |
|---|---|
| D-01 | 应用是 plain Java、Spring Boot、Android、桌面端还是受限容器 |
| D-02 | 是否允许长连接；代理、NAT、企业网关的 idle timeout 是多少 |
| D-03 | 调用需要 blocking convenience API 还是完全异步 |
| D-04 | 业务能接受哪些 Task 和 stream 恢复语义 |
| D-05 | 工具是否有副作用；如何提供业务 idempotency key |
| D-06 | 工具最大并发、队列长度、P95/P99 时延和 deadline |
| D-07 | 工具取消是否可协作；底层库是否支持 interruption |
| D-08 | 工具输入输出是否包含 PII、密钥、文件或客户数据 |
| D-09 | 谁决定工具授权；用户批准 UI 在哪里 |
| D-10 | SDK 日志、metrics、trace 如何接入其现有系统 |
| D-11 | 进程重启后是否必须恢复未完成工具；若必须，StateStore 用什么 |
| D-12 | 应用升级期间能否并存两版工具 schema |
| D-13 | 工具失败时希望 Agent 重试、换工具、询问用户还是终止 |
| D-14 | 需要支持的网络认证方式：token、mTLS、签名或企业代理 |

## 11. 分阶段实施

### Phase 0：协议与构建基线

工作：

- 修复 agent-client parent POM 版本并确认 JDK 基线。
- 冻结 client domain vocabulary、ID 语义、三套状态机。
- 与 Bus 冻结 invocation 与 tool callback wire contract。
- 为每个 wire message 建立 JSON Schema、示例和 golden fixture。
- 明确当前 ingress-envelope/v1 是迁移、替换还是保留内部用途。

DoD：

- agent-client 可以独立 mvn test。
- Lucio、Bus、Runtime、Client owner 对决策清单有书面结论。
- request/response/event/error fixture 在双方 contract test 中可解析。
- 未决项不以 TODO 隐藏在生产默认行为中。

### Phase 1：纯 Java API 与 Core

工作：

- 实现 public immutable carriers、AgentClient 和 InvocationCall。
- 实现调用、stream、tool-call 状态机。
- 实现 in-memory StateStore、LocalToolRegistry、dedupe 和 bounded executor。
- 实现错误、timeout、retry 和 close/drain。
- 增加 ArchUnit，禁止 public API 泄漏 Spring/Reactor/Jackson/A2A。

DoD：

- 状态机所有合法/非法转换有单元测试。
- UNKNOWN、重复事件、乱序事件、terminal 后事件有测试。
- Flow backpressure 和 buffer overflow 有确定行为。
- 同一 toolCallId 并发重复交付只执行一次。
- API 包的公开签名只出现 JDK 和 agent-client 类型。
- 线程安全测试通过，无无界线程、无 Thread.sleep 等待协议状态。

### Phase 2：Invocation Transport

工作：

- 实现 gateway transport 与 A2A-compatible codec。
- 实现 Agent Card / descriptor 映射。
- 实现 submit、Get、Cancel、Subscribe、Continue。
- 实现 credential、tenant、trace、deadline 传播。
- 实现 SSE lifecycle、重连和资源关闭。

DoD：

- 使用 fake gateway / WireMock 覆盖成功、拒绝、UNKNOWN、accepted 后超时、失败和取消。
- 使用真实 agent-runtime harness 验证标准 A2A codec，但不得绕过正式 gateway contract
  冒充生产拓扑。
- 获得 taskId 后任何 timeout 都不回退为 UNKNOWN。
- subscription close 不触发 CancelTask；CancelTask 必须显式调用。
- 无内部 endpoint、routeHandle、credential 或正文出现在日志。
- 所有 HTTP response、SSE error frame 和未知 enum 都有兼容性测试。

### Phase 3：Local Tool 多轮闭环

工作：

- 实现 ToolCallChannel 的 MVP transport。
- 实现 catalog publish、tool request receive、schema/policy hook、execute、result submit 和 ACK。
- 把 ToolResult 通过正式 ContinueTask / tool-result ingress 推进原 Task。
- 增加执行与交付双状态、result retry 和 dead-letter callback。

DoD：

- 单工具成功闭环。
- 同一 Task 连续两轮不同工具后 completed。
- 工具返回业务错误后 Agent 获得结构化失败。
- 重复 ToolCallEnvelope 不重复执行。
- result ACK 丢失时只重投结果、不重跑工具。
- tool timeout、cancel、queue full、schema invalid、policy denied 均有测试。
- Task cancel 能停止未开始工具，并协作取消运行中工具。
- tenant/toolKey/taskId/toolCallId 交叉错配全部拒绝。
- 工具参数和结果不进入默认日志。

### Phase 4：集成、兼容与交付

工作：

- plain Java quickstart 与 local-tool 示例。
- 可选 Spring Boot starter。
- metrics / trace bridge、debug snapshot。
- API compatibility gate、SBOM、发布说明和迁移指南。
- 故障注入与长稳测试。

DoD：

- 新用户只按最佳实践文档即可完成调用、流式观察、取消和本地工具注册。
- examples 在 CI 中作为可执行验收，不是不可编译片段。
- 公共 API 通过 binary compatibility 检查。
- 连接中断、gateway restart、runtime task missing、重复/乱序消息、慢订阅者和 SDK close
  有故障注入证据。
- 日志能用 clientInvocationId、serverTaskId、toolCallId、correlationId 串起链路，
  且通过敏感数据扫描。
- 发布物明确标记哪些能力是 MVP、experimental 或 deferred。

## 12. 总体验收 DoD

V1 只有同时满足以下条件才可对下游宣布可用：

### 功能

- 标准调用、查询、取消、重订阅和 continue 全部走受治理入口。
- accepted、working、input-required、terminal 投影一致。
- 本地工具至少完成两轮闭环。
- 错误和超时可程序化处理。

### 一致性

- runtime Task 是唯一权威。
- clientInvocationId 不替代 serverTaskId。
- 工具 execution outcome 与 result delivery ACK 分离。
- 所有重试都有稳定 idempotency / dedupe key。

### 可靠性

- 重复、乱序、延迟、连接断开和部分失败均有测试。
- Publisher 有背压和有界内存。
- close/drain 不泄漏连接、线程或 subscription。
- 无声降级和无限重试被禁止。

### 安全

- client 不感知 runtime 内部 endpoint 和 bus 物理拓扑。
- tenant 来源可信且不能由普通 header 冒充。
- credential、prompt、tool arguments、tool result 默认不写日志。
- 大载荷引用有授权、TTL 和审计。

### 可扩展性

- 公共 API 无 Spring、Reactor、Jackson、A2A SDK 泄漏。
- transport、StateStore、CredentialProvider 和 observation 可替换。
- 业务工具只依赖 tool SPI。
- wire schema 有版本和 golden fixtures。

### 开发体验

- plain Java 最佳实践文档和完整示例可运行。
- 测试方法同时覆盖单元、contract、集成、故障注入和日志检查。
- 每种状态和错误都有明确的下游处理建议。
- 当前未实现能力在文档中明确标记，不用“已支持”描述目标态。

## 13. 建议结论

第一版不应从“写一个 HTTP 工具类”开始，也不应直接复制 runtime 或 middleware 类型。
应先冻结四个稳定边界：

1. client 公共 domain API。
2. gateway / bus invocation wire contract。
3. local tool SPI。
4. tool-call / result / Task-resume wire contract。

在这四个边界之下，A2A、HTTP、SSE、Spring 和未来其他 transport 都只是 adapter；
在这四个边界之上，下游应用只面对 AgentClient、InvocationCall、LocalTool 和稳定值对象。
这样才能让 SDK 同时适配不同端侧、不同框架和不同部署形态，并避免把某个客户或某条临时链路
固化成公共平台能力。

## 附录 A：架构愿景对照与关键概念澄清

> 本附录用于把 L1 架构文档（`architecture/L1-High-Level-Design/agent-client/`）描述的
> **完整模块愿景**与当前两项口头诉求要求交付的**MVP 范围**对齐，避免把未落地能力误当作
> 本轮工作项，也避免把 MVP 设计成堵死未来演进的死胡同。本附录是解读辅助，不改变前文
> 的 MVP 边界（§9）。

### A.1 现在 vs 以后：范围对照表

L1 逻辑视图（`logical.md`）覆盖了 agent-client 的完整能力规划。下表标注每一块与当前
两项诉求（标准化调用 API + 本地工具多轮）的关系。

| L1 章节 / 领域对象 | 属于本轮 MVP | 属于后续愿景 | 说明 |
|---|---|---|---|
| ClientInvocation / ClientRequestContext（2.1） | ✅ | | 特性一状态管理的本地句柄核心 |
| Cursor / Progress / Capability correlation 归属（2.2） | ✅ | | 状态投影与多轮关联 |
| LocalCapability：Observation / Action 分类（2.3） | ✅（至少保留 side_effect 声明与差异化治理钩子） | 完整审批/补偿/回滚编排后置 | MVP 必须保留分类字段与 Action 强治理分叉 |
| CapabilityIntent / CapabilityResult（2.4） | ✅ | | 特性二多轮闭环 |
| DebugSession / DebugConfigurationDraft / DebugArtifact（2.5） | | ✅ 开发态调试与产物导出 | 本轮完全不做 |
| sdk-facade / invocation-state / stream-and-turn-loop（3.2–3.4） | ✅ | | 三条主干责任面 |
| capability-and-debug 中的 capability 治理（3.5） | ✅ | debug 部分后置 | 拆开看待 |
| ClientInvocation / Cursor / Capability 状态机（4.1–4.3） | ✅（枚举名可实现级细化，语义与上 wire 的 outcome 必须保留） | | 见 A.3 |
| DebugSession 状态机（4.4） | | ✅ | 本轮不做 |
| 依赖方向隔离（5.x） | ✅（硬约束，ArchUnit/CI 强制） | | 见 A.4 |

一句话：**本轮做"调用主干 + 工具多轮 + 本地状态投影 + 治理骨架"，不做"开发态调试/产物
导出"，但所有 MVP 设计都必须给后续愿景留出 SPI 扩展点，不得堵死。**

### A.2 wire vs 本地：什么能改、什么不能改

判断一个标识符/枚举/字段能不能在实现阶段自由改动，只问一个问题：**它会被序列化发到
网络对端吗（是否"上 wire"）？**

- **上 wire（跨团队契约，改动=破坏性变更，需版本管理）**：A2A 方法名、`taskId`、
  `messageId`、`tool_call_id`、`outcome`（`OK|ERROR|REJECTED|TIMEOUT`）、错误码闭集、
  所有 `ascend.*` 扩展字段（协议文档 §2.3、§7、§8）。
- **不上 wire（SDK 实现细节，可自由改名）**：内部类名、包名、本地状态机枚举名
  （如 §8.3 的 RECEIVED/VALIDATING/…）、变量命名、线程模型。

`CapabilityIntent` 的本地状态（DETECTED/EXECUTING/…）不上 wire，可改名；但
`CapabilityResult.outcome` 上 wire，必须与服务端（`s2c-callback.v1.yaml#outcome_values`）
对齐，不能随意改。

### A.3 三套状态机的定位

L1 的三套状态机（`logical.md` 4.1–4.3）都是**纯客户端本地投影，服务端不感知**。它们
存在的目的不是"给服务端看"，而是让 SDK 内部的调用、流消费、工具执行成为**可测试、
可观测、有不变量**的状态机——从而能对"审批超时""执行成功但回传失败""终态后收到乱序
事件"这类边界写确定性测试。实现时可细化枚举（本文 §8 即 L1 蓝图的实现级细化版），但
必须保留可区分性，尤其是要映射到上 wire 的 `outcome`。

### A.4 依赖方向为何是硬约束

`logical.md` §5 的依赖隔离不是风格建议，是编译期红线：agent-client 的公共 API/core
**禁止 import** agent-runtime / agent-core / agent-middleware 生产代码，仓库已有 ArchUnit
（`EdgeToComputeDirectLinkArchTest`）与 gate 规则自动执行。根因：client 是 edge plane、
可独立分发到客户现场，一旦与服务端 Java 类型耦合，服务端一改就要重新分发所有 SDK。
client 与服务端之间只能通过**线协议**（A2A JSON）通信，不能通过共享 Java 类型。这也是
公共 API 只允许 JDK 类型与 agent-client 自有值对象（§3.2）的架构层原因。

### A.5 治理（governance）的定义与边界作用

治理 = **在能力被使用的路径上，插入平台强制的、横切的检查与记录，使"谁、在什么授权下、
对什么数据、做了什么、留下什么证据"这条链完整可控**。它区分平台能力与业务自定义的方式
可概括为一句话：

> **治理机制（骨架）是平台能力，治理策略（血肉）是业务插件。**

- 平台能力（SDK 内置、不可绕过）：治理**卡点存在本身**。例如"Action 执行前一定调
  PolicyGuard、ApprovalProvider、幂等 claim"这个**流程编排顺序**焊死在 dispatcher 里
  （最佳实践文档 §2 的固定七步）。业务代码拿不到"跳过审批直接执行"的入口。
- 业务自定义（SPI 留空给业务）：卡点里**具体判什么**。PolicyGuard 允许哪些 scope、
  ApprovalProvider 弹什么 UI、CredentialProvider 从哪取 token——都由业务实现。

治理不追求"物理阻止恶意开发者"（那是操作系统沙箱/进程隔离的职责，见 §9.2 明确不做），
而是"让正确路径成为默认且最省事的路径，让偏离行为留下可追溯痕迹"。这正是 Observation/
Action 分类的意义：SDK 无法阻止开发者在 Observation 回调里写副作用，但治理保证他必须
**主动误标**才能绕过 Action 审批，而误标行为在声明与审计中有记录——在企业合规语境下，
清晰的责任边界就是治理的核心产出。

### A.6 标识符关系速查

| 标识符 | 层次 | 生成方 | 上 wire | 一句话 |
|---|---|---|---|---|
| `contextId` | 会话 | runtime | 是 | 一次会话内多个 Task 的分组 |
| `taskId`（=serverTaskId） | 任务 | runtime | 是 | 服务端 Task 权威 ID；拿到后一切操作用它 |
| `messageId`（承担 clientInvocationId 的 UNKNOWN 恢复职责） | 一次请求动作 | client SDK | 是 | A2A 必填；兼创建/续跑幂等键 |
| `tool_call_id`（=correlationId） | 一次工具调用 | runtime | 是 | 工具执行去重键，跨重投稳定 |
| `attempt` | 一次结果重投 | client SDK | 是 | 同 tool_call_id 的回传序号 |
| cursor（SSE `id:`） | 流消费位置 | gateway | 是 | 只表示"client 消费到哪" |
| ClientInvocation 本地句柄 | SDK 内存对象 | client SDK | 否 | 把上面几样绑在一起的本地小账本 |

层次关系：`contextId ⊃ taskId ⊃ tool_call_id ⊃ attempt`。UNKNOWN 恢复（未拿到 taskId
时）靠稳定复用的 `messageId`；拿到 `taskId` 后一切以 `taskId` 为准，`messageId` 退居日志关联。
