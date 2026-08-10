# agent-client

> **状态：Skeleton + Prototype。** Edge Access plane — AgentClient SDK（依据 Layer-0 principle P-I）。
> 团队自有的 AgentClient SDK（HTTP client + Task Cursor consumer + SSE/Webhook receiver）落地处，
> 实现按 ADR-0049 在 W3+ 推进。所有跨 plane 流量必须经 `agent-bus.spi.ingress.IngressGateway`
> 路由（ADR-0089 / Rule R-I.b）。

本仓库的 `agent-client-sdk-for-jvm` 是面向 **JVM 环境**的智能体客户端 SDK。本文档既是模块概览，也是**面向二次开发者的集成手册**——按本文从上到下读完，即可把 SDK 集成到你的应用中。

---

## 目录

- [目录结构](#目录结构)
- [SDK 能做什么 / 不做什么](#sdk-能做什么--不做什么)
- [环境要求](#环境要求)
- [把 SDK 引入到你的工程](#把-sdk-引入到你的工程)
- [30 秒快速开始](#30-秒快速开始)
- [核心概念](#核心概念)
- [完整使用流程](#完整使用流程)
- [本地工具开发指南](#本地工具开发指南)
- [传输与网关对接](#传输与网关对接)
- [错误处理与重试](#错误处理与重试)
- [SPI 扩展点](#spi-扩展点)
- [线程模型与生命周期](#线程模型与生命周期)
- [测试与调试](#测试与调试)
- [最佳实践与常见陷阱](#最佳实践与常见陷阱)
- [故障排查](#故障排查)
- [可运行示例](#可运行示例)
- [文档索引](#文档索引)

---

## 目录结构

```
agent-client/
├── agent-client-sdk-for-jvm/   ★ JVM 版 SDK 本体（api / *.spi / internal / transport）
├── docs/                       设计提案、设备可移植性 FAQ、getting-started 等
├── src/                        skeleton 占位（W3+ 实现落地处）
├── module-metadata.yaml        模块元数据（Rule R-C.b）
└── pom.xml                     skeleton pom（属主 reactor，parent=无（独立可构建 pom））
```

## SDK 本体（JVM 版）

`agent-client-sdk-for-jvm` 是面向 **JVM 环境**的 SDK ：

- 公共 API 与 SPI（`api` / `tool.spi` / `spi` / `state.spi` / `transport.spi`）只用 JDK 类型，不泄漏任何第三方类型。
- 默认传输 `transport.a2a.A2aHttpTransportProvider` 走真实 A2A JSON-RPC 2.0 over HTTP + SSE。
- JDK 17 基线（`<release>17</release>` 锁定 API 与字节码）。

> 命名说明：`-for-jvm` 后缀明确这是面向 JVM 的实现；未来 `for-android` / `for-ios` / `for-harmony`
> 等多端 SDK 尚在建设中，跨端能力由线协议中立性保证，详见
> [`docs/device-portability-and-v1-delivery.md`](docs/device-portability-and-v1-delivery.md)。

SDK 本身的 Maven 坐标：`com.openjiuwen:agent-client-sdk-for-jvm:0.1.0`
（groupId/version 沿用 demo 工程的，待 SDK 正式发布时再迁出 example groupId）。

### SDK 包结构（对齐设计四层）

```
com.openjiuwen.client
├── api/            公共 API：AgentClient / AgentClients / InvocationRequest / ContinueInputRequest
│                   / InvocationCall / InvocationEvent / TaskState / InvocationSnapshot / InvocationMode
│                   / Handle / ErrorCodes / ClassifiedError / InvocationNotResumableException
├── tool.spi/       本地工具 SPI：LocalTool / LocalToolDescriptor / ToolExposurePolicy / ToolView
│                   / ToolInvocation / ToolExecutionRecord / ToolExecutionContext / LocalToolRegistry
├── spi/            治理扩展点：Governance(PolicyGuard / ApprovalProvider)
├── state.spi/      客户端状态存储 SPI：ClientStateStore（幂等去重 / 提交去重）
├── transport.spi/  传输抽象：TransportProvider / ToolWireSpec / CredentialProvider
├── transport.a2a/  ★ 默认传输：A2aHttpTransportProvider（真实 A2A JSON-RPC over HTTP+SSE）+ A2aJsonCodec
└── internal/       内核编排：DefaultAgentClient(invocationRef↔taskRef 映射) / ToolDispatcher
                    / DefaultToolRegistry / InMemoryStateStore / ObservationTextRenderer
```

**依赖红线**：业务代码只应依赖 `api` / `tool.spi` / `spi` / `state.spi` / `transport.spi` 这五个包。`internal` 与 `transport.a2a` 是实现细节，不保证兼容；`transport.a2a.A2aHttpTransportProvider` 是默认实现，可直接 new 出来用，但其内部类型不要在业务代码里传递。

---

## SDK 能做什么 / 不做什么

**能做（SDK 承担）**：

- 发起智能体调用（流式 / 阻塞 / 异步三种模式），并消费标准化事件流。
- 维护客户端侧的调用句柄（`invocationRef`）与服务端 Task（`taskRef`）的映射，业务**无需感知**服务端 taskId。
- 把"用户补充输入"续传给处于等待状态的既有调用（`continueInput`）。
- 注册本地工具（端侧能力），按暴露策略把它们上报给服务端，由远端智能体驱动调用；SDK 自动执行、自动续传结果，业务侧看到的是一条连续事件流。
- 对本地工具做"最多执行一次"的幂等去重、参数校验、策略门禁、审批、超时控制。
- 处理 SSE 中断/重连：中断**不等于**失败，SDK 会主动查询服务端权威状态来恢复，无法确定时给出结构化恢复线索，不悬挂、不伪造终态。
- 把网关的 HTTP 治理错误（401/403/409/429/5xx）归一化为稳定的错误码与可重试判定。

**不做（边界之外）**：

- **不拥有**服务端 Task 的权威状态。所有 `getInvocation` / `completion()` 返回的都是**投影快照**，可能滞后。
- 不做工具沙箱：进程内护栏（有界执行器 + deadline + 异常边界），强隔离属宿主部署能力。
- 不持久化调用状态：默认 `ClientStateStore` 是内存实现，进程重启即丢；需要跨重启恢复请自行实现持久化 store。
- 不替业务系统做副作用幂等：SDK 只保证"同一 `toolCallId` 工具最多执行一次"，但业务下游系统仍需用自己的幂等键防重。
- 不依赖虚拟线程（JDK 17 基线）；升级 21+ 时执行器可平滑替换，公共 API 不变。
- v0730 不支持 `CancelTask` / `SubscribeToTask`（网关北向未开放），SDK 也不暴露 cancel / resubscribe。

---

## 环境要求

| 项 | 要求 |
|----|------|
| JDK | 17+（本机可为更高 JDK，SDK 用 `<release>17</release>` 锁定 API 与字节码） |
| Maven | 3.9+（仅在你需要从源码构建 SDK jar 时才需要；若直接消费已发布 jar 则不需要） |
| 运行时依赖 | Jackson `jackson-databind` 2.17.x（SDK 仅此一个运行时第三方依赖，且只在 `transport.a2a` 内部使用） |
| 网关 | 一个实现了 A2A JSON-RPC 2.0 over HTTP + SSE 北向协议的 Agent Bus gateway（见 [传输与网关对接](#传输与网关对接)） |

业务代码本身**不**需要 Jackson、Spring、Reactor 或任何 A2A SDK 类型——公共 API 只用 JDK 类型（`CompletionStage` / `Flow` / `record` / `sealed`）。

---

## 把 SDK 引入到你的工程

### 方式 A：Maven 依赖（SDK 已发布到仓库时）

```xml
<dependency>
  <groupId>com.openjiuwen</groupId>
  <artifactId>agent-client-sdk-for-jvm</artifactId>
  <version>0.1.0</version>
</dependency>
```

SDK 会传递性地带来 Jackson 依赖。若你的工程已管控依赖版本，可自行显式声明：

```xml
<dependency>
  <groupId>com.openjiuwen</groupId>
  <artifactId>agent-client-sdk-for-jvm</artifactId>
  <version>0.1.0</version>
  <exclusions>
    <exclusion>
      <groupId>com.fasterxml.jackson.core</groupId>
      <artifactId>jackson-databind</artifactId>
    </exclusion>
  </exclusions>
</dependency>
<dependency>
  <groupId>com.fasterxml.jackson.core</groupId>
  <artifactId>jackson-databind</artifactId>
  <version>2.17.3</version>
</dependency>
```

### 方式 B：从源码构建 jar 后手动放入工程

```bash
cd common/example/agent-client-demo
mvn -q -o clean package
# 产物：common/agent-client/agent-client-sdk-for-jvm/target/agent-client-sdk-for-jvm.jar
```

把该 jar 加入你的工程 classpath，并自行补齐 Jackson 三个 jar（`jackson-databind` / `jackson-core` / `jackson-annotations`，2.17.x）。

---

## 30 秒快速开始

最小可运行示例：连一个网关、发起一次流式调用、等到完成、打印输出。

```java
import com.openjiuwen.client.api.AgentClient;
import com.openjiuwen.client.api.AgentClients;
import com.openjiuwen.client.api.InvocationCall;
import com.openjiuwen.client.api.InvocationRequest;
import com.openjiuwen.client.api.InvocationSnapshot;
import com.openjiuwen.client.api.InvocationMode;
import com.openjiuwen.client.transport.a2a.A2aHttpTransportProvider;
import com.openjiuwen.client.transport.spi.CredentialProvider;

public class QuickStart {
    public static void main(String[] args) throws Exception {
        try (AgentClient client = AgentClients.builder()
                .transport(new A2aHttpTransportProvider("http://gateway.example.com"))
                .credentialProvider(CredentialProvider.staticToken("my-bearer-token"))
                .build()) {

            InvocationCall call = client.invoke(InvocationRequest.builder()
                    .conversationId("conv-1")
                    .mode(InvocationMode.STREAMING)
                    .input("hello, agent")
                    .build());

            // 不订阅事件流、直接等终态快照（最简消费方式）
            InvocationSnapshot snap = call.completion().toCompletableFuture().get();
            System.out.println("state = " + snap.state());
            System.out.println("output = " + snap.outputText());
        }
    }
}
```

如果你要看流式增量输出，改用 `call.events().subscribe(...)`，见 [完整使用流程](#完整使用流程)。

---

## 核心概念

### 三个标识符的归属

| 标识符 | 所有者 | 含义 | wire 字段 |
|--------|--------|------|-----------|
| `conversationId` | **业务应用** | 会话上下文生命周期，多次调用可复用 | A2A `message.contextId` |
| `invocationId` / `invocationRef` | **客户端** | 一次调用事务的句柄，后续操作的句柄 | A2A `message.messageId` |
| `taskId` / `taskRef` | **runtime** | 服务端 Task 权威标识 | Task/事件的 `id` / `taskId` |

**关键边界**：业务只见 `invocationRef`，`taskRef` 由 SDK 内部映射、不外泄。`accepted()` 回执里的 `diagnosticTaskRef` 仅用于诊断/日志，**不是**业务操作句柄。

### 三种调用模式

`InvocationMode` 决定 SDK 与网关之间的 A2A 方法与响应形态：

| 模式 | wire 方法 | 业务消费方式 | 状态 |
|------|-----------|--------------|------|
| `STREAMING` | `SendStreamingMessage`（HTTP + SSE） | 订阅 `events()` 增量消费 | **已交付** |
| `BLOCKING` | 严格 unary `SendMessage` + `params.configuration.returnImmediately=false`，不自动调用 `GetTask`；非终态时以 `ProgressUncertain` 结算 | 忽略事件流，直接等 `completion()`；需要持续观察时改用 ASYNC 或显式 `getInvocation` | **client 已交付** |
| `ASYNC` | `SendMessage` + `params.configuration.returnImmediately=true`，要求受理即返回 | 拿到 `accepted()` 即返回，之后用 `getInvocation` 观察 | **client 已交付；gateway 需执行该字段** |

> BLOCKING **不是**"在本地把流式结果聚合"，也不是隐藏的 `GetTask` 轮询；它只消费一次创建 `SendMessage` 响应。`SendMessage` 只表示 unary，真正区分 ASYNC/BLOCKING 返回时机的是 `returnImmediately`。client-tool / 用户输入续跑仍可按协议另发关联原 Task 的 `SendMessage`。

### 事件流（sealed `InvocationEvent`）

所有事件都以 `invocationRef` 归集。事件类型是 sealed interface，可用 `switch` / `instanceof` 穷尽处理：

| 事件 | 含义 |
|------|------|
| `Accepted` | 调用被服务端受理，携带诊断 `diagnosticTaskRef` |
| `StatusChanged` | 状态投影变化，`terminal` 标记是否终态 |
| `ContentDelta` | 增量输出文本（流式） |
| `InputRequired` | 需要客户端输入。`toolCall != null` → 是 client_tool，SDK 自动执行；`toolCall == null` → 需要用户补充输入，业务调 `continueInput` |
| `ProgressUncertain` | 流在非终态下中断、且无法确认真实状态。**不是失败**，附恢复线索 |
| `Completed` | 调用完成（终态） |
| `Failed` | 调用失败（终态），携带稳定 `errorCode` 与 `retryable` |

### 状态投影（`TaskState`）

`TaskState` 是服务端 Task 状态在客户端的**投影**，不是权威状态：

```
SUBMITTED → WORKING → INPUT_REQUIRED → COMPLETED
                   ↘                   → FAILED
                    ↘                  → CANCELED
                     ↘                 → REJECTED
```

`UNKNOWN` 表示本地暂不可判定（映射缺失、连接中断且未取得权威快照）。终态：`COMPLETED` / `FAILED` / `CANCELED` / `REJECTED`。任何操作前应容忍其滞后，并通过 `getInvocation` 校正。

---

## 完整使用流程

### 1. 构造 `AgentClient`

唯一必填项是 `transport`（决定 wire 协议与网关地址）。连接真实网关时还需 `credentialProvider`。

```java
AgentClient client = AgentClients.builder()
        .transport(new A2aHttpTransportProvider("https://agent-bus.example.com"))
        .credentialProvider(CredentialProvider.staticToken("my-token"))
        // 以下均有默认值，按需覆盖：
        // .toolRegistry(...)        // 默认空实现
        // .stateStore(...)          // 默认内存实现
        // .policyGuard(...)         // 默认放行一切
        // .approvalProvider(...)    // 默认自动批准
        // .toolExecutor(...)        // 默认 4 线程守护池
        .build();
```

`AgentClient` 实现 `AutoCloseable`，**用完务必 close**（释放传输连接、线程池、订阅资源）。close 不影响服务端 Task 状态。

### 2. 注册本地工具（可选）

只有想让远端智能体驱动端侧能力时才需要。详见 [本地工具开发指南](#本地工具开发指南)。

```java
client.tools().register(LocalTool.of(
        LocalToolDescriptor.builder("readPage")
                .description("Return the content of the page identified by pageId")
                .sideEffect(LocalToolDescriptor.SideEffect.OBSERVATION)
                .requiredArguments("pageId")
                .inputSchema("{\"type\":\"object\",\"properties\":{\"pageId\":{\"type\":\"string\"}},"
                        + "\"required\":[\"pageId\"]}")
                .build(),
        (invocation, ctx) -> {
            Map<String, Object> result = Map.of(
                    "title", "Mock Page",
                    "pageId", invocation.arguments().get("pageId"));
            return ToolExecutionRecord.ok(invocation.toolCallId(), result);
        }));
```

### 3. 声明工具暴露策略（默认不暴露任何工具）

注册不等于暴露。默认 `ToolExposurePolicy.none()`，服务端看不到任何本地工具。需显式授权：

```java
// 会话级：对该 conversationId 后续所有调用生效
client.exposeInConversation("conv-1",
        ToolExposurePolicy.allow("readPage", "submitOrder"));

// 或调用级覆盖（只能收紧、不能放大会话级授权）
InvocationRequest req = InvocationRequest.builder()
        .conversationId("conv-1")
        .mode(InvocationMode.STREAMING)
        .input("please read the page")
        .exposure(ToolExposurePolicy.allow("readPage"))   // 只暴露 readPage
        .build();
```

暴露策略支持过期窗口（`expiringIn` / `expiringAt`）、白名单（`allow`）、黑名单（`allExcept`）、全开（`all`，谨慎）。两级策略用 `and()` 组合，结果取交集——保证调用级只能收紧。

### 4. 发起调用并消费事件

```java
InvocationCall call = client.invoke(req);

// 方式 A：订阅事件流，增量消费
call.events().subscribe(new Flow.Subscriber<>() {
    private Flow.Subscription sub;
    @Override public void onSubscribe(Flow.Subscription s) { sub = s; s.request(Long.MAX_VALUE); }
    @Override public void onNext(InvocationEvent e) {
        switch (e) {
            case InvocationEvent.ContentDelta d -> System.out.print(d.text());
            case InvocationEvent.InputRequired ir when ir.toolCall() == null ->
                    // 需要用户补充输入 → 调 continueInput（见 5）
                    handleUserInput(call, ir);
            case InvocationEvent.InputRequired ir -> {
                // toolCall != null：client_tool，SDK 会自动执行，业务无需处理
            }
            case InvocationEvent.Completed c -> System.out.println("\n[done] " + c.outputText());
            case InvocationEvent.Failed f -> System.err.println("[failed] " + f.errorCode() + ": " + f.message());
            case InvocationEvent.ProgressUncertain pu -> handleUncertain(call, pu);
            default -> {}
        }
    }
    @Override public void onError(Throwable t) { t.printStackTrace(); }
    @Override public void onComplete() {}
});

// 方式 B：直接等终态快照（不需要增量时）
InvocationSnapshot snap = call.completion().toCompletableFuture().get(30, TimeUnit.SECONDS);
```

> JDK 17 基线：`switch` 类型模式匹配为预览特性；生产代码可用 `instanceof` 模式匹配替代，与 SDK 自身写法一致。

### 5. 用户输入续传（`continueInput`）

当事件流投递 `InputRequired` 且 `toolCall == null` 时，说明服务端需要用户补充输入。用 `continueInput` 续传：

```java
InvocationCall next = client.continueInput(ContinueInputRequest.builder()
        .conversationId(call.conversationId())
        .relatedInvocationRef(call.invocationRef())   // 指向处于 INPUT_REQUIRED 的那次调用
        .mode(InvocationMode.STREAMING)
        .input("Alice")
        .build());

// 续轮由新句柄承载：原句柄止于 INPUT_REQUIRED（非终态），最终结果只出现在新句柄上
InvocationSnapshot snap = next.completion().toCompletableFuture().get();
next.close();
call.close();
```

**关键约束**：
- `relatedInvocationRef` 必须处于 `INPUT_REQUIRED` 且无待处理的 client_tool，否则抛 `InvocationNotResumableException`（稳定错误码 `RELATED_NOT_RESUMABLE`，不可重试）。
- client_tool 类型的等待点由 SDK 自动续跑，**业务不要**插手 `continueInput`。
- 原句柄的 `completion()` 会在 INPUT_REQUIRED 点结算（非终态），避免悬挂。

### 6. 关闭资源

```java
call.close();      // 释放本次调用的订阅资源（不影响服务端 Task）
client.close();    // 释放传输连接与线程池
```

`close()` 幂等，不抛异常。建议用 try-with-resources 包住 `AgentClient`。

---

## 本地工具开发指南

### 工具 SPI 三件套

| 类型 | 职责 |
|------|------|
| `LocalToolDescriptor` | 工具自描述元数据：toolId、副作用分级、JSON Schema、必填参数、超时、是否需审批 |
| `LocalTool` | 工具执行体，`@FunctionalInterface`，返回 `CompletionStage<ToolExecutionRecord>` |
| `LocalToolRegistry` | 注册表，`register` / `unregister` / `find`，注册句柄实现 `AutoCloseable` |

### 一个完整的工具示例

```java
LocalToolDescriptor descriptor = LocalToolDescriptor.builder("submitOrder")
        .displayName("Submit order")
        .description("Submit an order; has side effects and requires approval")
        .sideEffect(LocalToolDescriptor.SideEffect.ACTION)   // 有副作用
        .requiredArguments("orderId")
        .inputSchema("{\"type\":\"object\",\"properties\":{\"orderId\":{\"type\":\"string\"}},"
                + "\"required\":[\"orderId\"]}")
        .timeout(Duration.ofSeconds(10))
        .build();

LocalTool.Registered tool = LocalTool.of(descriptor, (invocation, ctx) -> {
    // ctx.visibleToolNames() —— 本次 invocation 上报给服务端的工具名集合
    // ctx.conversationId() / ctx.invocationRef() / ctx.traceId() / ctx.deadline() —— 上下文
    String orderId = (String) invocation.arguments().get("orderId");
    Map<String, Object> result = Map.of("status", "submitted", "orderId", orderId);
    return ToolExecutionRecord.ok(invocation.toolCallId(), result);
});

// 注册（返回的句柄可 try-with-resources 反注册）
LocalToolRegistry.Registration reg = client.tools().register(tool);
```

### 副作用分级与审批

| `SideEffect` | 含义 | 默认是否需审批 |
|--------------|------|----------------|
| `OBSERVATION` | 只读、无副作用 | 否，直接执行 |
| `ACTION` | 有副作用（写操作等） | 是，需经 `ApprovalProvider` 批准 |

可在 descriptor 上用 `.requiresApproval(false)` 显式覆盖默认。SDK 编排顺序：

```
可见性校验 → 解析工具 → 参数校验 → PolicyGuard.check → (若需) ApprovalProvider → 执行 → 落库
```

任一环节拒绝都产出 `REJECTED` 结果续传给服务端，且工具**不被执行**。

### 工具结果（`ToolExecutionRecord`）

每次工具调用**只产生一个最终结果**（成功或结构化失败），不产生中间态外泄：

```java
ToolExecutionRecord.ok(toolCallId, payload);                    // 成功，payload 是结构化对象
ToolExecutionRecord.okRef(toolCallId, payload, payloadRef);     // 成功 + 大负载引用
ToolExecutionRecord.error(toolCallId, "db_error", "msg");       // 工具内部错误
ToolExecutionRecord.rejected(toolCallId, "permission_denied", "reason"); // 被治理拒绝
ToolExecutionRecord.timeout(toolCallId, "timeout", "msg");      // 超时
```

`payload` 会被 SDK 渲染为服务端可消费的 observation 文本（JSON 字符串），业务无需关心渲染细节。

### 幂等保证

SDK 对同一 `toolCallId` 保证工具**最多执行一次**：
- 通过 `ClientStateStore.saveRecordIfAbsent` 的原子写语义，抵御 `INPUT_REQUIRED` 的重复投递。
- 进程内 in-flight 合流：并发触发的同一次调用只会执行一次。
- 续传提交也有"每 toolCallId 只提交一次"的防抖。

**注意**：SDK 的幂等只覆盖"同一 `toolCallId`"。业务下游系统仍需用自己的幂等键防重（如订单号），因为不同的 `toolCallId` 可能对应同一业务实体。

### 治理 SPI（`Governance`）

```java
// 策略门禁：同步硬门禁，可基于 tenant/scope/dataRange/descriptor 做判定
Governance.PolicyGuard guard = (descriptor, invocation, ctx) -> {
    if (!grantedScopes.containsAll(descriptor.requiredArgumentKeys())) {
        return Governance.Decision.deny("MISSING_SCOPE", "...");
    }
    return Governance.Decision.allow();
};

// 审批提供者：可异步，可对接人工审批
Governance.ApprovalProvider approval = (descriptor, invocation, ctx) ->
        CompletableFuture.supplyAsync(() -> {
            // 弹 UI / 调审批服务 ...
            return Governance.ApprovalDecision.approve();
        }, myExecutor);
```

默认：`PolicyGuard.allowAll()` + `ApprovalProvider.autoApprove()`。生产环境**务必**替换为真实实现，否则 ACTION 工具会无审批直接执行。

---

## 传输与网关对接

### 默认传输 `A2aHttpTransportProvider`

基于 JDK 内置 `HttpClient`，无额外网络框架。构造时传网关 baseUrl，SDK 自动补齐 `/a2a` 后缀：

```java
new A2aHttpTransportProvider("http://gateway.example.com")
// 等价于 endpoint = http://gateway.example.com/a2a

new A2aHttpTransportProvider("http://gateway.example.com/a2a")
// 已含 /a2a，不再追加

// 可选：自定义 ObjectMapper 与 SSE 读空闲超时（默认 120s）
new A2aHttpTransportProvider(url, objectMapper, Duration.ofSeconds(60));
```

### 凭证提供者 `CredentialProvider`

网关对**每个** HTTP 请求强制鉴权（缺失记 `AUTH_MISSING`、非法记 `AUTH_INVALID`，一律 401）。SDK 在创建调用 / 用户输入续传 / 工具结果续传的每一次请求上都会附带 `Authorization: Bearer <token>`。

```java
// 静态 token（开发/测试用）
CredentialProvider.staticToken("my-token");

// 动态 token（生产用：从企业凭据代理获取、按会话/租户区分、支持刷新）
CredentialProvider dynamic = conversationId -> {
    // 可按 conversationId 区分租户；返回 null 表示本次不附带（仅本地假网关用）
    return tokenService.fetch(conversationId);
};
```

`InvocationRequest.credentialToken()` 若显式给出则**优先于** CredentialProvider（便于单次覆盖）。

### 你的网关需要支持的协议

| 项 | 要求 |
|----|------|
| 入口 | `POST /a2a`，JSON-RPC 2.0 |
| 鉴权 | 每个请求必须有 `Authorization: Bearer <非空>`，否则 401 `{"code":"AUTH_MISSING"}` |
| 方法 | `SendStreamingMessage`（SSE）、`SendMessage`（单条 JSON）、`GetTask`（单条 JSON） |
| 方法白名单 | 建议只放行上述三者；其余（`CancelTask` / `SubscribeToTask`）返回 `400 {"code":"VALIDATION_METHOD"}` |
| 状态查询 | `GetTask`，参数是 `params.id`（标准 A2A `TaskQueryParams.id`） |
| 创建调用 | `params.message.taskId` 为空；按 `message.messageId` 幂等去重；读 `params.metadata.clientTools` 获取客户端工具清单 |
| `agentId` | 可选（缺省路由到默认 Agent）；显式给出时不得为空串（否则 400 `VALIDATION_AGENT_ID`）；SDK 会把空白串归一化为 null |
| 请求工具 | `INPUT_REQUIRED` 状态，工具调用意图放在 `result.task.status.message.metadata._interrupt`（非流式）或 `result.statusUpdate.status.message.metadata._interrupt`（流式），含 `_interrupt_kind=client_tool`、`toolCallId`/`toolName`/`arguments` |
| 续传 | 客户端对既有 `taskId` 再发 unary `SendMessage`，正文为一个 text part；`returnImmediately` 由当前 mode 决定。单一 pending 场景不上 wire 回传 `toolCallId`，由 runtime 自动关联；**多并行工具**场景须在 `parts[].metadata.toolCallId` 定向，否则返回 `REMOTE_TOOL_INPUT_TARGET_REQUIRED` |
| 中断即关流 | 投递 `INPUT_REQUIRED` 后关闭当前 SSE 流，等客户端续传后再开下一段。这是**约定行为**，客户端不会当异常 |
| 完成 | `TASK_STATE_COMPLETED`，输出文本放在 `result.artifactUpdate.artifact.parts[].text` 或 `result.statusUpdate.status.message.parts[].text` |

详细的对接调试手册见 [`../example/agent-client-demo/Guidance4GatewayTest.md`](../example/agent-client-demo/Guidance4GatewayTest.md)。

### 标识与 wire 映射速查

| 业务概念 | 所有者 | A2A wire 字段 |
|----------|--------|---------------|
| `conversationId` | 业务应用 | `message.contextId` |
| `invocationId` / `invocationRef` | 客户端 | `message.messageId` |
| `taskId` / `taskRef` | runtime | 非流式 `result.task.id`；流式 `result.statusUpdate.taskId` |
| ToolView | 客户端 | `params.metadata.clientTools[{name,description,inputSchema}]`（`name = toolId`） |
| 工具调用意图 | runtime | `status.message.metadata._interrupt`（`_interrupt_kind=client_tool`/`user_input`） |
| 工具结果续传 | 客户端 | 对既有 `taskId` 的 `SendMessage`，结果渲染为 `TextPart`（`{text}`）；多工具定向时附 `parts[].metadata.toolCallId` |
| 业务附加属性 | 客户端 | `params.metadata.attributes`（字符串键值对，为空时整段省略） |

---

## 错误处理与重试

### 稳定错误码（`ErrorCodes`）

所有错误都归一化为稳定字符串码，**不要**靠异常消息做字符串匹配。错误码闭集：

| 错误码 | 含义 | 可重试 |
|--------|------|--------|
| `AUTH_MISSING` / `AUTH_INVALID` | 缺少/无效凭据 | 否 |
| `PERMISSION_DENIED` | 越权 | 否 |
| `VALIDATION_FAILED` | 请求参数非法 | 否 |
| `ROUTE_NOT_FOUND` | 网关找不到目标 Agent/路由 | 否 |
| `TASK_NOT_FOUND` | Task 不存在 | 否 |
| `METHOD_NOT_SUPPORTED` | 网关未开放该 A2A 方法 | 否 |
| `IDEMPOTENCY_PAYLOAD_MISMATCH` | 同幂等键绑定了不同正文 | **否** |
| `IDEMPOTENCY_IN_FLIGHT` | 同幂等键同正文的前一次请求仍在途 | **是（须用同键）** |
| `RATE_LIMITED` | 被限流 | 是 |
| `SERVICE_UNAVAILABLE` | 服务不可用 | 是 |
| `NETWORK_ERROR` | 网络失败 | 是 |
| `AGENT_ERROR` | 服务端 Task 失败 | 否 |
| `STREAM_INTERRUPTED` | 流在非终态下中断 | — |
| `UNSUPPORTED_MODE` | 未支持的调用模式 | 否 |
| `STREAMING_UNAVAILABLE` | 声明 STREAMING 但链路不提供流式 | 否 |
| `RELATED_NOT_RESUMABLE` | 关联 invocation 不可续接 | 否 |

判断可重试：`ErrorCodes.isRetryable(code)`。

### 异常分类（`ClassifiedError`）

传输层异常实现 `ClassifiedError` 接口，提供 `code()` / `retryable()`。从任意 `Throwable` 解出：

```java
String code = ClassifiedError.codeOf(throwable);       // 无法识别时回退 NETWORK_ERROR
boolean retryable = ClassifiedError.retryableOf(throwable);
```

`InvocationNotResumableException` 是 `continueInput` 本地预检失败时抛出的唯一受检语义异常，它继承 `IllegalStateException` 并实现 `ClassifiedError`（code=`RELATED_NOT_RESUMABLE`，retryable=false）。

### SSE 中断的恢复语义

中断**不等于**失败。SDK 按最后观测状态判别：

| 情形 | SDK 行为 |
|------|----------|
| 已到终态 | 正常结束 |
| 处于 `INPUT_REQUIRED` | 服务端按约定关流，保持通道开放等待续跑，不做任何处置 |
| 其余非终态 | 先用 `GetTask` 主动查询确认真实状态；能确定就据此投影（多数断连由此完全恢复） |
| 查询也无法确定 | 投递 `ProgressUncertain` 事件并正常结算，**不伪造终态也不悬挂** |
| 尚未取得 taskRef（创建未确认） | 以同幂等键、同正文重发创建（最多 3 次），由网关幂等回放取回原 Task，不产生重复 Task |

`ProgressUncertain` 不是失败。`completion()` 返回的快照会附带 `Recovery` 线索，告诉业务下一步：

```java
InvocationSnapshot snap = call.completion().get();
snap.maybeRecovery().ifPresent(r -> {
    switch (r.suggestedAction()) {
        case QUERY_INVOCATION ->
            // Task 已创建但进展未知：稍后调 getInvocation 再次确认。不要重新发起调用！
            client.getInvocation(call.invocationRef());
        case RETRY_CREATE_SAME_KEY ->
            // 创建未被确认：以同一幂等键与逐字节相同的正文重发创建
            client.invoke(InvocationRequest.builder()
                    .conversationId(r.conversationId())
                    .idempotencyKey(r.idempotencyKey())
                    .input(原 input)   // 必须逐字节相同
                    .build());
        case NONE -> {}
    }
});
```

---

## SPI 扩展点

SDK 提供五个可替换的 SPI，默认实现适合开发/测试，生产环境按需替换：

| SPI | 默认实现 | 何时需要替换 |
|-----|----------|--------------|
| `TransportProvider` | `A2aHttpTransportProvider`（真实 HTTP+SSE） | 换自定义协议传输，或测试时指向独立运行的 `mock-gateway` |
| `CredentialProvider` | 无（必填项，无默认） | 接企业凭据代理、按会话/租户区分 token、支持刷新 |
| `LocalToolRegistry` | `DefaultToolRegistry`（内存） | 需要工具元数据持久化或动态发现时 |
| `ClientStateStore` | `InMemoryStateStore`（内存） | 需要跨进程重启恢复时（持久化执行记录 outbox 与提交 ACK） |
| `Governance.PolicyGuard` / `ApprovalProvider` | `allowAll` / `autoApprove` | 生产环境**必须**替换为真实策略与审批实现 |

### 自定义传输示例

`TransportProvider` 是 SDK 内核与"协议/网络"之间的抽象缝。默认实现 `A2aHttpTransportProvider` 走真实 A2A JSON-RPC over HTTP+SSE，测试时把它指向独立运行的 `mock-gateway` 即可；若你的运行环境不用 HTTP（例如直连进程内 bus），实现该接口替换之：

```java
TransportProvider custom = new YourTransportProvider();
AgentClient client = AgentClients.builder()
        .transport(custom)
        .build();
```

### 持久化状态存储示例

```java
ClientStateStore persistent = new MyJdbcStateStore(dataSource);
AgentClient client = AgentClients.builder()
        .transport(new A2aHttpTransportProvider(url))
        .credentialProvider(creds)
        .stateStore(persistent)
        .build();
// 进程崩溃后重启：未确认的工具结果会被重投，不会重做 ACTION 副作用
```

实现 `ClientStateStore` 时需保证：
- `saveRecordIfAbsent` 必须是原子写（利用底层存储的原子语义），否则并发触发的同 `toolCallId` 会执行多次。
- 线程安全。

---

## 线程模型与生命周期

### SDK 内部的线程池

| 线程池 | 用途 | 默认 | 可替换 |
|--------|------|------|--------|
| 工具执行器 | 执行 `LocalTool.execute` | 4 线程守护池（`agent-client-tool-N`） | `Builder.toolExecutor` |
| 传输 IO（A2aHttpTransportProvider 内部） | SSE 读取、HTTP 异步发送 | 无界缓存守护池（`a2a-transport-io`） | 不可直接替换，可换自定义 `TransportProvider` |
| 看护调度器（A2aHttpTransportProvider 内部） | SSE 读空闲看护、创建恢复退避 | 单线程守护（`a2a-transport-watchdog`） | 同上 |

所有线程都是 daemon，不阻塞 JVM 退出。未捕获异常处理器为 best-effort，不打断主流程。

### 事件投递线程

事件通过 `Flow.Publisher` 投递，订阅者的 `onNext` 在传输 IO 线程池上执行。**不要在 `onNext` 里做阻塞操作**——会拖停 SSE 读取。耗时处理请投递到自己的线程池。

### `close()` 的语义

```java
call.close();   // 取消上游订阅、关闭下游 publisher。不影响服务端 Task 状态，幂等，不抛异常
client.close(); // 关闭传输（释放所有 Channel、scheduler、io 池）+ 关闭工具执行器
```

- `close()` **不**隐式取消服务端 Task（v0730 不支持 cancel）。
- `close()` 后再调用 `invoke` 等方法行为未定义，应避免。
- 推荐用 try-with-resources 包住 `AgentClient`，每个 `InvocationCall` 用完也 close。

### 升级到 JDK 21+

公共 API 不变。只需把 `toolExecutor` 换成虚拟线程执行器：

```java
// JDK 21+
AgentClients.builder()
        .transport(...)
        .toolExecutor(Executors.newVirtualThreadPerTaskExecutor())
        .build();
```

---

## 测试与调试

### 端到端验证（mock-gateway + verification-app）

SDK 的可运行示例（mock-gateway + verification-app + Dockerfile + 对接手册）已挪到项目共享示例区：

- 工程根：[`../example/agent-client-demo/`](../example/agent-client-demo/)
- 对接手册：[`../example/agent-client-demo/Guidance4GatewayTest.md`](../example/agent-client-demo/Guidance4GatewayTest.md)

demo 工程是独立的多模块 reactor（父 pom = `agent-client-demo-parent`），可脱离主 reactor 单独构建，会把 `agent-client-sdk-for-jvm` 经相对路径纳入一起编译：

```bash
cd common/example/agent-client-demo
mvn -q -o clean package
# 必须先配 AGENT_GATEWAY_URL 指向一个真实 gateway（可用同仓 mock-gateway 独立起，或你自己的 gateway）
AGENT_GATEWAY_URL=http://127.0.0.1:8080 java -jar verification-app/target/verification-app.jar   # 退出码 0 = 全部断言通过
```

verification-app 经 SDK 对 `AGENT_GATEWAY_URL` 指定的外部 gateway 发起真实 HTTP + SSE 调用，覆盖 13 个场景：

| 场景 | 考察点 |
|------|--------|
| S1 | STREAMING + client 工具多轮（每个工具恰好执行一次，ACTION 触发审批） |
| S2 | BLOCKING 走网关同步接口而非本地聚合 |
| S3 | 用户输入续传（continueInput，新句柄承载续轮） |
| S4 | 普通多轮（复用 conversationId，每轮新 Task） |
| S5 | 默认不暴露（ToolView 为空，服务端不可见任何本地工具） |
| S6 | 治理错误（401）以 FAILED 终态暴露，不伪装成功 |
| S7 | ASYNC 受理后用 getInvocation 观察（GetTask） |
| S8 | 流中断但服务端已完成 → 客户端自查恢复为终态，业务无感 |
| S9 | 流中断且服务端仍在跑 → 投递 ProgressUncertain + 恢复线索，不判失败、不悬挂 |
| S10 | 续接不可续接的 invocation → 返回稳定错误码而非裸异常 |
| S11 | 业务附加属性（trace/correlation）随请求到达网关 |
| S12 | 暴露窗口已关闭 → 不宣告工具，不会被驱动执行 |
| S13 | agentId 透传到网关；空白串在客户端即被归一化 |

跑这些场景是验证你对接是否正确的最快方式。详细构建/运行/Docker 说明见 [demo 的 README](../example/agent-client-demo/README.md)。

---

## 最佳实践与常见陷阱

### 凭证

- **生产环境务必用动态 `CredentialProvider`**，不要硬编码 token。token 来源与刷新由业务实现，SDK 不解释其内容。
- `InvocationRequest.credentialToken()` 用于单次覆盖（如临时提权），优先级高于 CredentialProvider。

### 工具暴露

- **默认不暴露任何工具**。这是安全语义：注册不等于暴露，需通过 `exposeInConversation` 或 `InvocationRequest.exposure` 显式授权。
- 遵循**最小暴露**原则：用 `allow("readPage")` 而非 `all()`。
- 用 `expiringIn` / `expiringAt` 给敏感授权加过期窗口。窗口关闭后服务端再请求端侧工具会被结构化拒绝（`context_expired`），不执行。
- 调用级 exposure **只能收紧**会话级授权，不能放大（两级用 `and()` 取交集）。

### 幂等

- SDK 保证"同一 `toolCallId` 工具最多执行一次"。但**业务下游系统仍需用自己的幂等键防重**——不同的 `toolCallId` 可能对应同一业务实体（如同一订单号被两次提交）。
- `idempotencyKey` 默认等于 `invocationId`。需要自定义时通过 `InvocationRequest.Builder.idempotencyKey` 设置。
- 进展不确定的恢复场景：若 `Recovery.suggestedAction == RETRY_CREATE_SAME_KEY`，**必须**用原幂等键与逐字节相同的正文重发，否则会触发 `IDEMPOTENCY_PAYLOAD_MISMATCH`。

### 事件消费

- **不要在 `onNext` 里做阻塞操作**——会拖停 SSE 读取。耗时处理投递到自己的线程池。
- `onComplete` 不等于成功：上游流结束但未到终态时，SDK 会以最后已知状态兜底完成，但建议用 `getInvocation` 校正。
- client_tool 类型的 `InputRequired` 由 SDK 自动处理，业务**不要**对其调 `continueInput`——那是用户输入续传通道，工具结果续跑走的是 SDK 内部自动路径。

### 资源管理

- `AgentClient` 与 `InvocationCall` 都实现 `AutoCloseable`，用 try-with-resources。
- `close()` 幂等、不抛异常、不影响服务端 Task。
- SDK 内部线程都是 daemon，不阻塞 JVM 退出；但生产环境显式 close 仍是好习惯，便于及时释放连接。

### 不要做的事

- **不要**直接使用 `diagnosticTaskRef`（即服务端 taskId）作为业务操作句柄。所有后续操作用 `invocationRef`。
- **不要**在业务代码里传递 `internal` / `transport.a2a` 包的类型——这些是实现细节，不保证兼容。
- **不要**把 `STREAMING` 的结果在本地聚合成一次性响应来模拟 BLOCKING——后者走的是网关同步接口，语义不同。
- **不要**对 `ProgressUncertain` 当作失败处理——它是"不确定"，附恢复线索，应按 `Recovery.suggestedAction` 处置。
- **不要**对不可重试的错误码无限重试（如 `AUTH_INVALID` / `PERMISSION_DENIED` / `IDEMPOTENCY_PAYLOAD_MISMATCH`）。

---

## 故障排查

| 现象 | 检查项 | 处理 |
|------|--------|------|
| invoke 被拒 | 凭证、agentId、schema、errorCode | 不可重试的错误码不要重试；显示稳定错误码给用户 |
| 401 / 403 | token 是否过期、tenant/scope、系统时钟 | 刷新短期凭据；403 不盲重试 |
| 429 / 503 | retry-after、deadline、retry budget | 指数退避加抖动，复用原幂等键 |
| SSE 频繁断开 | gateway、代理 idle timeout、心跳、消费速度 | SDK 会自动用 GetTask 恢复；若持续断开检查网络中间件 idle 配置 |
| `completion()` 永久悬挂 | 是否订阅了 events 但没消费？是否到 ProgressUncertain？ | 检查事件流；ProgressUncertain 会正常结算；若仍悬挂检查 onNext 是否阻塞 |
| 重复创建业务记录 | 业务下游幂等键、SDK idempotencyKey | 修复业务系统两层防重；SDK 已保证工具最多执行一次 |
| ACTION 工具未审批就执行 | `policyGuard` / `approvalProvider` 是否替换了默认 | 默认 `autoApprove`，生产环境务必替换 |
| 工具执行结果没回到服务端 | 网络中断、续传失败、`resumeGuard` 状态 | 看 `Failed` 事件的 errorCode；SDK 会在网络恢复后重投 |
| `continueInput` 抛 `InvocationNotResumableException` | `relatedInvocationRef` 是否处于 INPUT_REQUIRED？是否在等 client_tool？ | 检查 `relatedCall.lastState`；client_tool 等待点不要用 continueInput |
| 状态 `UNKNOWN` | 映射缺失、连接中断且未取得权威快照 | 调 `getInvocation` 校正；保留 raw 值，不要当 completed |
| close 后进程不退出 | 订阅未取消、自定义 executor 未关闭、审批异步未完成 | 用 try-with-resources；自定义 executor 自行 shutdown |
| agentId 空白串被拒 | 是否传了纯空格 | SDK 会归一化空白为 null；若仍被拒检查是否传了非空白但网关不认识的 agentId |

---

## 可运行示例

SDK 的可运行示例（verification-app + Dockerfile + 对接手册，配合独立运行的 `mock-gateway` 参考网关）已挪到项目共享示例区：

- 工程根：[`../example/agent-client-demo/`](../example/agent-client-demo/)
- 对接手册：[`../example/agent-client-demo/Guidance4GatewayTest.md`](../example/agent-client-demo/Guidance4GatewayTest.md)

demo 工程是独立的多模块 reactor（父 pom = `agent-client-demo-parent`），可脱离主 reactor 单独构建，
会把 `agent-client-sdk-for-jvm` 经相对路径纳入一起编译：

```bash
cd common/example/agent-client-demo
mvn -q -o clean package
# 必须先配 AGENT_GATEWAY_URL 指向一个真实 gateway（可用同仓 mock-gateway 独立起，或你自己的 gateway）
AGENT_GATEWAY_URL=http://127.0.0.1:8080 java -jar verification-app/target/verification-app.jar   # 退出码 0 = 全部断言通过
```

详细构建/运行/Docker 说明见 [demo 的 README](../example/agent-client-demo/README.md)。

---

## 文档索引

- [`docs/proposals/agent-client-v1-design.md`](docs/proposals/agent-client-v1-design.md) — V1 设计提案
- [`docs/device-portability-and-v1-delivery.md`](docs/device-portability-and-v1-delivery.md) — 设备可移植性与 V1 交付形态
- [`docs/getting-started.md`](docs/getting-started.md) — 最佳实践与测试方法（拟议 API，部分示例与当前实现已有差异，以本文及源码 javadoc 为准）
- [`../example/agent-client-demo/README.md`](../example/agent-client-demo/README.md) — demo 工程说明
- [`../example/agent-client-demo/Guidance4GatewayTest.md`](../example/agent-client-demo/Guidance4GatewayTest.md) — gateway 对接调试手册

> 版本与能力对齐：本 SDK 当前对齐 version-scope `FEAT-006`（标准化智能体调用）与 `FEAT-007`（本地工具 SPI），
> wire 对齐 L2 `Feat-Func-006 / Feat-Func-007` 与 runtime `Feat-Func-009`（A2A JSON-RPC 2.0 over HTTP + SSE）。
> 已知边界见 [SDK 能做什么 / 不做什么](#sdk-能做什么--不做什么)。
