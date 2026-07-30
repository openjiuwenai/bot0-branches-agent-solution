# 扩展入口与数据契约

本文统一说明 Custom REST、Versatile Adapter、AgentScope Adapter、AgentCore-ext 和 Client Tools
涉及的入口方向、参数格式和返回格式。基础 Runtime 会把 REST 或 A2A 请求归一化为
`ServeRequest`，扩展 Handler 不各自创建一套公网协议。

## 1. 入口总览

| 能力 | 方向 | 入口 | 所有者 | 说明 |
|---|---|---|---|---|
| Custom REST | 入站 | `POST ${openjiuwen.service.custom-rest.query-path}` | 扩展模块 | 宿主定义业务报文，框架负责 HTTP、A2A Bridge 和 SSE 传输 |
| Runtime Query | 入站 | `POST /v1/query`、兼容路径 `/query`，可选 WebFlux 路径 `/v1/query/reactive` | 基础 Runtime | 可承载普通 Handler 调用；不提供 Client Tools 的 metadata 协议 |
| Runtime A2A | 入站 | `POST /a2a` 和 `/a2a/` | 基础 Runtime | AgentScope 中断恢复、AgentCore-ext 远端工具和 Client Tools 的主要入口 |
| Versatile HTTP/SSE | 出站 | `POST` 到 `versatile.url-template` | Versatile Adapter | 把 `ServeRequest` 转成远端工作流请求并逐行读取响应 |
| AgentScope Java API | 进程内 | `ReActAgent.call/streamEvents` 或 `HarnessAgent.call/streamEvents` | AgentScope Adapter | 不新增 HTTP 端点 |
| AgentCore Rail | 进程内 | Agent 执行回调 | AgentCore-ext | 远端 A2A 工具和 Client Tools 都通过 Rail 产生 interrupt |

## 2. 内部统一请求与响应

各入口最终交给 `AgentHandler` 的请求包含：

| 字段 | 类型 | 说明 |
|---|---|---|
| `conversationId` | String | 会话标识，也是 AgentScope 的 `sessionId` 和请求级隔离键 |
| `messages` | List<Map> | 消息列表；Handler 通常读取最后一条有效 user 消息 |
| `userId` | String | 用户标识；AgentScope 同时写入 `RuntimeContext.userId` |
| `spaceId` | String | 空间标识 |
| `tenantId` | String | 租户标识 |
| `stream` | boolean | 调用方是否请求流式结果 |
| `metadata` | Map<String,Object> | 协议附加信息；Custom REST、Versatile 和 Client Tools 在这里交换扩展数据 |

非流式 Handler 返回 `QueryResponse(result, conversationId)`。流式 Handler 通过 Observer 输出
`QueryChunk`，常用类型为 `chunk`、`interrupt` 和 `error`。

## 3. Custom REST 入站契约

### 3.1 HTTP 层固定行为

- 仅注册 `POST`。
- 路径由 `openjiuwen.service.custom-rest.query-path` 决定，可使用 Spring `{path_variable}`。
- Header 名称转为小写，重复 Header 保留为 List。
- Path Variable 为单值 Map；Query 参数为多值 Map。
- 空 Body 转为 `{}`。
- 非空 Body 必须声明 `application/json` 或 `application/*+json`，并且 JSON 根节点必须是对象。
- 没有 `Accept` 时视为可接受 SSE；命令要求流式但明确拒绝 SSE 时返回 406。

框架传给协议适配器的上下文为：

```java
record Context(
    Map<String, List<String>> headers,
    Map<String, String> pathVariables,
    Map<String, List<String>> queryParams,
    Map<String, Object> body
) {}
```

### 3.2 宿主 SPI

宿主必须实现五个转换方法：

| 方法 | 输入 | 输出 | 作用 |
|---|---|---|---|
| `toA2ARequest` | `Context` | `A2ASendCommand` | 构造 A2A `MessageSendParams` 并决定是否流式执行 |
| `fromA2ATask` | `Task`, `Context` | 可序列化 Object | 投影非流式成功响应 |
| `fromA2AStreamEvent` | `StreamingEventKind`, `Context` | `SseEvent` | 投影单个 SSE 事件 |
| `fromError` | `CustomRestError`, `Context` | 可序列化 Object | 投影建立流之前的 HTTP 错误 |
| `fromStreamError` | `CustomRestError`, `Context` | `SseEvent` | 投影流建立之后的错误事件 |

`A2ASendCommand.params.message.contextId` 必须非空。`stream=true` 调用 A2A streaming handler，
`stream=false` 调用 blocking handler。业务 Body、成功响应和 SSE data 没有框架固定 Schema，应由宿主
适配器定义并保持版本稳定。

### 3.3 传输结果

非流式成功固定返回 HTTP 200 和 `application/json`，Body 是 `fromA2ATask` 的结果。流式成功固定返回：

```text
HTTP 200
Content-Type: text/event-stream
Cache-Control: no-cache, no-transform
Connection: keep-alive
X-Accel-Buffering: no
```

`SseEvent.event` 为空时只发送 `data`；非空时发送同名 SSE event。event 名不能包含 CR、LF 或 NUL，
data 必须非空、可被 Jackson 序列化，且不能是 Servlet 或 Spring MVC 响应对象。

### 3.4 任务续接与并发

若适配器没有在消息中提供 `taskId`，Bridge 会按 tenant 和 `contextId` 查询 TaskStore：

- 没有活动正式 Task：创建新任务。
- 唯一正式 Task 为 `INPUT_REQUIRED`：自动补充其 `taskId`，形成续轮。
- Task 为 `SUBMITTED` 或 `WORKING`：返回 `conversation_busy`。
- 多个活动正式 Task、未知活动 Task 或不可恢复状态：返回 409。
- `shadow:` Task 不作为正式父任务续接。

同一 `conversationId` 在请求准备阶段有进程内互斥；后到请求返回 409，不进入内部排队。

### 3.5 稳定错误

| HTTP | code | 触发条件 |
|---:|---|---|
| 400 | `invalid_json` | Body 不是合法 JSON |
| 400 | `invalid_custom_request` | JSON 根节点不是对象，或 `A2ASendCommand.params.message.contextId` 缺失 |
| 406 | `stream_not_acceptable` | 适配器要求流式，但请求不接受 SSE |
| 409 | `conversation_busy` | 同会话正在处理，或已有任务处于提交/执行中 |
| 409 | `conversation_task_conflict` | 存在无法识别的活动任务或任务状态 |
| 409 | `conversation_task_ambiguous` | 同会话存在多个活动正式任务 |
| 409 | `conversation_not_resumable` | 当前任务不能用普通输入恢复 |
| 415 | `unsupported_media_type` | 非空 Body 没有 JSON Content-Type |
| 500 | `adapter_execution_failed` | Adapter 返回 null/非法命令、SPI 输出无效或不可序列化，或内部执行/投影失败 |
| 502 | `invalid_a2a_result` | blocking A2A 调用没有返回 Task |
| 503 | `agent_not_ready` | 存在 Readiness Bean 且 Agent 尚未加载 |
| 503 | `task_store_unavailable` | TaskStore 查询失败 |
| A2A 映射值 | `a2a_<code>` | A2A RequestHandler 拒绝请求 |

若宿主错误投影返回 `null` 或不可序列化，HTTP 回退格式为：

```json
{
  "error": {
    "code": "invalid_custom_request",
    "message": "conversationId is required"
  }
}
```

流建立后的错误不会改写 HTTP 状态，而是发送一个错误 SSE 事件后结束。

## 4. Runtime A2A 入站契约

AgentScope、AgentCore-ext 和 Client Tools 复用 Runtime A2A JSON-RPC 入口。普通非流式请求示例：

```json
{
  "jsonrpc": "2.0",
  "id": "request-1",
  "method": "SendMessage",
  "params": {
    "message": {
      "role": "ROLE_USER",
      "messageId": "message-1",
      "contextId": "conversation-1",
      "parts": [
        {"text": "处理这个请求"}
      ]
    }
  }
}
```

流式请求使用 `SendStreamingMessage` 并通过 `text/event-stream` 返回 JSON-RPC 事件。续轮必须使用
上一轮实际返回的 `taskId` 和 `contextId`，不能自行生成。

## 5. AgentScope 契约

### 5.1 请求映射

- `conversationId` 必须非空，映射到 AgentScope `RuntimeContext.sessionId`。
- `userId` 映射到 `RuntimeContext.userId`。
- `tenantId`、`spaceId` 以及 metadata 中的 `traceId`、`requestId` 写入 RuntimeContext。
- 普通调用只把最后一条有效 user 内容映射为本轮 `UserMessage`，不会重复写入完整历史。

### 5.2 正常输出

非流式结果：

```json
{
  "role": "assistant",
  "content": "AgentScope 返回文本"
}
```

流式文本增量：

```json
{
  "type": "answer_delta",
  "content": "增量文本"
}
```

流必须观察到最终结果或已识别的 interrupt 才能正常完成；没有业务终态就结束会输出 error chunk，
并以 Observer error 结束。

### 5.3 中断格式

确认中断：

```json
{
  "type": "__interaction__",
  "index": 0,
  "payload": {
    "kind": "confirmation",
    "items": [
      {"type": "tool_call", "name": "execute_transfer"}
    ]
  },
  "message": "The following operation requires confirmation.",
  "context": {"_interrupt_kind": "ask_user"}
}
```

外部工具结果中断使用 `payload.kind=tool_result`，每个 item 额外包含 `arguments`。当前只支持恰好
一个待恢复的外部工具。普通暂停使用 `payload.kind=message` 且 items 为空。

### 5.4 恢复输入

- `confirmation`：同一 Task 的下一条文本必须为 `APPROVE` 或 `REJECT`，忽略大小写和首尾空白。
- `tool_result`：同一 Task 的下一条非空文本作为唯一 pending 工具的结果。
- `message`：下一轮作为继续信号，正文不转换为普通 AgentScope user query。

客户端不需要也不应回传 AgentScope 内部 tool-call ID、`ToolUseBlock` 或完整状态。

## 6. Versatile 出站契约

### 6.1 请求

URL 由意图专属 `endpoints` 或顶层 `url-template` 选择，并替换 `{conversation_id}`。出站 Body：

```text
body = copy(ServeRequest.metadata.body.custom_data)
inputs = copy(body.inputs)
inputs.query = 当前消息解析出的 query，缺失时使用 lastUserQuery
inputs.intent = 当前消息解析出的 intent（如有）
inputs.intents = 配置候选意图的 JSON 字符串（如有）
inputs.messages = ServeRequest.messages 的 JSON 字符串（如有效）
body.inputs = inputs
```

`ServeRequest.metadata.headers` 仅按白名单透传，随后由 `headers-template` 覆盖。metadata.query
中的值全部转为字符串并进行 URL 编码。

`interrupt.resume-request-template.body` 没有“仅恢复轮次生效”的判断：配置后每次调用都会合并，且同名
顶层键会覆盖普通 Body，`inputs` 不做深度合并。

### 6.2 响应

远端必须返回 2xx。Adapter 按 UTF-8 逐行读取：

- `data:` 前缀会被剥离。
- 空行和常规 SSE 控制行被忽略。
- 普通数据先输出 `TYPE_CHUNK`，结果节点和中断信号用于状态收口。
- `event=exception` 形成错误终态。
- 任意层级出现 `node_type=End` 标记正常完成。
- 未看到完成、结果或完整原生中断时，流结束会产生“需要输入”中断。

未配置 `result-extractions` 时，Legacy 模式从目标结果节点的 QA text 生成：

```json
{"type": "answer", "output": "远端结果"}
```

配置三字段抽取后，Adapter 解析 `response_content`、`intent_id` 和 `agent_id`。非歧义意图会生成
`_interrupt_kind=a2a_delegate` 的内部委派，让 Runtime 根据目标 Agent 注册名继续路由；这不是新的
公网 HTTP 响应类型。

## 7. AgentCore-ext 远端 A2A 工具契约

AgentCore-ext 不提供独立入口。Runtime 完成远端 Agent Card 发现后，在每次执行前为当前 AgentCore
Agent 增量安装工具。远端注册名成为工具名，输入 Schema 固定为：

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

模型调用该工具后，Rail 产生：

```json
{
  "message": "remoteInput 的字符串值",
  "context": {
    "agentName": "远端注册名",
    "_interrupt_kind": "a2a_delegate"
  }
}
```

Runtime 负责真正的远端调用、Task 保存、多成员关联和把结果恢复到 AgentCore。Rail 收到非 `null`
resume input 后不再次调用远端，而是把该值作为工具结果交回 AgentCore；空字符串也会进入恢复分支。

## 8. Client Tools 契约

### 8.1 首轮声明

Client Tools 仅通过 A2A `params.metadata.clientTools` 声明：

```json
{
  "clientTools": [
    {
      "name": "getLocalWeather",
      "description": "读取客户端本地天气",
      "inputSchema": {
        "type": "object",
        "properties": {
          "city": {"type": "string"}
        },
        "required": ["city"]
      }
    }
  ]
}
```

`clientTools` 必须是数组，每项必须是对象且 `name` 非空。`inputSchema` 省略时使用空对象。
同一请求中名称不能重复，也不能与 Agent 已注册的服务端工具或当前模型工具同名。

### 8.2 中断返回

单工具通常返回：

```json
{
  "type": "__interaction__",
  "toolCallId": "call-1",
  "toolName": "getLocalWeather",
  "message": "Client tool invocation required: getLocalWeather",
  "context": {
    "_interrupt_kind": "client_tool",
    "arguments": {"city": "深圳"}
  }
}
```

同一模型轮产生多个工具调用时，`_interrupt.items` 包含多份上述对象。客户端必须使用返回的
`toolName`、`toolCallId` 和 `context.arguments` 执行本地工具。

### 8.3 恢复请求

单 pending 可以用一个不带目标 ID 的 TextPart 恢复：

```json
{
  "role": "ROLE_USER",
  "taskId": "task-1",
  "contextId": "conversation-1",
  "parts": [
    {"text": "客户端工具执行结果"}
  ]
}
```

多 pending 必须逐项定向：

```json
{
  "role": "ROLE_USER",
  "taskId": "task-1",
  "contextId": "conversation-1",
  "parts": [
    {"text": "工具 A 结果", "metadata": {"toolCallId": "call-a"}},
    {"text": "工具 B 结果", "metadata": {"toolCallId": "call-b"}}
  ]
}
```

目标集合必须与所有 pending 调用完全一致，缺失或未知 ID 会拒绝整个续轮。同一 `toolCallId` 可以由
多个 TextPart 连续提供，Runtime 会按出现顺序拼接为一个结果；所有 TextPart 必须都带目标 ID，不能
混合定向与非定向 Part。恢复时无需再次发送 `clientTools`；请求级 Rail 会在 Handler 调用结束时精确
卸载，不影响后续任务。
