# Custom REST 特性

## 1. 定位

Custom REST 为 Servlet 形态的 Agent Runtime 增加一个宿主可定义报文的 HTTP 入口。框架固定处理
HTTP 接收、JSON 校验、A2A RequestHandler 调用、Task 续接、同会话互斥和 SSE 生命周期；宿主只负责
把自己的请求和响应格式与 A2A 对象互相转换。

Maven 模块：

```xml
<dependency>
  <groupId>com.openjiuwen</groupId>
  <artifactId>agent-service-app-custom-rest</artifactId>
  <version>0.1.1</version>
</dependency>
```

该特性不会替代 Runtime 的 `/v1/query` 或 `/a2a`，三个入口可以在同一应用中共存。

## 2. 启用条件

自动装配同时要求：

- 应用类型为 Servlet。
- 类路径存在 Spring MVC `DispatcherServlet` 和 A2A `RequestHandler`。
- 配置 `openjiuwen.service.custom-rest.query-path`。
- 应用提供 `CustomRestProtocolAdapter` Bean。
- Runtime 已提供 A2A `RequestHandler`、`TaskStore` 和 Jackson `ObjectMapper`。

`query-path` 必须是非空绝对路径模式。未配置时不注册 Controller，也不会占用任何路由。

## 3. 处理链路

```text
HTTP POST
  -> CustomRestHandler
  -> 构造 Context(headers, pathVariables, queryParams, body)
  -> CustomRestProtocolAdapter.toA2ARequest
  -> 校验 conversationId、stream 和 readiness
  -> 按 conversationId 解析可恢复 Task 并加互斥 reservation
  -> RequestHandler.onMessageSend 或 onMessageSendStream
  -> CustomRestProtocolAdapter 投影 Task、事件或错误
  -> JSON 或 SSE
```

框架不会解释宿主 Body 中的 `input`、`agent_id` 或 `stream` 等业务字段。是否流式由
`toA2ARequest` 返回的 `A2ASendCommand.stream` 决定，而不是由 Controller 猜测 Body。

## 4. 协议适配 SPI

`CustomRestProtocolAdapter` 定义五个方向明确的方法：

```java
A2ASendCommand toA2ARequest(Context context);
Object fromA2ATask(Task task, Context context);
SseEvent fromA2AStreamEvent(StreamingEventKind event, Context context);
Object fromError(CustomRestError error, Context context);
SseEvent fromStreamError(CustomRestError error, Context context);
```

适配器必须保证：

- `A2ASendCommand` 和 `params` 非空。
- `params.message.contextId` 为非空业务会话 ID。
- blocking 返回对象和 SSE data 可被当前 `ObjectMapper` 序列化。
- 流事件投影不返回 `null`，SSE event 名不包含换行或 NUL。
- 错误投影不抛出新的业务异常；投影无效时框架只能使用通用回退信封。

适配器可以把原始 Body、Header、Query 和 Path Variables 放入 `MessageSendParams.metadata`，使后续
Handler 或出站 Adapter 获取宿主上下文。框架只负责不可变复制，不规定 metadata 键名。

## 5. Task 续接

当消息没有 `taskId` 时，Bridge 使用 `contextId` 和 tenant 查询 TaskStore。

| 当前状态 | 行为 |
|---|---|
| 没有活动正式 Task | 按新消息执行 |
| 唯一正式 Task 为 `INPUT_REQUIRED` | 自动把该 Task ID 写入消息并续接 |
| 正式 Task 为 `SUBMITTED` 或 `WORKING` | 拒绝并返回 `conversation_busy` |
| 正式 Task 为 `AUTH_REQUIRED` | 拒绝普通输入恢复 |
| 多个活动正式 Task | 返回 `conversation_task_ambiguous` |
| 终态 Task | 不参与续接 |
| 已知 `shadow:` Task | 不作为正式父任务 |

同一进程内，同一 `conversationId` 在请求准备到 blocking 完成之间保持 reservation。流式请求在
观察到属于该会话的正式父 Task 后释放 reservation，使后续续轮可以进入；若流尚未产生可观察 Task，
即使客户端断开也会继续拉取事件，直到能够安全释放 reservation，或 Publisher 调用 `onComplete`/
`onError`。

## 6. 流式语义

`stream=true` 时创建无限超时的 `SseEmitter`，但真正的执行超时仍由下层 Runtime 或 Handler 决定。
Subscriber 每次只请求一个事件，完成投影并写出后再请求下一项。

以下事件结束 SSE：

- `TaskStatusUpdateEvent.isFinalOrInterrupted()` 为 true。
- `Task` 状态为 final 或 interrupted。
- Publisher 正常 complete。
- Publisher、投影或序列化失败，写出一个错误事件后结束。

下游断开后，在正式 Task 已可观察时取消订阅；尚不可观察时继续读取，避免会话 reservation 永久占用。

## 7. 错误与安全边界

稳定错误码和 HTTP 状态见[入口与数据契约](../entrypoints-and-contracts.md)。重要边界包括：

- 非空请求 Body 必须是 JSON Object。
- 同会话并发请求不会排队。
- A2A 错误只暴露稳定映射码，不直接返回内部异常文本。
- Adapter 的成功或错误对象不可序列化时使用通用错误，不尝试字符串拼接修复。
- 当前 `ServerCallContext` 使用未认证用户；认证和授权应由入口前置过滤器或宿主协议适配层完成。

## 8. 限制

- 仅支持 Servlet MVC，不注册 WebFlux Controller。
- 一个应用只适合配置一个 `query-path`；自动装配不提供多路由列表。
- 业务请求和响应 Schema 完全由宿主实现，模块本身不能生成 OpenAPI 业务模型。
- 进程内 reservation 不能替代跨副本的会话串行化；多副本部署仍需要粘性路由或外部协调。
- 终态事件会关闭 SSE，但若其 Task 此时还不能从 TaskStore 观察到，当前实现不会释放该会话的
  reservation；终态处理会取消订阅，不能依赖 Publisher 随后再以 `onComplete`/`onError` 补偿释放。
  下层必须保证正式 Task 在终态事件到达前已可查询，否则该进程中的后续同会话请求会持续返回 409。
- 自动续接只识别唯一的 `INPUT_REQUIRED` 正式 Task，不猜测未知或冲突状态。

## 9. 相关文档

- [配置参考](../configuration.md)
- [入口与数据契约](../entrypoints-and-contracts.md)
- [Custom REST 接入指南](../guides/custom-rest-integration.md)
