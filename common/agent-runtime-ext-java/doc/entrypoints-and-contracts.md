# 入口与数据契约

本文分为两部分：

- 第一部分面向调用 Runtime 的用户，说明每个 HTTP 入口接收什么报文、返回什么报文、字段由谁产生，以及普通调用和远端并发调用时的行为。
- 第二部分面向 Handler 和 Adapter 开发者，说明入口报文如何转换为 `ServeRequest`，以及 Handler 输出如何转换为用户可见响应。

# 第一部分：用户交互契约

## 1. 入口总览

| 入口族 | HTTP 入口 | 请求协议 | 非流式响应 | 流式响应 |
|---|---|---|---|---|
| Query | `POST /v1/query`、`POST /query`、`POST /v1/query/reactive` | Query JSON | Query JSON | SSE；每个 `data` 是 Handler 或编排层数据 |
| A2A JSON-RPC | `POST /a2a`、`POST /a2a/` | A2A JSON-RPC 2.0 | JSON-RPC `result` 或 `error` | SSE；事件名为 `jsonrpc`，`data` 是 JSON-RPC 响应 |
| Custom REST | `POST ${openjiuwen.service.custom-rest.query-path}` | 宿主定义 JSON | 宿主定义 JSON | 宿主定义 SSE event 和 data |

三个 Query 路径使用同一套请求字段、校验规则、非流式响应模型和 SSE data 格式，因此合并说明。它们只有注册条件和传输实现不同。

## 2. Query 入口族

### 2.1 路径与实现差异

| 路径 | Web 栈 | 注册条件 | 对外报文差异 | 内部传输差异 |
|---|---|---|---|---|
| `/v1/query` | Spring MVC | Servlet 应用中注册 | 无 | 流式调用通过异步任务写入 `SseEmitter`；内部 metadata 记录 URL Query 参数和完整 JSON Body |
| `/query` | Spring MVC | `openjiuwen.service.query.legacy-path-enabled=true`，默认 `true` | 无 | 直接复用 `/v1/query` 的全部处理逻辑，metadata 中的 path 为实际请求路径 `/query` |
| `/v1/query/reactive` | Spring WebFlux | `openjiuwen.service.query.webflux.enabled=true`，默认 `false` | 无 | 使用 `Flux` 输出 SSE；内部 metadata 的 Query 参数固定为空，Body 只记录入口重新构造的字段 |

这里的“对外报文无差异”是指调用方提交和收到的业务 Schema 相同，不表示三个 Controller 的线程调度和内部 metadata 完全相同。

### 2.2 请求 Header

请求使用 `Content-Type: application/json`。以下 Header 为可选字段；非空 Header 的值优先于 Body 中的同类字段：

| Header | 覆盖的 Body 字段 | 默认值 | 含义 |
|---|---|---|---|
| `X-User-ID` | `user_id` | `anonymous` | 当前用户标识 |
| `X-Space-ID` | `space_id` | `default` | 当前空间标识 |
| `X-Tenant-ID` | `tenant_id` | 无 | 当前租户标识 |

空白 Header 不覆盖 Body。入口会将除认证、Cookie、API Key 等敏感 Header 以外的 Header 写入内部 metadata，但这些 Header 不会自动出现在用户响应中。

### 2.3 请求 Body

完整示例：

```json
{
  "conversation_id": "conversation-1",
  "messages": [
    {
      "role": "user",
      "content": "查询订单状态"
    }
  ],
  "user_id": "user-1",
  "space_id": "space-1",
  "tenant_id": "tenant-1",
  "stream": false
}
```

代码对象与实际读取链路：

```text
HTTP JSON
  -> Jackson 反序列化为 QueryRequest
  -> QueryIngressSupport.validateAndBuild(...)
  -> ServeRequest.fromQueryRequest(...)
  -> ServeOrchestrator
  -> AgentHandler.query(...) / streamQuery(...)
```

| JSON 位置 | `QueryRequest` 中的对应值 | `ServeRequest` 中的对应值 | Runtime 的实际处理 |
|---|---|---|---|
| `conversation_id` | `conversationId` | `conversationId` | 入口显式校验非空白；用于会话隔离、编排和响应关联 |
| `messages` | `List<Map<String,Object>> messages` | `messages` | 整个列表传入 Handler；没有 `Message` 子 DTO |
| `messages[].role` | 消息 Map 的 `role` | 同一消息 Map 的 `role` | `lastUserQuery()` 查找最后一条 `role=user` 且 `content` 非 null 的消息，比较时忽略大小写 |
| `messages[].content` | 消息 Map 的 `content` | 同一消息 Map 的 `content` | `lastUserQuery()` 将选中值转为字符串，作为常用的本轮文本输入 |
| `messages[].metadata` | 消息 Map 的 `metadata` | 同一消息 Map 的 `metadata` | 作为消息 Map 的一部分序列化保存；发生远端 A2A 调用时，整体序列化到出站 `Message.metadata` |
| `user_id` | `userId` | `userId` | 非空 `X-User-ID` 可覆盖；作为用户上下文传给 Handler，远端调用时还会补入出站参数 metadata 的 `userId` |
| `space_id` | `spaceId` | `spaceId` | 非空 `X-Space-ID` 可覆盖；作为空间上下文传给 Handler |
| `tenant_id` | `tenantId` | `tenantId` | 非空 `X-Tenant-ID` 可覆盖；作为租户上下文传给 Handler |
| `stream` | `boolean stream` | `boolean stream` | Controller 据此选择非流式或 SSE；远端调用继续使用该值选择调用方式 |

`messages[].metadata` 不对应强类型 Java 对象，其子键也不属于 Query 固定协议。Runtime 只保证把整个 Map 作为消息内容的一部分序列化保存，并在发生远端 A2A 调用时整体序列化到出站 `Message.metadata`；本文不列举其内部键名。

单轮简写：

```json
{
  "conversation_id": "conversation-1",
  "message": "查询订单状态",
  "stream": true
}
```

字段规则：

| 字段 | 类型 | 必填 | 默认值 | 处理规则 |
|---|---|---:|---|---|
| `conversation_id` | String | 是 | 无 | 必须存在且不能是空白字符串；作为本次调用的会话标识 |
| `messages` | Array&lt;Map&lt;String,Object&gt;&gt; | 否 | `[]` | 原样转换为内部消息列表；消息没有单独的强类型 DTO |
| `message` | String | 否 | 无 | 仅当 `messages` 为空且本字段非空白时，转换为一条 `{"role":"user","content":...}` 消息 |
| `user_id` | String | 否 | `anonymous` | 可被非空 `X-User-ID` 覆盖；显式 JSON `null` 也回退为默认值 |
| `space_id` | String | 否 | `default` | 可被非空 `X-Space-ID` 覆盖；显式 JSON `null` 也回退为默认值 |
| `tenant_id` | String | 否 | `null` | 可被非空 `X-Tenant-ID` 覆盖 |
| `stream` | Boolean | 否 | `true` | `true` 返回 SSE，`false` 返回一个 JSON 对象 |
| 其他顶层字段 | 任意 | 否 | - | 不进入 `QueryRequest`；MVC 会随完整原始 Body 保存到 `ServeRequest.metadata.body`，WebFlux 不会保留这些未知字段 |

`messages` 和 `message` 同时存在时，非空 `messages` 优先，`message` 被忽略。入口不强制每个消息对象的字段集合；Runtime 只显式读取消息 Map 中的 `role` 和 `content`。消息中的 `metadata` 作为开放 Map 序列化保存，发生远端 A2A 调用时整体序列化透传，本文不定义或列举其内部键名。`ServeRequest.lastUserQuery()` 从最后一条 `role=user` 且 `content` 非 null 的消息中取本轮输入；如果不存在，则回退到最后一条 `content` 非 null 的消息；仍不存在时返回空字符串。

### 2.4 非流式成功响应

当 `stream=false` 时返回 HTTP 200 和 `application/json`：

```json
{
  "result": "处理完成",
  "conversation_id": "conversation-1"
}
```

字段含义和来源：

| 字段 | 含义 | 产生方 | 入口处理 |
|---|---|---|---|
| `result` | 本轮聚合业务结果；内部结构由具体 Handler 定义 | `AgentHandler.query` | 原样序列化，不增删 `result` 内字段 |
| `conversation_id` | 响应所属会话 | `AgentHandler.query` 返回的 `QueryResponse` | 原样序列化；标准 Handler 应返回请求中的 `conversation_id` |

因此 Query 入口固定的只有外层 `result` 和 `conversation_id`。`result` 是 `Object`，其内部字段完全取决于具体 Handler 或 Runtime 明确实现的编排逻辑；本节不为它补充假定字段。

### 2.5 流式成功响应

当 `stream=true` 或省略 `stream` 时返回 HTTP 200：

```text
Content-Type: text/event-stream
Cache-Control: no-cache, no-transform
Connection: keep-alive
X-Accel-Buffering: no
```

典型 SSE：

```text
data: "处理中"

```

内部 Handler 输出 `QueryChunk(type, data)`，但 Query SSE 不把它序列化成 `{"type":...,"data":...}` 外层对象：

| Handler 输出 | 用户看到的 SSE `data` | 字段来源 |
|---|---|---|
| `chunk.data != null` | `chunk.data` 的 JSON | 全部由 Handler 或远端编排层产生并透传 |
| `chunk.data == null` | `{"type":"<chunk.type>"}` | `type` 来自 Handler 的 `QueryChunk.type`，外层对象由入口补齐 |

例如 Handler 输出：

```java
new QueryChunk(QueryChunk.TYPE_CHUNK, "处理中")
```

用户收到：

```text
data: "处理中"

```

SSE 没有固定 event 名、序号或结束对象。Handler 正常调用 `onComplete` 后连接结束；连接结束本身表示本次流完成。

### 2.6 中断与恢复

非流式中断仍使用正常的 Query 外层。以下示例限定为一个远端成员进入 `INPUT_REQUIRED`；字段均由当前 AgentCore Handler 和远端批次协调器明确构造：

```json
{
  "result": {
    "role": "assistant",
    "content": "Remote agent requires input",
    "_interrupt": {
      "message": "Remote agent requires input",
      "items": [
        {
          "toolCallId": "call-1",
          "toolName": "remote-agent-tool",
          "message": "Remote agent requires input"
        }
      ]
    }
  },
  "conversation_id": "conversation-1"
}
```

`result.role` 来自发起委派的 Handler；`result.content`、`result._interrupt.message` 和 `result._interrupt.items` 由 `A2AEnabledServeOrchestrator.queryBatchResolution` 与 `RemoteInvocationBatchCoordinator.publicInterrupt` 构造。`items[].toolCallId`、`items[].toolName` 和 `items[].message` 都是 Runtime 明确定义的字段；多个远端成员等待输入时，`items` 按成员逐项返回。其他 Handler 或 Adapter 产生的中断可使用各自定义的 `result` 内部结构；AgentScope 的结构见后文，Client Tools 则通过 A2A 的 INPUT_REQUIRED 响应返回。

流式中断直接把 `QueryChunk.data` 作为一条 SSE data 返回。Query Controller 不为中断数据补字段。

Query 恢复继续使用相同 `conversation_id`，将用户答复放在新一轮 `message` 或 `messages` 中。Query 请求没有 `taskId` 字段；多个并行远端工具分别等待输入时，应改用 A2A 的 `taskId + parts[].metadata.toolCallId` 定向恢复。

### 2.7 错误响应

请求校验失败：

```http
HTTP/1.1 400 Bad Request
Content-Type: application/json
```

```json
{
  "type": "error",
  "error": "conversation_id is required"
}
```

服务未就绪：

| HTTP | Body | 条件 |
|---:|---|---|
| 503 | `{"type":"error","error":"agent not loaded"}` | 存在 Readiness 且 Agent 尚未加载 |
| 503 | `{"type":"error","error":"no agent handler configured"}` | 没有可用的 `ServeOrchestrator` |

非流式执行失败：

```http
HTTP/1.1 500 Internal Server Error
Content-Type: application/json
```

```json
{
  "type": "error",
  "code": "AGENT_EXECUTION_FAILED",
  "error": "agent execution failed",
  "conversation_id": "conversation-1"
}
```

流已经建立后，HTTP 状态不能再改写。默认编排层会先尝试输出一个 Handler 风格的错误数据：

```text
data: {"type":"error","error":"具体错误信息"}

```

随后以传输错误结束。调用方必须同时处理 SSE 中的 `type=error` 数据和连接异常，不能只依赖 HTTP 状态。

### 2.8 非并发、并发与远端调用

| 场景 | 行为 |
|---|---|
| 单请求、无远端调用 | 入口只调用一次 `query` 或 `streamQuery`；结果按 2.4 或 2.5 返回 |
| 多请求、不同 `conversation_id`、无远端调用 | MVC 和 WebFlux 都允许并发进入；每个请求使用独立 `ServeRequest`。会话隔离和 Handler 内部状态安全由具体 Handler 保证 |
| 多请求、相同 `conversation_id`、无远端调用 | Query 入口本身不加同会话互斥锁，也不返回固定的 busy 错误；是否串行、拒绝或并行由 Handler 及其 Session 实现决定。AgentScope Handler 会拒绝同一会话已有执行时的重入 |
| 单个远端委派 | Handler 产生 `a2a_delegate` 中断后，A2A 编排层调用一个远端 Agent；完成后按中断的 `resume` 语义恢复父 Handler 或直接形成结果 |
| 一轮产生多个远端委派 | 同一批成员并发 fan-out，全部收敛后按原工具调用顺序 fan-in；流式远端业务输出可在完成前逐条返回，因此不同成员的中间输出可能交错 |
| 超过远端并发上限 | 全局最多同时运行 `max-concurrency` 个远端成员，其余进入 FIFO 队列；队列满或等待超时的成员以 `REMOTE_OVERLOADED` 失败 |

以下示例限定为 `resume=false`、一个远端 Agent 只产生一个普通文本 Part。Query 入口先返回带来源信息的远端原始输出，再返回批次聚合终值，随后关闭连接；完整业务事件序列为：

```text
data: {"content":"【远端 Agent 原始输出】","projection":{"kind":"remote_agent_output","batchId":"batch-1","toolCallId":"call-1","target":"remote-agent"}}

data: "【从远端 Task artifacts 提取并聚合的业务终值】"

```

第一条事件的 `content` 和 `projection` 由 `RemoteInvocationBatchCoordinator.forwardRemoteOutput` 明确定义：

| 字段路径 | 来源和含义 |
|---|---|
| `content` | 远端 Agent artifact Part 的原始 `text` 或 `data`；上例已在值中标出 |
| `projection` | Runtime 生成的来源信息，不是远端 Agent 原始输出的一部分 |
| `projection.kind` | 固定为 `remote_agent_output` |
| `projection.batchId` | Runtime 远端调用批次 ID |
| `projection.toolCallId` | 触发该远端调用的工具调用 ID |
| `projection.target` | 目标 Agent 注册名；注册名为空时使用工具名 |

第一条事件的 `content` 是远端流式 Artifact Part 的原始 `text` 或 `data`。第二条事件由 `A2AEnabledServeOrchestrator.streamBatchResolution` 明确产生；单成员、单个普通 TextPart 时它通常与远端最终文本相同，多个 Part 或成员时则是聚合值。如果 `resume=true`，远端原始输出之后会恢复父 Handler，后续业务事件由父 Handler 产生，不能预设固定结构。

如果该委派配置为不恢复父 Handler，远端调用完成后，非流式 Query 的完整响应如下。该示例限定为一个远端 Agent 的 Task 只包含一个普通 TextPart：

```json
{
  "result": {
    "role": "assistant",
    "content": "【远端 Agent 原始 TextPart.text】"
  },
  "conversation_id": "conversation-1"
}
```

这里的 `result.role` 和 `result.content` 由 `A2AEnabledServeOrchestrator.queryBatchResolution` 明确构造。在上述单成员、单个普通 TextPart 场景中，远端原始 `TextPart.text` 位于 `result.content`，示例已在该值中标出。多个 Part、DataPart 或多个远端成员会先提取并按成员顺序聚合，因此此时 `result.content` 是聚合业务结果，不是远端 JSON-RPC 外层或单个 Part 的逐字节副本。如果委派要求恢复父 Handler，远端结果先写入 Runtime 定义的内部 `runtime.remoteToolResults`，最终用户响应由恢复后的父 Handler 产生，不能预设其 `result` 内部字段。

远端调用限制由 `openjiuwen.service.a2a.remote-invocation.*` 控制，默认最大并发为 16、队列容量为 256、排队超时为 30 秒。这些限制作用于 Runtime 内所有远端调用批次，不改变 Query 的请求和响应 Schema。

## 3. A2A JSON-RPC 入口

### 3.1 公共请求外层

`POST /a2a` 和 `POST /a2a/` 行为相同。请求使用 JSON-RPC 2.0：

```json
{
  "jsonrpc": "2.0",
  "id": "request-1",
  "method": "GetTask",
  "params": {
    "id": "task-1"
  }
}
```

| 字段 | 类型 | 必填 | 含义 |
|---|---|---:|---|
| `jsonrpc` | String | 是 | 固定为 `2.0` |
| `id` | String、Number 或 null | 否 | 调用方关联请求和响应的 ID；响应原样带回 |
| `method` | String | 是 | `SendMessage`、`SendStreamingMessage`、`GetTask` 或 `SubscribeToTask` |
| `params` | Object | 由方法决定 | 方法参数 |

所有 JSON-RPC 协议错误通常返回 HTTP 200；成功或失败由 Body 中的 `result` 或 `error` 判断。

### 3.2 SendMessage 与 SendStreamingMessage 请求

两种发送方法使用同一 `params` Schema，区别是前者返回一个 JSON-RPC 响应，后者返回 SSE：

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
        {
          "text": "处理这个请求"
        }
      ]
    }
  }
}
```

| 字段 | 必填 | 规则和含义 |
|---|---:|---|
| `params` | 是 | 必须是对象 |
| `params.message` | 是 | 必须是对象 |
| `params.message.parts` | 是 | 必须是非空数组，并至少包含一条非空白 TextPart；当前入口只把文本 Part 转给 Handler |
| `params.message.parts[].text` | 至少一个 | 必须为 String；所有有效 TextPart 按顺序直接拼接为本轮 Handler 文本，不自动插入分隔符 |
| `params.message.parts[].metadata` | 否 | 必须是对象；Client Tools 多结果恢复时可携带 `toolCallId` |
| `params.message.role` | 否 | 默认 `ROLE_USER`；非空值由当前 A2A SDK 的 `Message.Role` 解析 |
| `params.message.contextId` | 建议首轮提供 | 会话标识；续轮必须复用 Task 的 `contextId` |
| `params.message.taskId` | 首轮否，续轮是 | 指定要继续的 Task；续轮必须使用上一轮实际返回值 |
| `params.message.messageId` | 否 | A2A 消息标识 |
| `params.message.metadata` | 否 | 消息级 Map；内部键没有预定义 Schema，整体序列化并随本轮消息进入 Handler |
| `params.metadata` | 否 | 请求级 Map；整体序列化并进入 `ServeRequest.metadata`；Runtime 明确定义的扩展字段会从这里读取 |
| `params.configuration` | 否 | 可包含 `historyLength`、`returnImmediately` 和 `taskPushNotificationConfig` |
| `params.pushNotificationConfig` | 否 | 推送配置简写；提供后按立即返回处理，callback URL 必须是绝对 HTTP(S) URL |

`X-User-ID`、`X-Space-ID`、`X-Tenant-ID` 分别映射为内部用户、空间和租户字段；未提供时用户和空间默认是 `anonymous`、`default`。

### 3.3 SendMessage 响应

`SendMessage` 返回 HTTP 200、`application/json`。典型完成响应：

```json
{
  "jsonrpc": "2.0",
  "id": "request-1",
  "result": {
    "task": {
      "id": "task-1",
      "contextId": "conversation-1",
      "status": {
        "state": "TASK_STATE_COMPLETED"
      },
      "artifacts": [
        {
          "artifactId": "artifact-1",
          "parts": [
            {"text": "处理完成"}
          ]
        }
      ]
    }
  }
}
```

主要字段：

| 字段 | 含义 | 产生方 |
|---|---|---|
| `jsonrpc` | JSON-RPC 版本，固定 `2.0` | A2A 入口 |
| `id` | 请求关联 ID | 入口从请求原样带回 |
| `result.task` | `SendMessage` 返回的 A2A Task | A2A SDK/TaskStore |
| `result.task.id` | Runtime Task ID；续轮和任务查询必须使用该值 | A2A SDK/TaskStore |
| `result.task.contextId` | 会话标识 | A2A SDK，根据请求消息上下文建立 |
| `result.task.status` | 当前 Task 状态对象 | A2A SDK/TaskStore |
| `result.task.status.state` | Task 状态，例如 `TASK_STATE_COMPLETED`、`TASK_STATE_INPUT_REQUIRED`、`TASK_STATE_FAILED` | A2A 执行器根据 Handler 终态映射 |
| `result.task.status.message` | 需要输入或失败时的 Agent 消息 | Handler 中断/错误经 A2A 执行器转换 |
| `result.task.artifacts` | 本轮业务输出集合 | A2A 执行器根据 Handler 输出创建 |
| `result.task.artifacts[].artifactId` | Artifact ID | A2A SDK 在未指定时生成 |
| `result.task.artifacts[].parts` | Artifact 的 TextPart 或 DataPart 列表 | A2A 执行器根据 Handler 输出创建 |
| `result.task.artifacts[].parts[].text` | 文本业务结果 | 非流式 A2A 执行器把 Handler `result.content` 转为字符串后创建 |
| `result.task.history` | Task 消息历史；是否返回及长度受 A2A 参数和 SDK 行为影响 | A2A SDK/TaskStore |

非流式 Handler 返回的 `result.content` 会转换成 TextPart；Handler `result` 中没有 `content` 时不会凭空创建业务 artifact。外层 Task 字段不是 Handler 原样返回，而是 A2A 层生成。

如果本轮是 `resume=false` 的直接远端委派，`SendMessage` 返回给调用方的完整响应如下。该示例限定为一个远端 Agent 的 Task 只包含一个普通 TextPart：

```json
{
  "jsonrpc": "2.0",
  "id": "request-1",
  "result": {
    "task": {
      "id": "task-1",
      "contextId": "conversation-1",
      "status": {
        "state": "TASK_STATE_COMPLETED"
      },
      "artifacts": [
        {
          "artifactId": "artifact-1",
          "parts": [
            {
              "text": "【远端 Agent 原始 TextPart.text】"
            }
          ]
        }
      ]
    }
  }
}
```

在上述单成员、单个普通 TextPart 场景中，远端原始 `TextPart.text` 位于 `result.task.artifacts[0].parts[0].text`，上例已在该值中标出。该值经过 `A2AEnabledServeOrchestrator` 和 `A2AAgentExecutor` 放入新的本地 Task；多个 Part、DataPart、多个远端成员或已识别终态 envelope 会经过拼接、序列化或业务值提取，因此这时该字段是聚合结果，不是远端 JSON-RPC 响应外层或单个 Part 的逐字节副本。

### 3.4 SendStreamingMessage 响应

`SendStreamingMessage` 返回 `text/event-stream`。每个 SSE 事件名固定为 `jsonrpc`：

```text
event:jsonrpc
data:{"jsonrpc":"2.0","id":"request-2","result":{"artifactUpdate":{"taskId":"task-1","artifact":{"artifactId":"artifact-1","parts":[{"text":"处理中"}]},"contextId":"conversation-1"}}}

event:jsonrpc
data:{"jsonrpc":"2.0","id":"request-2","result":{"statusUpdate":{"taskId":"task-1","status":{"state":"TASK_STATE_COMPLETED"},"contextId":"conversation-1"}}}

```

每个 `data` 都包含：

| 字段 | 含义 | 产生方 |
|---|---|---|
| `jsonrpc` | 固定 `2.0` | A2A 入口 |
| `id` | 原请求 ID | A2A 入口 |
| `result` | 一个带类型包装的 A2A streaming event | A2A SDK/执行器 |
| `result.task` | Task 快照事件（存在时） | A2A SDK/TaskStore |
| `result.message` | A2A Message 事件（存在时） | A2A SDK |
| `result.artifactUpdate` | artifact 更新事件（存在时） | Handler chunk 经 `ChunkMapper` 转换 |
| `result.artifactUpdate.taskId` | 当前 Task ID | A2A SDK |
| `result.artifactUpdate.contextId` | 当前会话 ID | A2A SDK |
| `result.artifactUpdate.artifact` | 当前业务输出 | Handler chunk 经 `ChunkMapper` 转换 |
| `result.artifactUpdate.artifact.artifactId` | 当前 Artifact ID | A2A SDK/AgentEmitter |
| `result.artifactUpdate.artifact.parts` | 本次增量的 TextPart 或 DataPart 列表 | Handler chunk 经 `ChunkMapper` 转换 |
| `result.artifactUpdate.artifact.parts[].text` | 文本增量值（Part 为 TextPart 时） | `ChunkMapper` |
| `result.artifactUpdate.artifact.parts[].data` | 结构化增量值（Part 为 DataPart 时，与 `text` 二选一） | `ChunkMapper` |
| `result.statusUpdate` | Task 状态更新事件（存在时） | 执行器根据 Handler 完成、中断或错误映射 |
| `result.statusUpdate.taskId` | 当前 Task ID | A2A SDK |
| `result.statusUpdate.contextId` | 当前会话 ID | A2A SDK |
| `result.statusUpdate.status` | 当前 Task 状态 | A2A 执行器 |
| `result.statusUpdate.status.state` | `TASK_STATE_COMPLETED`、`TASK_STATE_INPUT_REQUIRED` 或 `TASK_STATE_FAILED` 等 A2A Task 状态 | A2A 执行器 |

Handler 普通 chunk 转换为 artifact；`type=interrupt` 转换为 `TASK_STATE_INPUT_REQUIRED`；`type=error` 转换为 `TASK_STATE_FAILED`；正常完成转换为 `TASK_STATE_COMPLETED`。远端并发调用的中间输出还会带 `_remote_invocation` Part metadata，用于标识 `batchId`、`toolCallId` 和目标 Agent。

以下示例同样限定为 `resume=false`、一个远端 Agent 只产生一个普通文本 Part。除 SDK 在新 Task 启动时可能先发送的提交/工作状态事件外，与远端业务结果直接相关的完整事件序列依次为远端原始输出、批次聚合终值和完成状态；Runtime 生成的可选空字段不会出现在 JSON 中：

```text
event:jsonrpc
data:{"jsonrpc":"2.0","id":"request-2","result":{"artifactUpdate":{"taskId":"task-1","artifact":{"artifactId":"artifact-1","parts":[{"text":"【远端 Agent 原始输出】","metadata":{"_remote_invocation":{"kind":"remote_agent_output","batchId":"batch-1","toolCallId":"call-1","target":"remote-agent"}}}]},"contextId":"conversation-1"}}}

event:jsonrpc
data:{"jsonrpc":"2.0","id":"request-2","result":{"artifactUpdate":{"taskId":"task-1","artifact":{"artifactId":"artifact-2","parts":[{"text":"【从远端 Task artifacts 提取并聚合的业务终值】"}]},"contextId":"conversation-1"}}}

event:jsonrpc
data:{"jsonrpc":"2.0","id":"request-2","result":{"statusUpdate":{"taskId":"task-1","status":{"state":"TASK_STATE_COMPLETED"},"contextId":"conversation-1"}}}

```

第一个 `artifactUpdate` 中：

| 字段路径 | 来源 |
|---|---|
| `result.artifactUpdate.artifact.parts[0].text` | 远端 Agent artifact 的原始 `TextPart.text`；上例已在值中标出 |
| `result.artifactUpdate.artifact.parts[0].data` | 远端 Agent 输出为普通结构化值时使用；与 `text` 二选一 |
| `result.artifactUpdate.artifact.parts[0].metadata._remote_invocation` | Runtime 生成的来源信息，不是远端 Agent 原始报文的一部分 |
| `_remote_invocation.kind` | 固定为 `remote_agent_output` |
| `_remote_invocation.batchId` | Runtime 远端调用批次 ID |
| `_remote_invocation.toolCallId` | 触发该远端调用的工具调用 ID |
| `_remote_invocation.target` | 目标 Agent 注册名；注册名为空时使用工具名 |

第一个 `artifactUpdate` 的 `parts[0].text` 是远端原始 `TextPart.text`；第二个 `artifactUpdate` 是 `A2AEnabledServeOrchestrator.streamBatchResolution` 产生的聚合终值。远端协议外层、Task、Artifact、`statusUpdate` 和来源 metadata 都由当前 Runtime 重新构造。对于结构化输出，`ChunkMapper` 会保留普通值，但会对 Runtime 明确认识的 `answer`/`workflow_final` 终态 envelope 提取其中的业务值，所以不能把 A2A Part 一概描述成远端 JSON-RPC 报文的逐字节副本。`resume=true` 时，原始远端输出之后由恢复后的父 Handler 继续产生业务事件，后续结构不固定。

### 3.5 GetTask 与 SubscribeToTask

查询已有 Task：

```json
{
  "jsonrpc": "2.0",
  "id": "request-3",
  "method": "GetTask",
  "params": {
    "id": "task-1",
    "tenant": "tenant-1",
    "historyLength": 10
  }
}
```

`params.id` 是必填的非空字符串；`tenant` 和 `historyLength` 可选。响应的 `result` 直接是 Task 对象，不再包含 `task` 包装：Task ID 位于 `result.id`，会话位于 `result.contextId`，状态位于 `result.status`，业务输出位于 `result.artifacts`。

订阅 Task 后续事件：

```json
{
  "jsonrpc": "2.0",
  "id": "request-4",
  "method": "SubscribeToTask",
  "params": {
    "id": "task-1",
    "tenant": "tenant-1"
  }
}
```

`params.id` 是必填的非空字符串。响应采用与 `SendStreamingMessage` 相同的 `event:jsonrpc` SSE 外层。

### 3.6 JSON-RPC 错误

```json
{
  "jsonrpc": "2.0",
  "id": "request-1",
  "error": {
    "code": -32602,
    "message": "Invalid params: params.message.parts is required and must be a non-empty array"
  }
}
```

`jsonrpc` 和 `id` 与成功响应含义相同；`error.code` 是 JSON-RPC/A2A 错误码，`error.message` 是对应的错误说明。这里的 `message` 由当前解析器或 SDK 错误对象产生，不是 Handler 业务结果。

| code | 含义 | 常见触发条件 |
|---:|---|---|
| `-32700` | Parse error | 空 Body 或非法 JSON |
| `-32600` | Invalid Request | 根节点不是对象，或 `jsonrpc`、`method`、`id` 形态非法 |
| `-32601` | Method not found | method 不在四个已注册方法中 |
| `-32602` | Invalid params | `params`、`message`、`parts` 或方法专用字段非法 |
| `-32603` | Internal error | 未被协议错误覆盖的 Runtime 异常 |
| `-32001` | Task not found | 查询或订阅的 Task 不存在 |

### 3.7 非并发、并发与远端调用

| 场景 | 行为 |
|---|---|
| 单个 Send、无远端调用 | 创建或继续一个 Task，Handler 输出转换为 artifact/status，再返回或持续推送 |
| 多个不同 Task/Context | Controller 不做全局串行化，RequestHandler 和 TaskStore 分别维护 Task；可并发执行 |
| 同一 Task 的续轮 | 必须带正确 `taskId` 和 `contextId`，且 Task 应处于可恢复状态；是否接受由 A2A SDK 的 Task 状态校验决定 |
| 单个远端委派 | 远端 Task ID 保存到父 Task 对应的批次状态，远端完成后恢复父 Handler |
| 多个远端委派 | 使用与 Query 相同的全局有界 fan-out/fan-in；成员结果按原工具调用顺序聚合，流式中间 artifact 可交错 |
| 同一父 Task 重复启动远端批次 | Runtime 拒绝同时存在的活动或待恢复批次，避免同一父 Task 被重复恢复 |

## 4. Custom REST 入口

### 4.1 可见入口与请求

Custom REST 路径由 `openjiuwen.service.custom-rest.query-path` 配置，仅注册 `POST`。业务字段由宿主实现的 `CustomRestProtocolAdapter` 定义，因此框架不存在一套可列举的固定业务 Schema。为了避免把宿主字段误写成框架字段，本节不虚构业务键名。最小的框架层请求形态为：

```http
POST <openjiuwen.service.custom-rest.query-path 的配置值>
Content-Type: application/json
```

```json
{}
```

`{}` 仅表示框架允许空对象，不表示宿主 Adapter 必须接受它。实际 Body 字段、Header、Path Variable 和 Query 参数由 `CustomRestProtocolAdapter.toA2ARequest` 读取；它们会分别保存在 `Context.body`、`Context.headers`、`Context.pathVariables` 和 `Context.queryParams` 中并传给宿主 Adapter。

框架固定的 HTTP 处理规则：

| 输入 | 必填 | 框架行为 |
|---|---:|---|
| JSON Body | 否 | 空 Body 转为 `{}`；非空 Body 必须声明 `application/json` 或 `application/*+json`，且根节点必须是对象 |
| Header | 否 | 名称转小写；重复值保留为 List |
| Path Variable | 由配置路径决定 | 转为单值 Map |
| Query 参数 | 否 | 重复值保留为 List |
| `Accept` | 否 | 未提供时视为接受 SSE；Adapter 选择流式但请求明确拒绝 SSE 时返回 406 |

具体哪些业务字段必填，必须由宿主 Adapter 的 `toA2ARequest` 约束，并在宿主 API 文档中逐字段说明。框架只统一校验 Adapter 最终产生的 `params.message.contextId` 必须非空。

### 4.2 非流式响应

非流式成功固定为 HTTP 200 和 `application/json`，完整 Body 就是 `fromA2ATask(Task, Context)` 返回对象的 Jackson 序列化结果。框架不增加外层，因此不存在可脱离宿主 Adapter 给出的统一 JSON 字段示例。

| 字段 | 来源 |
|---|---|
| 所有业务字段 | 宿主 `CustomRestProtocolAdapter.fromA2ATask` |
| Task 状态、artifact 到业务字段的映射 | 宿主 Adapter |
| HTTP 200 和 `Content-Type` | Custom REST 框架 |

Adapter 输出必须非 null 且可被 Jackson 序列化。

### 4.3 流式响应

流式成功固定返回 HTTP 200 和 Query 相同的四个 SSE Header。每个完整 SSE frame 直接由 `fromA2AStreamEvent` 返回的 `SseEvent(event, data)` 形成：

```text
event:<SseEvent.event；为空时不输出本行>
data:<SseEvent.data 的 JSON 序列化结果>

```

| SSE 部分 | 来源和规则 |
|---|---|
| `event` | Adapter 的 `SseEvent.event`；可为空，不能包含 CR、LF 或 NUL |
| `data` | Adapter 的 `SseEvent.data`；必须非空且可序列化 |
| 业务字段 | 全部由宿主 Adapter 根据 A2A streaming event 投影 |
| 流式错误事件 | Adapter 的 `fromStreamError`；无有效返回时回退为 `event:error` |

流建立后的错误不会修改 HTTP 200，而是投影为一个错误 SSE 事件后结束。

### 4.4 稳定错误

错误 Body 优先由 `fromError` 产生；若 Adapter 返回 null 或不可序列化，则使用：

```json
{
  "error": {
    "code": "invalid_custom_request",
    "message": "conversationId is required"
  }
}
```

| HTTP | code | 触发条件 |
|---:|---|---|
| 400 | `invalid_json` | Body 不是合法 JSON |
| 400 | `invalid_custom_request` | JSON 根节点不是对象，或 Adapter 产生的 contextId 缺失 |
| 406 | `stream_not_acceptable` | Adapter 要求流式，但请求不接受 SSE |
| 409 | `conversation_busy` | 同会话已有请求正在准备/执行，或已有任务处于提交/执行中 |
| 409 | `conversation_task_conflict` | 存在无法识别的活动任务或状态 |
| 409 | `conversation_task_ambiguous` | 同会话存在多个活动正式 Task |
| 409 | `conversation_not_resumable` | 当前 Task 不能用普通输入恢复 |
| 415 | `unsupported_media_type` | 非空 Body 没有 JSON Content-Type |
| 500 | `adapter_execution_failed` | Adapter 命令、输出、序列化或内部投影失败 |
| 502 | `invalid_a2a_result` | blocking A2A 调用没有返回 Task |
| 503 | `agent_not_ready` | Agent 尚未加载 |
| 503 | `task_store_unavailable` | TaskStore 查询失败 |
| A2A 映射值 | `a2a_<code>` | A2A RequestHandler 拒绝请求 |

### 4.5 非并发、并发与远端调用

| 场景 | 行为 |
|---|---|
| 单请求 | Adapter 构造 A2A Send 命令；blocking 或 streaming 执行完成后再释放会话占用 |
| 不同 `contextId` 并发 | 可并发进入，各自通过 TaskStore 解析或创建 Task |
| 相同 `contextId` 并发 | Custom REST Bridge 有进程内互斥；后到请求立即返回 409 `conversation_busy`，不进入等待队列 |
| 未显式提供 `taskId` | Bridge 按 tenant 和 `contextId` 查询正式 Task；没有活动 Task 时创建，唯一 `INPUT_REQUIRED` Task 时自动续接 |
| 内部 Handler 触发远端调用 | 使用与 A2A/Query 相同的远端批次协调器；Custom REST 只负责把最终 A2A Task/event 投影为宿主报文 |

# 第二部分：Runtime、Handler 与 Adapter 契约

## 5. 从入口到 Handler

```text
Query JSON ---------------------> QueryIngressSupport ----+
                                                        |
A2A JSON-RPC -> A2AProtocolAdapter ---------------------+--> ServeRequest
                                                        |       |
Custom REST -> host Adapter -> A2A RequestHandler ------+       v
                                                ServeOrchestrator
                                                        |
                                      AgentHandler.query/streamQuery
                                                        |
                              QueryResponse / QueryChunk / interrupt
```

Custom REST 先转为 A2A，再由 A2A 执行器转为 `ServeRequest`；Query 直接构造 `ServeRequest`。所有具体 Agent 实现只需要实现 `AgentHandler`，不应自行处理 Servlet、WebFlux、JSON-RPC 或 SSE 对象。

## 6. ServeRequest

| 字段 | 类型 | Query 来源 | A2A/Custom REST 来源 | Handler 使用语义 |
|---|---|---|---|---|
| `conversationId` | String | `conversation_id` | `message.contextId` | 会话/Session 标识 |
| `messages` | List&lt;Map&gt; | 归一化后的 `messages` | 当前 A2A Message 的 TextPart 拼成一条消息 | 当前请求消息；不是 Runtime 自动补齐的完整历史 |
| `userId` | String | Body 或 `X-User-ID` | `X-User-ID`，缺省 `anonymous` | 用户标识 |
| `spaceId` | String | Body 或 `X-Space-ID` | `X-Space-ID`，缺省 `default` | 空间标识 |
| `tenantId` | String | Body 或 `X-Tenant-ID` | `X-Tenant-ID` | 租户标识 |
| `stream` | boolean | Body `stream` | 由 Send 方法决定 | 选择 `query` 或 `streamQuery`，也传给远端调用 |
| `metadata` | Map&lt;String,Object&gt; | HTTP headers/query/path/body | `params.metadata` 加 Runtime 可信扩展字段 | 中断恢复、Client Tools、远端批次和 Adapter 附加信息 |

Query MVC metadata：

```json
{
  "headers": {},
  "query": {},
  "path": "/v1/query",
  "body": {"conversation_id": "conversation-1", "stream": false}
}
```

WebFlux metadata 具有相同四个键，但 `query` 固定为 `{}`，`body` 只重建 `conversation_id`、`stream` 和非 null 的 `message`。

Query `messages[].metadata` 保留在 `ServeRequest.messages` 对应消息 Map 中，不会自动提升到 `ServeRequest.metadata`。Runtime 只在需要远端 A2A 调用时提取最后一条有效消息的 metadata Map，并把整个 Map 序列化到远端 A2A `Message.metadata`；本文不为这个开放 Map 定义内部键名。

A2A metadata 保留 `params.metadata`，但会删除调用方伪造的 `runtime.parentTaskId`、`runtime.remoteToolInputs`、`runtime.remoteBatchId`、`runtime.remoteToolResults`，再由 Runtime 根据可信 Task/Part 信息重新生成。

## 7. AgentHandler 输出与字段归属

本章先说明 `AgentHandler` 的通用输出契约，再说明 `JiuwenCoreAgentHandler` 包装 AgentCore `ReActAgent` 和 `DeepAgent` 时的具体行为。两类 Agent 都经 `Runner.runAgent(...)` 或 `Runner.runAgentStreaming(...)` 调用；流式调用只请求 `StreamMode.OUTPUT`，Runtime 负责把 AgentCore 对象归一化为 `QueryResponse` 或 `QueryChunk`。

AgentCore Handler 的非流式执行路径取决于构造时保存的对象：

| Handler 中保存的对象 | `query(...)` 实际路径 |
|---|---|
| 具有公开 `invoke` 方法的 Agent 实例；ReActAgent 和 DeepAgent 实例均属于此类 | `Runner.runAgent(...)`，再归一化单个 Core 返回值 |
| Agent ID 字符串，或没有公开 `invoke` 方法的对象 | `Runner.runAgentStreaming(...)`，消费完整 Core 流后聚合成一个 `QueryResponse` |

按 Agent ID 注册时，即使 Runner 最终解析到 ReActAgent 或 DeepAgent，Handler 仍选择第二条路径，因为路径判断发生在 Runner 解析 Agent ID 之前。`streamQuery(...)` 不作此区分，始终调用 `Runner.runAgentStreaming(...)`。

### 7.1 非流式通用契约

```java
QueryResponse query(ServeRequest request);
```

Handler 必须返回非 null `QueryResponse`：

| 字段 | 产生方 | 含义 |
|---|---|---|
| `QueryResponse.result` | Handler | 最终业务结果。AgentCore Handler 固定构造成 Map |
| `result.role` | AgentCore Handler | 固定为 `assistant` |
| `result.content` | AgentCore Handler | 从 Core 返回值中抽取的最终文本；中断时为中断提示文本，缺失时为空字符串 |
| `result._interrupt` | AgentCore Handler | 仅中断时存在；值为归一化后的一个中断或中断批次 |
| `QueryResponse.conversationId` | AgentCore Handler | 原样回填 `ServeRequest.conversationId`，序列化字段名为 `conversation_id` |

正常结果示例：

```json
{
  "result": {
    "role": "assistant",
    "content": "最终回答"
  },
  "conversation_id": "conversation-1"
}
```

AgentCore Handler 按下列顺序生成 `result.content`：

1. Core 返回 Map 时，按 `output`、`content`、`response`、`result`、`data`、`payload` 的顺序递归查找第一个可转为文本的值。
2. Map 只有一个字段且未命中上述名称时，继续递归处理该唯一字段的值；多字段 Map 没有可抽取字段时，使用整个 Map 的字符串表示。
3. `ControllerOutput` 先处理其 `data`，`WorkflowOutput` 先处理其 `result`；其他对象使用其字符串表示。
4. Core 返回 `result_type=interrupt` 且 `state` 中含 `__interaction__` 事件时，不执行普通文本抽取，而是生成 `result._interrupt`；单个中断保留单对象结构，多个中断生成 `{message, items}` 批次结构。

非流式走“消费 Core 流”路径时，Handler 逐个归一化事件并按 `content`、`delta`、`output`、`response`、`result`、`data`、`payload` 递归抽取文本；已经累积到增量文本后，不再重复追加 `type=answer` 的最终文本。遇到 `__interaction__` 时优先返回中断；没有中断时返回累计文本，累计文本为空则使用最后一个 payload 的字符串表示。该聚合路径不把顶层 `type=error` 或 payload 的 `result_type=error` 转成非流式错误字段，而是按相同文本规则聚合；只有迭代 Core 流本身抛出异常时，异常才会向入口层传播。

Core 返回 Map 中的其他字段不会直接并入 `QueryResponse.result`。例如 `result_type`、`interrupt_ids`、DeepAgent 的 `rounds` 和 `loop_state` 都是 Core 内部执行结果；只有上述文本和中断字段经过 Handler 投影到非流式响应。

Query 入口原样序列化 `QueryResponse.result` 和 `conversation_id`。A2A 入口只提取 Map 结果中的 `content` 作为 artifact；如果存在 `_interrupt`，则转成 INPUT_REQUIRED 状态。Custom REST 再由宿主 Adapter 将最终 A2A Task 投影成业务 Body。

### 7.2 流式通用契约

```java
void streamQuery(ServeRequest request, QueryStreamObserver observer);
```

Handler 通过以下回调形成流：

| 回调 | 含义 | Query 投影 | A2A 投影 |
|---|---|---|---|
| `onNext(QueryChunk)` | 一个业务增量、中断或错误 | 序列化 `data` | artifact、INPUT_REQUIRED 或 FAILED |
| `onComplete()` | 正常结束 | 关闭 SSE | COMPLETED；已中断/失败时不再发送 COMPLETED |
| `onError(Throwable)` | 异常结束 | 连接错误 | FAILED |
| `isCancelled()` | 调用方是否已取消 | Handler 应停止继续产出 | Handler 应停止继续产出 |

AgentCore 的普通流事件使用 `OutputSchema(type, index, payload)`。AgentCore Handler 将它归一化为以下 `QueryChunk`：

```json
{
  "type": "chunk",
  "data": {
    "type": "llm_output",
    "index": 0,
    "payload": {
      "content": "增量文本",
      "result_type": "answer"
    }
  }
}
```

外层 `QueryChunk.type` 是 Runtime 的路由类型；`data.type`、`data.index` 和 `data.payload` 来自 Core `OutputSchema`。具体映射如下：

| Core/Runtime 原始值 | `QueryChunk.type` | `QueryChunk.data` | 处理规则 |
|---|---|---|---|
| 普通 `OutputSchema` | `chunk` | `{type,index,payload}` | 保留 Core 事件类型、序号和 payload |
| `OutputSchema.type=__interaction__` | `interrupt` | 归一化中断 Map | 先缓存；Core 流结束后一次性输出一个中断或一个 `{message,items}` 批次 |
| `OutputSchema.type=error` | `error` | `{type,index,payload}` | 先 `onNext` 错误 chunk，再 `onError`，不调用 `onComplete` |
| Map | 由 Map 顶层 `type` 决定 | 原 Map | 顶层 `__interaction__`/`error` 分别映射为中断/错误，其余为普通 chunk |
| 其他对象 | `chunk` | `{type:"chunk",data:<原值>}` | Handler 补统一 Map 外壳 |
| Runner/归一化过程抛出异常 | `error` | `{type:"error",error:<异常消息>}` | Handler 构造错误数据，调用 `onNext` 后再调用 `onError` |

`QueryChunk` 的框架类型及其产生位置如下：

| type | 产生位置 | 含义 |
|---|---|---|
| `chunk` | 具体 Handler | 普通中间/最终业务数据 |
| `interrupt` | 具体 Handler | 需要用户输入、Client Tool 结果或远端委派处理 |
| `remote_agent_output` | Runtime 远端编排层 | 远端 Agent 的流式业务输出及来源信息；不是 ReActAgent/DeepAgent 的 Core 事件类型 |
| `error` | 具体 Handler | 流式失败终态 |

Handler 不应在 `type=error` 后再发送普通 chunk 或完成数据；编排层和 A2A 执行器会把它视为失败终态。

错误判定只看归一化数据的顶层 `type`。例如 `data.type="answer"`、`data.payload.result_type="error"` 仍是普通 `chunk`，不会触发 `onError`；只有 `data.type="error"`（或原始 Map 顶层 `type="error"`）才是流式失败终态。

### 7.3 ReActAgent 输出

`ReActAgent` 直接执行模型调用、工具调用和中断恢复循环。Core 非流式返回值是 Map，不是 HTTP 响应对象。

#### 7.3.1 非流式

Handler 直接持有 ReActAgent 实例时，Core 返回值和 Handler 投影如下：

| Core 场景 | Core 返回值 | AgentCore Handler 输出 |
|---|---|---|
| 正常回答 | `{output:<模型最终文本>, result_type:"answer"}` | `result={role:"assistant",content:<output>}` |
| 工具调用中断 | `{result_type:"interrupt", state:[OutputSchema...], interrupt_ids:[...]}` | `result={role:"assistant",content:<中断消息>,_interrupt:<归一化中断>}` |
| Core 以结果 Map 表示失败 | `{output:<错误文本>, result_type:"error"}` | `result={role:"assistant",content:<output>}`；非流式 `QueryResponse` 没有独立错误字段 |
| Core/Runner 抛出异常 | 无正常返回值 | Handler 不构造 `QueryResponse`，异常向入口层传播 |

Handler 持有 ReActAgent 的 Agent ID 时，非流式请求改走 7.1 所述的流聚合路径：`llm_reasoning` 和 `llm_output` 中可抽取的文本按事件顺序累计，已有增量正文时跳过最终 `answer` 的重复文本；中断仍生成 `_interrupt`，Core `error` 事件则按其 `output` 文本聚合。

ReActAgent 中断的 `state` 元素为 `OutputSchema("__interaction__", index, InteractionOutput)`。Handler 生成的中断字段如下：

| 字段 | 产生方 | 含义 |
|---|---|---|
| `type` | Handler 读取 Core `OutputSchema.type` | 固定为 `__interaction__` |
| `index` | Core | 当前中断在该批交互中的序号 |
| `payload` | Core | 原始 `InteractionOutput`，保留其 `id` 和 `value` 对象 |
| `message` | Handler 从 `InteractionOutput.value` 抽取 | 用户可读的输入/确认提示；仅 Core 提供时存在 |
| `context` | Handler 从 `InterruptRequest` 抽取 | 中断上下文；仅 Core 提供时存在 |
| `toolCallId` | Handler 从 `ToolCallInterruptRequest` 抽取 | 被中断工具调用的 ID；仅工具中断时存在 |
| `toolName` | Handler 从 `ToolCallInterruptRequest` 抽取 | 被中断工具名；仅工具中断时存在 |

#### 7.3.2 流式

ReActAgent 可能产生以下 `OutputSchema`；Handler 不改写其内层 payload：

| `OutputSchema.type` | payload 字段 | 字段产生方与含义 |
|---|---|---|
| `llm_reasoning` | `content`, `result_type="answer"` | Core 从模型 reasoning 增量产生 |
| `llm_output` | `content`, `result_type="answer"` | Core 从模型正文增量产生 |
| `llm_output` | `tool_calls` | Core 从模型工具调用增量产生；该事件不一定包含 `result_type` |
| `llm_usage` | `usage_metadata`, `result_type="answer"` | Core 从模型 usage 增量产生 |
| `answer` | `output`, `result_type` | Core 的最终结果事件；`result_type` 通常为 `answer`，也可能保留 Core 生成的 `error`，但外层类型仍为 `answer` |
| `__interaction__` | `InteractionOutput` | Core 的工具中断事件；Handler 转成 `interrupt` chunk |
| `error` | `output`, `result_type="error"` | Core 捕获流式执行异常后产生；Handler 转成失败终态 |

模型正文通常同时出现在一个或多个 `llm_output.payload.content` 增量以及最终 `answer.payload.output` 中。Handler 流式模式逐事件透传，不负责去重；是否展示增量、最终事件或两者由调用方按 `data.type` 决定。

### 7.4 DeepAgent 输出

`DeepAgent` 内部持有一个 `ReActAgent`，并根据 `DeepAgentConfig.isEnableTaskLoop()` 决定是否执行外层任务循环。该开关会实质改变输出结构。

#### 7.4.1 未启用任务循环

Handler 直接持有 DeepAgent 实例时，非流式 `DeepAgent.invoke(...)` 返回执行描述 Map：

```json
{
  "agent_name": "deep_agent",
  "mode": "normal",
  "workspace": "<workspace path>",
  "inputs": {
    "conversation_id": "conversation-1",
    "query": "用户输入"
  }
}
```

这些字段均由 DeepAgent 产生。由于该 Map 没有 `output`/`content` 等结果字段，AgentCore Handler 将整个多字段 Map 的字符串表示写入 `QueryResponse.result.content`。流式调用会产生一个 Core `answer` 事件，其 payload 为 `{output:<上述 Map>, result_type:"answer"}`，Handler 再将它作为普通 `chunk` 输出。Handler 持有 DeepAgent 的 Agent ID 时，非流式请求消费这条 Core 流，最终仍从该 `answer.payload.output` 得到整个描述 Map 的字符串表示。

#### 7.4.2 启用任务循环

DeepAgent 的每一轮都通过内部 ReActAgent 执行。Handler 直接持有 DeepAgent 实例时，非流式 Core 返回 Map 的主要字段如下：

| 字段 | 产生方 | 含义 | Handler 是否直接返回 |
|---|---|---|---|
| `agent_name` | DeepAgent | Agent Card 名称 | 否 |
| `mode` | DeepAgent | 当前 `normal`/`plan` 模式 | 否 |
| `workspace` | DeepAgent | 当前工作区路径 | 否 |
| `inputs` | DeepAgent | 本次归一化输入 | 否 |
| `rounds` | DeepAgent | 按执行顺序保存的全部轮次结果 | 否 |
| `loop_state` | DeepAgent/LoopCoordinator | 迭代次数、停止原因和评估器状态等循环状态 | 否 |
| `final_result` | DeepAgent | 最后一轮的完整结果 | 否 |
| `output` | DeepAgent 从最后一轮复制 | 最后一轮最终业务输出 | 是，抽取为 `result.content` |
| `result_type` | DeepAgent 从最后一轮复制 | 最后一轮的 `answer`、`interrupt` 或 `error` | 仅用于判断中断；不作为响应字段返回 |
| `state` | DeepAgent 从最后一轮复制 | 最后一轮中断状态 | 中断时归一化为 `result._interrupt` |
| `usage_metadata`/`usage`/`token_usage`/`total_tokens` | DeepAgent 从最后一轮复制 | 最后一轮可用的用量信息 | 否 |

每个 `rounds[]` 元素由 DeepAgent 在内部 ReActAgent 结果上补充 `status`、`round`、`is_follow_up`、`query` 和 `mode`；应用任务指令时还会有 `task_instruction_query`。这些诊断字段保留在 Core 返回值中，不进入当前 Handler 的 `QueryResponse.result`。

流式任务循环设置内部采集标记，并逐轮转发内部 ReActAgent 的 `OutputSchema`。因此：

- 用户会收到各轮的 `llm_reasoning`、`llm_output`、`llm_usage`、`answer`、`__interaction__` 或 `error` 事件，字段结构与 7.3.2 相同。
- `data.index` 是内部 ReActAgent 流事件序号，不是 DeepAgent 的外层 `round`。
- DeepAgent 最终汇总 Map（`rounds`、`loop_state`、`final_result` 等）不会额外生成一个流式 chunk；流正常耗尽后由 Handler 调用 `onComplete()`。
- 内部 ReActAgent 中断事件仍由 Handler 缓存并归并为一个 `interrupt` chunk；内部错误事件仍转换为 `error` 并以 `onError` 结束。

Handler 持有 DeepAgent 的 Agent ID 时，非流式请求同样消费上述逐轮流并聚合正文，不会获得 DeepAgent 非流式返回值中的 `rounds`、`loop_state` 或 `final_result`；中断仍优先生成 `_interrupt`，Core `error` 事件则按其 `output` 文本聚合。只有调用 `streamQuery(...)` 时，顶层 `type=error` 才转换为 `QueryChunk.type=error` 和 `onError`。

### 7.5 ReActAgent 与 DeepAgent 对照

| 场景 | ReActAgent | DeepAgent 未启用任务循环 | DeepAgent 启用任务循环 |
|---|---|---|---|
| Core 非流式主返回 | `{output,result_type}` 或中断 Map | `{agent_name,mode,workspace,inputs}` | 外层汇总 Map，并复制最后一轮 `output/result_type/state` |
| Handler 非流式正文 | ReAct 最终 `output` | 整个描述 Map 的字符串表示 | 最后一轮 `output` |
| Core 流式来源 | 当前 ReAct 循环 | 一个包装描述 Map 的 `answer` | 每一轮内部 ReAct 流 |
| 正常流终态 | `answer` 后流结束 | 单个 `answer` 后流结束 | 最后一轮内部事件结束后关闭流，无额外外层汇总事件 |
| 中断 | `__interaction__` | 无内部 ReAct 执行产生的工具中断 | 内部 ReAct 的 `__interaction__` |
| 错误事件 | Core `error` 或 Handler 捕获异常 | DeepAgent 捕获的 `error` 或 Handler 捕获异常 | 内部/外层 `error` 或 Handler 捕获异常 |
| 会话清理 | Handler 清理 ReActAgent 的 ContextEngine | Handler 递归取得并清理内部 ReActAgent 的 ContextEngine | 同左 |

## 8. AgentScope Adapter

### 8.1 请求映射

- `conversationId` 映射到 AgentScope `RuntimeContext.sessionId`。
- `userId` 映射到 `RuntimeContext.userId`。
- `tenantId`、`spaceId` 写入 RuntimeContext；如果 `ServeRequest.metadata` 已包含 `traceId` 或 `requestId`，Adapter 也会写入。这两个名称是 `AgentScopeRequestMapper` 显式读取的扩展键，不是 Query Body 字段，也不会从 `messages[].metadata` 自动提升。
- 普通调用使用 `AgentScopeRequestMapper.latestEffectiveContent()`：从后向前选择最后一条 `role=user` 且 `content` 非空白的消息；没有时回退到最后一条 `content` 非空白的消息。选中的内容转换为本轮 `UserMessage`，不会重复写入 Runtime 已携带的完整历史。

### 8.2 正常输出

非流式 `result` 示例：

```json
{
  "role": "assistant",
  "content": "AgentScope 返回文本"
}
```

流式 `QueryChunk.data` 示例：

```json
{
  "type": "answer_delta",
  "content": "增量文本"
}
```

流必须观察到最终结果或已识别的 interrupt 才能正常完成；没有业务终态就结束时，Adapter 输出 error chunk 并以 Observer error 结束。

### 8.3 中断与恢复

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

- `confirmation`：同一 Task 的下一条文本必须为 `APPROVE` 或 `REJECT`，忽略大小写和首尾空白。
- `tool_result`：同一 Task 的下一条非空文本作为唯一 pending 工具结果。
- `message`：下一轮仅作为继续信号，正文不转换为普通 AgentScope user query。

客户端不需要回传 AgentScope 内部 tool-call ID、`ToolUseBlock` 或完整状态。

## 9. Versatile Adapter

### 9.1 出站请求

URL 使用配置的 `url-template` 并替换 `{conversation_id}`。Body 构造规则：

```text
body = copy(ServeRequest.metadata.body.custom_data)
inputs = copy(body.inputs)
inputs.query = 当前消息解析出的 query，缺失时使用 lastUserQuery
inputs.intent = 当前消息解析出的 intent（如有）
inputs.intents = 配置候选意图的 JSON 字符串（如有）
inputs.messages = 只含有效 role/content 的重建消息列表的 JSON 字符串（如有）
body.inputs = inputs
```

`ServeRequest.metadata.headers` 只按白名单透传，随后由 `headers-template` 覆盖。`metadata.query` 中的值转为字符串并进行 URL 编码。

`interrupt.resume-request-template.body` 配置后会在每次调用合并；同名顶层键覆盖普通 Body，`inputs` 不做深度合并。

### 9.2 远端响应到 QueryChunk

远端必须返回 2xx。Adapter 按 UTF-8 逐行读取：

- 剥离 `data:` 前缀，忽略空行和常规 SSE 控制行。
- 普通数据先输出 `TYPE_CHUNK`；结果节点和中断信号用于收口。
- 顶层 `event=exception` 形成错误终态。
- 任意层级出现 `node_type=End` 标记正常完成。
- 收到顶层 `event=end`，但没有结果、完成节点或完整原生中断时，形成“需要输入”中断。
- HTTP 响应流直接关闭，且此前没有可识别终态时，形成 `TYPE_ERROR`，错误码为 `VERSATILE_STREAM_CLOSED_WITHOUT_TERMINAL`。

未配置 `result-extractions` 时，目标结果节点的 QA text 生成：

```json
{"type": "answer", "output": "远端结果"}
```

配置三字段抽取后，Adapter 解析 `response_content`、`intent_id` 和 `agent_id`；生成的内部委派交给 Runtime 继续处理，不新增用户侧 HTTP 报文类型。

## 10. AgentCore-ext 远端 A2A 工具

Runtime 完成远端 Agent Card 发现后，为当前 AgentCore Agent 安装远端工具。远端注册名成为工具名，输入 Schema 为：

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

模型调用工具后，Rail 产生：

```json
{
  "message": "remoteInput 的字符串值",
  "context": {
    "agentName": "远端注册名",
    "_interrupt_kind": "a2a_delegate"
  }
}
```

单个或多个 `a2a_delegate` 统一交给 `RemoteInvocationBatchCoordinator`。协调器负责全局并发槽、FIFO 队列、远端 Task 保存、成员结果聚合和父 Handler 恢复。多个成员的最终结果按原始 `index` 排序，不按实际完成顺序恢复。

## 11. Client Tools

### 11.1 声明

Client Tools 通过 A2A `params.metadata.clientTools` 声明。完整请求示例：

```json
{
  "jsonrpc": "2.0",
  "id": "request-5",
  "method": "SendMessage",
  "params": {
    "message": {
      "role": "ROLE_USER",
      "messageId": "message-5",
      "contextId": "conversation-1",
      "parts": [
        {"text": "处理这个请求"}
      ]
    },
    "metadata": {
      "clientTools": [
        {
          "name": "getLocalWeather",
          "description": "读取客户端本地天气",
          "inputSchema": {}
        }
      ]
    }
  }
}
```

`clientTools` 必须是数组；每项必须是对象且 `name` 非空。`inputSchema` 省略时使用空对象。名称不能在同一请求中重复，也不能与服务端工具或当前模型工具同名。

### 11.2 中断返回与恢复

单工具调用时，AgentCore Handler 产生的中断数据为：

```json
{
  "type": "__interaction__",
  "toolCallId": "call-1",
  "toolName": "getLocalWeather",
  "message": "Client tool invocation required: getLocalWeather",
  "context": {
    "_interrupt_kind": "client_tool",
    "arguments": {}
  }
}
```

经 `SendMessage` 返回给用户时，完整 A2A 响应如下；SDK 为空的可选字段不在示例中显示：

```json
{
  "jsonrpc": "2.0",
  "id": "request-5",
  "result": {
    "task": {
      "id": "task-1",
      "contextId": "conversation-1",
      "status": {
        "state": "TASK_STATE_INPUT_REQUIRED",
        "message": {
          "role": "ROLE_AGENT",
          "parts": [
            {
              "text": "Client tool invocation required: getLocalWeather"
            }
          ],
          "metadata": {
            "_interrupt": {
              "type": "__interaction__",
              "toolCallId": "call-1",
              "toolName": "getLocalWeather",
              "message": "Client tool invocation required: getLocalWeather",
              "context": {
                "_interrupt_kind": "client_tool",
                "arguments": {}
              }
            }
          }
        }
      }
    }
  }
}
```

`jsonrpc`、`id`、`result.task`、`status` 和 `status.message` 由 A2A 入口与 SDK 构造；Handler 产生的完整中断数据位于 `result.task.status.message.metadata._interrupt`。客户端应使用响应中的 `result.task.id`、`result.task.contextId`、`toolName`、`toolCallId` 和 `arguments` 执行本地工具。

单个 pending 可用一个 TextPart 恢复。多个 pending 必须逐项定向：

```json
{
  "jsonrpc": "2.0",
  "id": "request-6",
  "method": "SendMessage",
  "params": {
    "message": {
      "role": "ROLE_USER",
      "taskId": "task-1",
      "contextId": "conversation-1",
      "parts": [
        {"text": "工具 A 结果", "metadata": {"toolCallId": "call-a"}},
        {"text": "工具 B 结果", "metadata": {"toolCallId": "call-b"}}
      ]
    }
  }
}
```

目标集合必须与全部 pending 调用一致。缺失或未知 ID 会拒绝整次续轮；同一 `toolCallId` 的多个 TextPart 按出现顺序拼接。所有 TextPart 必须统一为定向或非定向，不能混合。恢复时无需再次发送 `clientTools`。
