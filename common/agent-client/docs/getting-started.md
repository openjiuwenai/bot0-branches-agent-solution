# Agent Client 端侧接入最佳实践（拟议 API）

> **状态：Draft / Proposed API**
>
> 本文用于对齐 `agent-client`、`agent-bus` 和首批端侧应用的开发方式。文中的包名、Java 类型、方法签名和协议字段是一组自洽的**拟议 API**，不是对当前仓库已实现能力的承诺。它们必须经过架构师、Agent Bus 负责人和 SDK 使用方的协议评审，形成版本化 schema、共享 fixture 和兼容策略后，才能固化为公共 API。
>
> 配套文档与术语对齐：
> - 能力拆解与 SDK 设计：`agent-client/docs/proposals/agent-client-v1-design.md`
> - client↔gateway 线协议：现以 L2 `architecture/L2-Low-Level-Design/agent-client/Feat-Func-006-*.md` §3.5 与 `Feat-Func-007-*.md` §3.5 为准，**已对齐 runtime `Feat-Func-009`**（`SendMessage`/`SendStreamingMessage`/`GetTask`；工具目录走 `params.metadata.clientTools`；调用意图走 `_interrupt`；结果走普通 TextPart observation 文本，单 pending、不回传 `toolCallId`）。
> - 事实来源：本模块两大特性以 version-scope `FEAT-006`（标准调用）/ `FEAT-007`（本地工具）为准；本文示例语义须服从二者。
> - **⚠️ 术语迁移**：本文早期示例采用的 `ascend-client-tools/v1` 结构化扩展（`tool_call_id` 回传、结构化结果、outcome 枚举）**已按 009 裁剪**，仅保留为 SDK 客户端内部模型/未来 V1.1 诉求（见 Feat-Func-007 §9）。阅读本文 wire 相关示例时以 L2 §3.5 为准；本文正文的深度对齐为后续单独一轮工作。`taskId` = A2A `message.taskId`；`invocationId` 是 SDK 本地句柄，UNKNOWN 恢复由 A2A `messageId` + gateway 去重承担（见 Feat-Func-006 §8 G-6）。

## 1. 边界与基本原则

- 所有 client -> server 控制请求只发送到 Agent Bus gateway，不直接访问某个 `agent-runtime` 或其 `/a2a` 地址。
- 服务端 Task 是唯一权威生命周期；`ClientInvocation` 只是本地句柄、关联引用和 UI 状态投影。
- cursor 只表示“客户端已消费到哪里”，不表示“服务端执行到哪里”。
- 首版本地工具采用主动多轮模型：client 从查询或服务流收到 `INPUT_REQUIRED`，在本地执行工具，再主动 `resume`；不要求 client 暴露 webhook。
- Observation 是只读环境观测，重点治理数据范围、脱敏、租户和时效；Action 会产生副作用，必须额外治理授权、审批、幂等、证据和补偿。
- 同一 event、`correlationId` 或 resume 请求可能重复到达。重复投递不能造成重复 Action。
- 业务代码只依赖 JDK、SDK 领域类型、`CompletionStage` 和 `Flow`，不暴露 Spring、Reactor、Jackson 或 A2A SDK 类型。

## 2. 拟议公共 API

核心 API 建议保持小而稳定：

```java
public interface AgentClient extends AutoCloseable {
    CompletionStage<InvocationHandle> invoke(InvocationRequest request);

    Flow.Publisher<AgentEvent> events(
            InvocationId invocationId,
            SubscriptionOptions options);

    CompletionStage<TaskSnapshot> get(InvocationId invocationId);

    CompletionStage<Void> cancel(
            InvocationId invocationId,
            CancelRequest request);

    CompletionStage<InvocationHandle> resume(
            InvocationId invocationId,
            ResumeRequest request);

    CompletionStage<InvocationHandle> recover(InvocationId invocationId);

    ToolRegistry tools();

    @Override
    void close();
}
```

本地工具建议使用注册句柄和统一 dispatcher：

```java
public interface ToolRegistry {
    Registration register(LocalTool<?, ?> tool);
    Optional<ToolDescriptor> find(String toolId, String version);
    CompletionStage<ToolResult> execute(ToolCallIntent intent);
}

public interface LocalTool<I, O> {
    ToolDescriptor descriptor();
    Class<I> inputType();
    Class<O> outputType();
    CompletionStage<O> execute(ToolCallContext context, I input);
}

public interface Registration extends AutoCloseable {
    ToolDescriptor descriptor();
    @Override void close();
}
```

公共 dispatcher 应按固定顺序执行：

1. 校验 tenant、deadline、tool id/version 和输入 JSON Schema；
2. 调用 `PolicyGuard`；
3. Action 需要时调用 `ApprovalProvider`；
4. 用 `IdempotencyStore` 原子 claim，重复请求复用旧结果；
5. 执行 handler，并将异常/超时映射为结构化 `ToolResult`；
6. 先写 `ToolResultStore`，再允许调用方提交 resume；
7. resume 成功确认后标记 outbox 已提交。

建议保留以下可替换 SPI：

| SPI | 责任 |
|---|---|
| `CredentialProvider` | 提供短期凭据，不把 token 写入日志或状态文件。 |
| `InvocationStore` | 保存 invocation、taskRef 和本地投影，支持进程恢复。 |
| `CursorStore` | 原子保存最近已成功处理的 event cursor。 |
| `ToolResultStore` | 保存待提交/已提交的工具结果，承担 client-side outbox。 |
| `IdempotencyStore` | 对 tenant/task/correlation/tool 做原子防重。 |
| `PolicyGuard` | 校验租户、scope、数据范围和副作用约束。 |
| `ApprovalProvider` | 承接人工批准、拒绝或参数修改。 |
| `EvidenceSink` | 记录必要证据引用，不上传敏感正文。 |

## 3. 依赖与配置

协议和 API 固化并发布后，业务应用预计只需：

```xml
<dependency>
  <groupId>com.openjiuwen</groupId>
  <artifactId>agent-client</artifactId>
  <version>0.1.0</version>
</dependency>
```

生产配置应满足：

- gateway URL 指向 Agent Bus；
- 凭据来自企业凭据代理、环境或短期 token provider；
- 通过 `AgentClients.Builder.retryPolicy(...)` 配置链路异常后的查询/重订阅间隔、
  最大退避、jitter 和连续失败上限；默认策略为 200/400/800ms、连续失败 3 次熔断；
- invocation、cursor、tool result 和 idempotency 使用持久化 store；
- Action 的最终业务系统也接受幂等键；SDK 本地防重不能替代业务效果幂等；
- 首版 SDK 与示例按 **JDK 17** 基线落地（决策项 L-08 已敲定为 17）。代码只使用 17 稳定特性（record、sealed、instanceof 模式匹配、文本块），不依赖虚拟线程；升级到 JDK 21+ 时可平滑替换执行器实现，公共 API 不变。

## 4. 完整端侧示例

示例完成以下闭环：

- 配置 client 和本地 store；
- 注册一个 Observation 和一个 Action；
- 执行端侧策略、审批和幂等保护；
- 创建调用或在重启后恢复；
- 流式消费内容和 `INPUT_REQUIRED`；
- 执行工具、保存结果并 resume；
- 处理取消、终态和资源关闭。

示例中的包均为拟议 API；应用代码没有 Spring/A2A import。

```java
package com.example.edge;

import com.openjiuwen.client.api.*;
import com.openjiuwen.client.event.*;
import com.openjiuwen.client.model.*;
import com.openjiuwen.client.spi.*;
import com.openjiuwen.client.store.*;
import com.openjiuwen.client.tool.*;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;

public final class EdgeAgentApplication {

    // JDK 17 基线：使用有界平台线程池执行本地工具。若 SDK/宿主升级到 JDK 21+，
    // 可替换为 Executors.newVirtualThreadPerTaskExecutor()，公共 API 不变。
    private static final ExecutorService TOOL_EXECUTOR =
            Executors.newFixedThreadPool(8, runnable -> {
                Thread thread = new Thread(runnable, "agent-client-tool");
                thread.setDaemon(true);
                return thread;
            });

    private EdgeAgentApplication() {
    }

    public static void main(String[] args) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        Path stateDir = Path.of(
                System.getProperty("user.home"), ".example-agent-client");

        PolicyGuard policyGuard = new EdgePolicyGuard(
                "tenant-acme",
                Set.of("customer:read", "ticket:write"));

        try (AgentClient client = AgentClientBuilder.create()
                .gatewayEndpoint(URI.create("https://agent-bus.example.com"))
                .tenantId("tenant-acme")
                .actorRef("employee:10086")
                .credentialProvider(context ->
                        CompletableFuture.completedFuture(
                                requiredEnv("AGENT_BUS_TOKEN")))
                .connectTimeout(Duration.ofSeconds(3))
                .requestTimeout(Duration.ofSeconds(30))
                .streamIdleTimeout(Duration.ofMinutes(2))
                .invocationStore(new FileInvocationStore(
                        stateDir.resolve("invocations")))
                .cursorStore(new FileCursorStore(
                        stateDir.resolve("cursors")))
                .toolResultStore(new FileToolResultStore(
                        stateDir.resolve("tool-results")))
                .idempotencyStore(new FileIdempotencyStore(
                        stateDir.resolve("idempotency")))
                .policyGuard(policyGuard)
                .approvalProvider(new ConsoleApprovalProvider())
                .toolExecutor(TOOL_EXECUTOR)
                .build();

             Registration observation =
                     client.tools().register(customerObservation());
             Registration action =
                     client.tools().register(createTicketAction())) {

            CompletionStage<InvocationHandle> start;
            String recoverId = System.getenv("RECOVER_INVOCATION_ID");

            if (recoverId == null || recoverId.isBlank()) {
                InvocationRequest request = InvocationRequest.builder()
                        .agentId("service-desk-agent")
                        .text("""
                                读取客户 C-100 的基本资料；
                                如发现网络故障，请在审批后创建工单。
                                """)
                        .idempotencyKey(UUID.randomUUID())
                        .deadline(Duration.ofMinutes(10))
                        .attributes(Map.of("channel", "desktop"))
                        .build();
                start = client.invoke(request);
            } else {
                // 从 InvocationStore、CursorStore 和未确认的 tool outbox 恢复。
                start = client.recover(InvocationId.of(recoverId));
            }

            start.thenAccept(handle -> {
                System.out.printf(
                        "started invocationId=%s taskId=%s%n",
                        handle.invocationId(), handle.taskId());

                client.events(
                                handle.invocationId(),
                                SubscriptionOptions.fromSavedCursor())
                        .subscribe(new EventSubscriber(
                                client, handle.invocationId(), done));

                listenForCancel(client, handle.invocationId());
            }).exceptionally(error -> {
                done.completeExceptionally(error);
                return null;
            });

            // 只在 main 边界等待；Flow callback 和工具 handler 不阻塞。
            done.join();
        } finally {
            TOOL_EXECUTOR.shutdown();
        }
    }

    private static LocalTool<CustomerQuery, CustomerView>
            customerObservation() {
        ToolDescriptor descriptor = ToolDescriptor.builder()
                .toolId("customer.profile.read")
                .version("1.0.0")
                .displayName("读取客户基本资料")
                .type(ToolType.OBSERVATION)
                .description("只返回端侧客户目录中的最小必要资料。")
                .inputSchema("""
                        {
                          "type":"object",
                          "required":["customerId"],
                          "properties":{
                            "customerId":{"type":"string","minLength":1}
                          },
                          "additionalProperties":false
                        }
                        """)
                .outputSchema("""
                        {
                          "type":"object",
                          "required":[
                            "customerId","displayName","serviceLevel"
                          ],
                          "properties":{
                            "customerId":{"type":"string"},
                            "displayName":{"type":"string"},
                            "serviceLevel":{"type":"string"}
                          },
                          "additionalProperties":false
                        }
                        """)
                .requiredScopes(Set.of("customer:read"))
                .policy(ToolPolicy.observation()
                        .dataScope("customer.basic")
                        .maskSensitiveFields(true)
                        .build())
                .build();

        return LocalTool.of(
                descriptor,
                CustomerQuery.class,
                CustomerView.class,
                (context, input) -> CompletableFuture.supplyAsync(
                        () -> CustomerDirectory.readSanitized(
                                input.customerId()),
                        TOOL_EXECUTOR));
    }

    private static LocalTool<CreateTicket, TicketCreated>
            createTicketAction() {
        ToolDescriptor descriptor = ToolDescriptor.builder()
                .toolId("ticket.create")
                .version("1.0.0")
                .displayName("创建服务工单")
                .type(ToolType.ACTION)
                .description("创建服务工单，会产生业务副作用。")
                .inputSchema("""
                        {
                          "type":"object",
                          "required":["customerId","summary"],
                          "properties":{
                            "customerId":{"type":"string","minLength":1},
                            "summary":{
                              "type":"string","minLength":1,"maxLength":500
                            }
                          },
                          "additionalProperties":false
                        }
                        """)
                .outputSchema("""
                        {
                          "type":"object",
                          "required":["ticketId","created"],
                          "properties":{
                            "ticketId":{"type":"string"},
                            "created":{"type":"boolean"}
                          },
                          "additionalProperties":false
                        }
                        """)
                .requiredScopes(Set.of("ticket:write"))
                .policy(ToolPolicy.action()
                        .requiresApproval(true)
                        .requiresIdempotency(true)
                        .evidenceRequired(true)
                        .build())
                .build();

        return LocalTool.of(
                descriptor,
                CreateTicket.class,
                TicketCreated.class,
                (context, input) -> CompletableFuture.supplyAsync(
                        () -> TicketService.createOnce(
                                context.idempotencyKey(), input),
                        TOOL_EXECUTOR));
    }

    /**
     * 每次只 request(1)，事件异步处理完后再请求下一条，避免同一个
     * invocation 上出现并发 resume。
     */
    private static final class EventSubscriber
            implements Flow.Subscriber<AgentEvent> {

        private final AgentClient client;
        private final InvocationId invocationId;
        private final CompletableFuture<Void> done;
        private Flow.Subscription subscription;

        private EventSubscriber(
                AgentClient client,
                InvocationId invocationId,
                CompletableFuture<Void> done) {
            this.client = client;
            this.invocationId = invocationId;
            this.done = done;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(AgentEvent event) {
            handle(event).whenComplete((ignored, error) -> {
                if (error != null) {
                    subscription.cancel();
                    done.completeExceptionally(error);
                } else if (!done.isDone()) {
                    subscription.request(1);
                }
            });
        }

        private CompletionStage<Void> handle(AgentEvent event) {
            if (event instanceof ContentDelta delta) {
                System.out.print(delta.text());
                return CompletableFuture.completedFuture(null);
            }
            if (event instanceof StatusChanged changed) {
                System.out.printf(
                        "%nstatus=%s raw=%s%n",
                        changed.projection(), changed.rawStatus());
                return CompletableFuture.completedFuture(null);
            }
            if (event instanceof InputRequired required) {
                return handleInput(required);
            }
            if (event instanceof InvocationCompleted completed) {
                System.out.printf(
                        "%ncompleted taskId=%s artifacts=%d%n",
                        completed.taskId(), completed.artifacts().size());
                done.complete(null);
                return CompletableFuture.completedFuture(null);
            }
            if (event instanceof InvocationFailed failed) {
                done.completeExceptionally(new IllegalStateException(
                        failed.errorCode() + ": " + failed.message()));
                return CompletableFuture.completedFuture(null);
            }

            // 新事件必须前向兼容；保留 type/rawExtensions 后继续消费。
            System.out.printf("%nignored event type=%s%n", event.type());
            return CompletableFuture.completedFuture(null);
        }

        private CompletionStage<Void> handleInput(InputRequired required) {
            if (!(required.intent() instanceof ToolCallIntent intent)) {
                return resume(required, ToolResult.rejected(
                        required.correlationId(),
                        "UNSUPPORTED_INPUT",
                        "This client handles local tools only."));
            }

            /*
             * dispatcher 统一执行 schema、policy、approval、idempotency、
             * timeout 和 result-outbox；业务层不直接调用 Action handler。
             */
            return client.tools().execute(intent)
                    .handle((result, error) -> error == null
                            ? result
                            : ToolResult.error(
                                    intent.correlationId(),
                                    "CLIENT_TOOL_DISPATCH_FAILED",
                                    safeMessage(error)))
                    .thenCompose(result -> resume(required, result));
        }

        private CompletionStage<Void> resume(
                InputRequired required,
                ToolResult result) {
            /*
             * toolResult(...) 从 correlationId 派生并持久化稳定幂等键。
             * 网络重试时不得生成新 key。
             */
            ResumeRequest request = ResumeRequest.toolResult(
                    required.taskId(),
                    required.correlationId(),
                    result);

            return client.resume(invocationId, request)
                    .thenAccept(handle -> System.out.printf(
                            "%nresumed correlationId=%s outcome=%s%n",
                            required.correlationId(), result.outcome()));
        }

        @Override
        public void onError(Throwable error) {
            done.completeExceptionally(error);
        }

        @Override
        public void onComplete() {
            // EOF 不等于成功；没有明确终态时用 get() 补偿。
            if (!done.isDone()) {
                client.get(invocationId).whenComplete((snapshot, error) -> {
                    if (error != null) {
                        done.completeExceptionally(error);
                    } else if (snapshot.status().isTerminal()) {
                        done.complete(null);
                    } else {
                        done.completeExceptionally(
                                new IllegalStateException(
                                        "stream ended before terminal state"));
                    }
                });
            }
        }
    }

    /**
     * 真实端侧应用应由取消按钮调用 cancel。这里用 stdin 演示。
     */
    private static void listenForCancel(
            AgentClient client,
            InvocationId invocationId) {
        Thread cancelThread = new Thread(() -> {
            Scanner scanner = new Scanner(System.in);
            if (scanner.hasNextLine()
                    && "cancel".equalsIgnoreCase(
                            scanner.nextLine().trim())) {
                client.cancel(
                        invocationId,
                        CancelRequest.userRequested("cancelled from edge UI"))
                        .exceptionally(error -> {
                            System.err.println(
                                    "cancel failed: " + safeMessage(error));
                            return null;
                        });
            }
        }, "agent-client-cancel");
        cancelThread.setDaemon(true);
        cancelThread.start();
    }

    private record EdgePolicyGuard(
            String expectedTenant,
            Set<String> grantedScopes) implements PolicyGuard {

        @Override
        public CompletionStage<PolicyDecision> check(
                ToolCallIntent intent,
                ToolDescriptor descriptor,
                ToolCallContext context) {
            if (!expectedTenant.equals(context.tenantId())) {
                return completed(PolicyDecision.deny("TENANT_MISMATCH"));
            }
            if (!grantedScopes.containsAll(descriptor.requiredScopes())) {
                return completed(PolicyDecision.deny("MISSING_SCOPE"));
            }
            if (descriptor.type() == ToolType.ACTION
                    && (intent.idempotencyKey() == null
                    || intent.idempotencyKey().isBlank())) {
                return completed(PolicyDecision.deny(
                        "IDEMPOTENCY_KEY_REQUIRED"));
            }
            if (descriptor.policy().requiresApproval()) {
                return completed(PolicyDecision.requireApproval(
                        "Allow action " + descriptor.displayName() + "?"));
            }
            return completed(PolicyDecision.allow());
        }
    }

    private static final class ConsoleApprovalProvider
            implements ApprovalProvider {

        @Override
        public CompletionStage<ApprovalDecision> requestApproval(
                ToolCallIntent intent,
                ToolDescriptor descriptor,
                String prompt) {
            return CompletableFuture.supplyAsync(() -> {
                System.out.printf(
                        "%nAPPROVAL: %s correlationId=%s [yes/no]%n",
                        prompt, intent.correlationId());
                Scanner scanner = new Scanner(System.in);
                if (scanner.hasNextLine()
                        && "yes".equalsIgnoreCase(
                                scanner.nextLine().trim())) {
                    return ApprovalDecision.approved(
                            "approval:" + intent.correlationId());
                }
                return ApprovalDecision.rejected("USER_REJECTED");
            }, TOOL_EXECUTOR);
        }
    }

    private static final class CustomerDirectory {
        private static CustomerView readSanitized(String customerId) {
            // 手机号、证件号等敏感字段不离开端侧。
            return new CustomerView(
                    customerId, "Acme Customer", "GOLD");
        }
    }

    private static final class TicketService {
        private static final ConcurrentHashMap<String, TicketCreated> RESULTS =
                new ConcurrentHashMap<>();

        private static TicketCreated createOnce(
                String idempotencyKey,
                CreateTicket request) {
            return RESULTS.computeIfAbsent(idempotencyKey, ignored ->
                    new TicketCreated(
                            "INC-" + Math.abs(request.hashCode()), true));
        }
    }

    private static <T> CompletionStage<T> completed(T value) {
        return CompletableFuture.completedFuture(value);
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Missing environment variable: " + name);
        }
        return value;
    }

    private static String safeMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return Optional.ofNullable(root.getMessage())
                .orElse(root.getClass().getSimpleName());
    }

    public record CustomerQuery(String customerId) {
    }

    public record CustomerView(
            String customerId,
            String displayName,
            String serviceLevel) {
    }

    public record CreateTicket(String customerId, String summary) {
    }

    public record TicketCreated(String ticketId, boolean created) {
    }
}
```

### 4.1 示例为何这样处理

- `ToolRegistry.execute` 是治理入口，业务代码不保存并直调 Action handler。
- Observation 仍执行 tenant、scope、schema、脱敏与 deadline 校验。
- Action 在审批和幂等 claim 后执行；业务系统再次用相同幂等键防重。
- 工具结果先写 outbox 后 resume。进程在两步之间崩溃时重发结果，不重做 Action。
- subscriber 每次只消费一条事件，避免同一 invocation 并发 resume。
- 重复 `correlationId` 返回已保存结果。
- 流结束但没有明确终态时执行 `get`，不能把 EOF 当完成。
- `close()` 释放订阅、线程和连接，但不隐式取消服务端 Task。

## 5. 状态与恢复

状态 owner：

| 对象 | owner | client 的责任 |
|---|---|---|
| Task lifecycle | runtime | 通过 get/cancel/resume 观察或请求推进。 |
| Invocation projection | client | UI、重连和本地流程；不是服务端 writer。 |
| Stream cursor | client | 事件处理成功后再提交。 |
| Capability correlation | client | 关联 intent、审批、结果和 resume outbox。 |
| Tool side effect | 业务系统 | 用幂等键或业务唯一约束防重。 |

推荐投影：

```text
NEW -> SUBMITTED -> STREAMING -> COMPLETED
          |             |
          |             +-> WAITING_CAPABILITY -> RESUMING -> STREAMING
          |             +-> CANCEL_REQUESTED -> CANCELED | COMPLETED
          |             +-> FAILED
          +-> REJECTED | DEFERRED
```

必须同时保留 `rawStatus`；未来新增状态映射为 `UNKNOWN`，不能误判为成功。

进程恢复顺序：

1. 从 `InvocationStore` 读取 invocation、tenant、taskRef 和投影；
2. 检查 `ToolResultStore`，先重发尚未确认的 resume；
3. 从 `CursorStore` 读取最后已处理 cursor；
4. 调用 `get` 获取服务端快照；
5. Task 非终态时使用 cursor 重订阅；
6. 服务端不支持 replay 时从当前快照继续，并用 eventId/correlationId 去重。

## 6. 测试矩阵

| 层级 | 必测内容 | 关键断言 |
|---|---|---|
| 单元：模型 | 必填字段、空白 ID、deadline、不可变集合、unknown 状态 | 非法输入在发请求前失败；对象不可被外部修改。 |
| 单元：状态机 | 合法/非法转移、cancel 与 terminal 竞态 | 服务端终态优先；本地投影不写服务端状态。 |
| 单元：registry | 重复 id/version、并发注册/注销、未知版本 | 冲突确定性失败；Registration 关闭后不可解析。 |
| 单元：policy | tenant/scope、审批、脱敏、缺少幂等键 | 未批准 Action 的 handler 调用次数为零。 |
| 单元：幂等 | 重复 intent、resume 重试、进程恢复 | Action 一次；复用同一结果和 resume key。 |
| 单元：超时 | handler/审批超时、deadline 过期 | 形成可提交的 `TIMEOUT`，不静默丢失。 |
| 契约：JSON | create/get/cancel/resume/event/error | 字段、null、枚举、unknown 扩展与共享 fixture 一致。 |
| 契约：SSE | 拆包、心跳、重复/乱序、EOF、错误帧 | 不丢事件；cursor 处理成功后才推进。 |
| 集成：fake bus | accepted/deferred/rejected、401/403/429/5xx、重连 | typed error、retry-after、trace 和幂等键正确。 |
| 集成：real bus | 路由、鉴权、租户隔离 | SDK 只访问 bus；跨租户 taskRef 被拒绝。 |
| E2E | invoke -> tool -> resume -> complete | Task 完成；Action 一次；全链路 correlation 一致。 |
| E2E：崩溃 | Action 成功、resume 未确认时结束进程并恢复 | 不重复副作用；旧 outbox 可使 Task 继续。 |
| 兼容性 | 旧 SDK、旧 fixture、新字段/事件 | 兼容窗口内无 Java API 或 wire 破坏。 |
| DFX | 日志、指标、trace、脱敏 | 可关联定位；无 token 和敏感 payload。 |

### 6.1 单元测试骨架：重复 Action

下面使用拟议的 `agent-client-testkit`；类名可调整，语义必须保留。

```java
import com.openjiuwen.client.api.AgentClient;
import com.openjiuwen.client.testkit.AgentClientTestKit;
import com.openjiuwen.client.tool.LocalTool;
import com.openjiuwen.client.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolIdempotencyTest {

    @Test
    void duplicateIntentExecutesActionOnce() {
        AtomicInteger executions = new AtomicInteger();
        var intent = AgentClientTestKit.actionIntent(
                "task-1", "corr-1", "ticket.create", "1.0.0", "idem-1",
                new EdgeAgentApplication.CreateTicket(
                        "C-100", "network down"));

        LocalTool<EdgeAgentApplication.CreateTicket,
                EdgeAgentApplication.TicketCreated> action =
                AgentClientTestKit.action(
                        "ticket.create",
                        "1.0.0",
                        EdgeAgentApplication.CreateTicket.class,
                        EdgeAgentApplication.TicketCreated.class,
                        (context, input) -> {
                            executions.incrementAndGet();
                            return AgentClientTestKit.completed(
                                    new EdgeAgentApplication.TicketCreated(
                                            "INC-1", true));
                        });

        try (AgentClient client = AgentClientTestKit.clientBuilder()
                .allowAllPolicies()
                .approveAllActions()
                .inMemoryStores()
                .build();
             var registration = client.tools().register(action)) {

            ToolResult first = client.tools().execute(intent)
                    .toCompletableFuture().join();
            ToolResult duplicate = client.tools().execute(intent)
                    .toCompletableFuture().join();

            assertEquals(1, executions.get());
            assertEquals(first, duplicate);
            assertTrue(first.isOk());
        }
    }
}
```

### 6.2 契约测试骨架：共享 fixture

SDK 与 Agent Bus 必须读取同一份版本化 fixture，至少覆盖 create/get/cancel、四种 tool outcome、deferred、未知状态和未来扩展字段。

```java
import com.openjiuwen.client.model.ResumeRequest;
import com.openjiuwen.client.testkit.ContractFixtures;
import com.openjiuwen.client.wire.ClientWireCodec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolResumeWireContractTest {

    private final ClientWireCodec codec = ClientWireCodec.strictV1();

    @Test
    void encodesExactlyLikeSharedBusFixture() {
        ResumeRequest request =
                ContractFixtures.toolResultResumeDomainObject();

        String actual = codec.writeCanonicalJson(request);
        String expected =
                ContractFixtures.read("v1/resume-tool-result-ok.json");

        assertEquals(expected, actual);
    }

    @Test
    void preservesUnknownExtensions() {
        var event = codec.readEvent(
                ContractFixtures.read(
                        "v1/task-event-with-future-field.json"));

        assertEquals(
                "future-value",
                event.extensions().get("future_field"));
    }
}
```

### 6.3 关键 E2E 故障场景

1. Action 已成功写业务系统；
2. `ToolResultStore.save` 后、resume 响应前强制结束 client；
3. 使用同一 invocationId 重启；
4. client 用原结果和原幂等键重发 resume，不再执行 Action；
5. runtime 完成，业务系统只有一条记录。

## 7. Maven 命令

Windows PowerShell：

```powershell
# agent-client 单元测试及所需上游模块
.\mvnw.cmd -pl agent-client -am test

# 指定测试；允许 -am 的其他模块没有同名测试
.\mvnw.cmd -pl agent-client -am -Dtest=ToolIdempotencyTest -Dsurefire.failIfNoSpecifiedTests=false test

# 单元、契约、集成和质量门禁
.\mvnw.cmd -pl agent-client -am verify

# 定位并发问题时临时关闭 JUnit 类级并行
.\mvnw.cmd -pl agent-client -am -DjunitParallel=false test
```

协议 fixture 和 E2E profile 落地后建议增加：

```powershell
.\mvnw.cmd -pl agent-client,agent-bus -am -Dtest=*WireContractTest -Dsurefire.failIfNoSpecifiedTests=false test
.\mvnw.cmd -pl agent-client -am -Pe2e verify
```

`-Pe2e` 和兼容性插件只有在 Maven profile 正式加入后才可使用；不要把拟议命令描述成当前 CI 已具备的能力。

## 8. 日志与指标

关键结构化字段：

| 字段 | 用途 |
|---|---|
| `event` | 稳定事件名，如 `client.tool.completed`。 |
| `tenantId` / `actorRef` | 租户和业务主体引用。 |
| `requestId` / `traceId` / `spanId` | 请求与链路关联。 |
| `invocationId` / `taskId` | client 与 server 调用关联。 |
| `correlationId` | 本地工具多轮关联。 |
| `toolId` / `toolVersion` / `toolType` | 工具身份和分类。 |
| `idempotencyKeyHash` | 只记录哈希/尾部，不记录完整密钥。 |
| `outcome` / `errorCode` | 稳定结果分类。 |
| `attempt` / `retryable` / `elapsedMs` | 重试与耗时。 |

样例：

```json
{
  "timestamp": "2026-07-14T08:21:31.120Z",
  "level": "INFO",
  "event": "client.tool.completed",
  "tenantId": "tenant-acme",
  "traceId": "0123456789abcdef0123456789abcdef",
  "invocationId": "inv-01J...",
  "taskId": "task-01J...",
  "correlationId": "corr-01J...",
  "toolId": "ticket.create",
  "toolVersion": "1.0.0",
  "toolType": "ACTION",
  "idempotencyKeyHash": "sha256:73bf...91ac",
  "outcome": "OK",
  "attempt": 1,
  "elapsedMs": 184
}
```

禁止记录 bearer token、cookie、私钥、完整 prompt、完整工具参数/结果、客户敏感正文、可重放 cursor 或审批凭据。

推荐指标：

- `agent_client_invocation_total{outcome}`
- `agent_client_request_duration_seconds{operation}`
- `agent_client_stream_reconnect_total{reason}`
- `agent_client_tool_execution_total{tool_id,tool_type,outcome}`
- `agent_client_tool_duration_seconds{tool_id}`
- `agent_client_resume_retry_total{error_code}`
- `agent_client_pending_tool_results`

taskId、invocationId 和 correlationId 不得作为 metric label，避免高基数。

## 9. 故障排查

| 现象 | 检查项 | 处理 |
|---|---|---|
| invoke rejected | tenant、credential、agentId、schema、errorCode | 不重试不可重试拒绝；显示稳定错误码。 |
| deferred / 429 | retry-after、deadline、retry budget | 指数退避加抖动，复用原幂等键。 |
| SSE 频繁断开 | gateway、代理 idle timeout、心跳、消费速度 | 从已确认 cursor 重订阅，失败时 get 补偿。 |
| EOF 但 UI 仍 running | terminal event、最后 cursor、task snapshot | EOF 不等于完成，查询服务端。 |
| 重复创建业务记录 | correlation、幂等键、result outbox、下游幂等 | 修复 SDK 与业务系统两层防重。 |
| 找不到工具 | toolId/version、Registration、tenant scope | resume `REJECTED/TOOL_NOT_REGISTERED`，不静默跳过。 |
| Action 未审批就执行 | descriptor policy、guard、approval wiring | 缺少必需审批时禁止 handler。 |
| Action 成功但 Task 不继续 | outbox、correlationId、resume error | 用原结果和原 key 重发 resume。 |
| 重复收到同一 intent | 服务端未确认或事件 replay | 返回旧 ToolResult，不重做 Action。 |
| 工具超时 | intent deadline、队列、handler 和下游超时 | 提交 `TIMEOUT`，由服务端决定后续。 |
| 401 / 403 | token、tenant/scope、系统时钟 | 刷新短期凭据；403 不盲重试。 |
| 状态 UNKNOWN | rawStatus、SDK/协议版本 | 保留 raw 值并升级映射，不当作 completed。 |
| close 后进程不退出 | subscription、executor、审批、pending outbox | 有界关闭并记录 pending；不隐式 cancel Task。 |

## 10. 协议评审清单

公共 API 固化前至少确认：

- Java 最低版本、artifact 拆分和二进制兼容窗口；
- gateway endpoint、鉴权方式和 tenant 来源；
- create/get/cancel/resume 的完整 request/response schema；
- accepted 是否同时返回 taskRef 与 cursor；
- SSE eventId、cursor、replay、heartbeat 与 EOF 语义；
- `INPUT_REQUIRED` tool intent 字段、版本、大小限制和扩展规则；
- `OK/ERROR/REJECTED/TIMEOUT` 的准确语义；
- correlationId 和各操作幂等键的生成方、作用域与保存期限；
- Action 成功但 resume 未确认时的恢复协议；
- cancel 与 resume 并发时的确定性结果；
- deadline、retry-after、退避与最大重试预算；
- unknown enum/event/field 的前向兼容策略；
- 敏感数据、授权引用、审计引用和大对象引用的边界；
- SDK 与 Agent Bus 的共享 fixture、TCK、E2E 环境和发布门禁负责人。

评审通过后，应先把结论写入版本化 schema 和共享 fixture，再让 SDK Java API、Agent Bus handler 和本文共同引用该权威来源，避免三份定义独立漂移。
