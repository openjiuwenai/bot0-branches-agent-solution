# Agent Bus Consumer 特性

## 1. 定位

Agent Bus Consumer 使 Agent Runtime 能够订阅并消费发给自身的 Agent Bus 请求事件，也能把
Runtime 间远程调用发送到 Agent Bus，同时复用标准 A2A 服务入口的业务语义。Maven 模块为
`agent-service-bus-consumer`。

Agent Bus SDK 的 runtime role 负责 Broker 连接并提供请求 consumer 和响应 producer；本模块负责
事件校验、A2A 请求桥接、Task 受理、状态投影和响应发布。业务 `AgentHandler` 不感知 Broker、Topic
或订阅过程。

Agent Bus SDK 的 caller role 提供高层请求提交端口 `AgentBusRequestSubmitter` 和响应 consumer；
本模块实现 `RemoteAgentCaller`、RDC 路由、进程内 pending call 和响应回灌。Runtime 不接触
Broker producer、JDBC outbox 或 PostgreSQL。

## 2. 支持的请求

| Agent Bus 请求事件 | A2A 语义 | 处理结果 |
|---|---|---|
| `CLIENT_INVOCATION_REQUESTED` | `SendMessage` 或 `SendStreamingMessage` | 创建或续接 Task，发布 Client 调用响应事件 |
| `A2A_CALL_REQUESTED` | `SendMessage` 或 `SendStreamingMessage` | 创建或续接 Task，发布 A2A 调用响应事件 |
| `CLIENT_INVOCATION_QUERY_REQUESTED` | `GetTask` | 按 `taskId` 查询并发布 `INVOCATION_RESPONSE` |
| `A2A_CALL_QUERY_REQUESTED` | `GetTask` | 按 `taskId` 查询并发布 `A2A_CALL_RESPONSE` |
| `CLIENT_STREAM_SUBSCRIBE_REQUESTED` | 校验 `SubscribeToTask` 的目标 Task | 发布 `INVOCATION_STREAM_READY` |
| `A2A_STREAM_SUBSCRIBE_REQUESTED` | 校验 `SubscribeToTask` 的目标 Task | 发布 `A2A_STREAM_READY` |

创建事件 payload 中的标准方法名决定调用 `SendMessage` 还是 `SendStreamingMessage`。查询与订阅事件
分别只接受 `GetTask` 和 `SubscribeToTask`，不接受没有协议依据的方法别名。当前不处理 CancelTask
请求事件。

## 3. 处理链路

```text
Agent Bus SDK runtimeRequestConsumer
  -> AgentBusBrokerDeliveryPort 订阅并转换消息
  -> BrokerDeliveryLoop 轮询
  -> RuntimeBusEventConsumer 校验、受理和并发控制
  -> RequestHandlerBusA2aBridge
  -> 标准 A2A RequestHandler
  -> AgentHandler / TaskStore
  -> BusTaskProjectionCoordinator
  -> BusResponseRelay
  -> Agent Bus SDK runtimeResponseProducer
```

Runtime 间出站调用链路为：

```text
RemoteInvocationBatchCoordinator
  -> AgentBusRemoteAgentCaller
  -> RuntimeRdcClient
  -> AgentBusRequestSubmitter
  -> Agent Bus SDK 内部 requestProducer
  -> responseConsumer
  -> AgentBusCallerResponseLifecycle
  -> RemoteCallOutcomeMapper
```

处理成功后才向 Agent Bus SDK commit；确定性无效请求会 reject；临时处理失败返回 retry。模块使用
envelope 的 tenant、目标 service ID、schema、deadline 和 payload 约束过滤不应进入本 Runtime 的
消息。

## 4. A2A 语义复用

Bridge 直接调用 HTTP A2A Controller 使用的同一个 `RequestHandler`，不通过本机 HTTP `/a2a`
回环。两条入口因此共享消息发送、流式发送、Task 查询、TaskStore 和业务 `AgentHandler`。

Bridge 会把 envelope 中的 tenant、correlation ID、idempotency key、trace ID、源/目标 service ID
和 deadline 写入 `ServerCallContext`。payload 中显式携带的 tenant 必须与 envelope tenant 一致；
不一致时使用与 Task 不存在相同的外部错误表面。

对于携带已有 `taskId` 的创建请求，Bridge 按 A2A continuation 处理，不把它降级为新建 Task。

## 5. Task 受理与幂等

创建请求按 `tenantId + idempotencyKey` 在当前进程内串行受理：

- 首次请求先保存 RESERVED 记录，再调用 A2A Bridge。
- Task 创建成功后保存真实 `taskId`，转为 ADMITTED。
- 相同幂等键和相同请求正文复用已有 Task。
- 相同幂等键但请求正文不同会发布确定性的拒绝结果。

上游 A2A `RequestHandler` 当前不能接受调用方预留的 Task ID。因此，如果进程在 RESERVED 和
ADMITTED 之间失败，恢复仍受上游 Task ID 注入能力限制。

## 6. 响应与状态投影

模块根据请求来源发布 Client 或 A2A 事件族，主要包括：

- `*_ACCEPTED`：请求已被 Runtime 受理。
- `*_REJECTED`、`*_FAILED`：确定性校验或业务控制面失败。
- `*_RESPONSE`：发送或查询得到的 Task 快照/响应。
- `*_STREAM_READY`：流式 Task 已具备点对点订阅条件。
- `*_INPUT_REQUIRED`：Task 等待调用方继续输入。
- `*_TERMINAL`：Task 已完成、失败、取消或拒绝。

`TaskStoreProjectionPostProcessor` 包装基础 Runtime 的 `TaskStore`，观察 Bus 所属 Task 的持久化状态
变化。投影失败只记录告警，不回滚已经保存的 Task；修复调度器会重新扫描已受理 Task。

响应投影先写入当前进程的 projection store，再由 relay 异步发布。event ID 根据租户、Task、投影
类型和 revision 生成，用于避免同一状态被重复发布。

## 7. 流式边界

Agent Bus 不传输 token chunk、SSE frame、progress stream 或大正文。流式创建只通过总线完成控制面
受理，并发布带 `taskId` 和 `streamRef` 的 `*_STREAM_READY`。

Gateway 随后通过 Runtime 的标准 A2A `SubscribeToTask` HTTP/SSE 入口接收真正的流。Bus 所属 Task
访问该入口时必须提供匹配的 `X-OpenJiuwen-Stream-Ref`；非 Bus Task 保持标准 A2A 订阅行为。

`streamRef` 是随机、不包含 tenant/task 明文的进程内引用，与租户和 Task 绑定，默认有效期为 60
分钟。Runtime 重启后旧引用失效。

## 8. 自动装配

`openjiuwen.service.bus.consumer.enabled=true` 是本模块的全局开关。启用后要求非空、稳定的
`openjiuwen.service.service-id` 和非空 `agent-bus.tenant`。Runtime 应在应用配置中把 Agent Bus SDK
的两个内部 role 开关都引用到该全局开关：

```yaml
openjiuwen:
  service:
    bus:
      consumer:
        enabled: ${AGENT_BUS_ENABLED:false}

agent-bus:
  role:
    runtime:
      enabled: ${openjiuwen.service.bus.consumer.enabled}
    caller:
      enabled: ${openjiuwen.service.bus.consumer.enabled}
```

- 开关开启时，SDK 必须同时提供 `runtimeRequestConsumer`、`runtimeResponseProducer`、
  `AgentBusRequestSubmitter` 和 `responseConsumer`；基础 Runtime 必须提供 A2A `RequestHandler` 和
  `TaskStore`。缺少任一组件时启动失败并指出缺失项。
- 模块装配真实 Bus Caller、内部注册发现客户端和响应生命周期。Caller 通过高层提交端口把请求
  交给 SDK，不依赖 Agent Bus reliability 层。
- Bus 全局开关开启后，`AgentBusRemoteAgentCaller` 是唯一 Runtime 间调用实现，不允许退回 HTTP
  Caller。开关关闭或未配置时不装配 Bus 链路，保留现有纯 HTTP Runtime。

Runtime 侧应配置 `agent-bus.reliability.enabled=false`。`agent-service-bus-consumer` 对
`event-bus-sdk` 传递的 JDBC、Flyway 和 PostgreSQL 依赖做了排除，因此只使用内存业务状态的
Runtime 无需部署或配置数据库。

自动装配使用 `runtime-<service-id>` 作为稳定的 consumer service ID。它标识消费进度和消费端身份，
不替代事件 envelope 的目标 service ID 或 Task ID。

## 9. 当前限制

- Task 受理记录和响应投影记录使用内存实现，不支持跨重启恢复或多副本共享。
- 当前自动装配只读取内联 payload，不解析仅包含 `payloadRef` 的外部正文。
- 上游 A2A 不支持调用方注入预留 Task ID，RESERVED 崩溃恢复尚不能完全闭环。
- 流引用和投影修复状态均属于当前 Runtime 进程。
- 可观测性和生产级持久化属于后续 DFX 能力。
- Runtime Caller 的 `A2A_STREAM_READY` 已可识别，但尚未接入公共 `SubscribeToTask` 客户端，
  因此流式中间 chunk 的点对点 SSE 回灌仍待完成。
- Caller pending call 目前保存在内存中，只保证单实例、单进程生命周期。

## 10. 相关文档

- [Agent Bus Consumer 接入指南](../guides/agent-bus-consumer-integration.md)
- [扩展模块 README](../../README.md)
- [Agent Bus caller/callee 独立 Demo](../../../example/agent-bus-consumer-demo)
