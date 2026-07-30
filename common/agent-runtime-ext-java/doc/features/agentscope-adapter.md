# AgentScope Adapter 特性

## 1. 定位

AgentScope Adapter 把本地 AgentScope Java Agent 接到 Runtime `AgentHandler` SPI。它负责请求、事件、
结果和恢复输入的类型转换，不启动远端 AgentScope 服务，也不新增 HTTP 端点。

Maven 模块：

```xml
<dependency>
  <groupId>com.openjiuwen</groupId>
  <artifactId>agent-service-adapters-agentscope</artifactId>
  <version>0.1.1</version>
</dependency>
```

当前支持两类宿主 Agent：

```java
AgentScopeAgentHandler.forReActAgent(ReActAgent agent)
AgentScopeAgentHandler.forHarnessAgent(HarnessAgent agent)
```

模块没有 Spring Boot 自动装配，也没有 YAML 配置类。宿主必须先构造 Agent，再将 Handler 注册为 Bean。

## 2. 请求映射

每次调用先创建 AgentScope `RuntimeContext`：

| Runtime 输入 | AgentScope 目标 |
|---|---|
| `conversationId` | `sessionId`，必须非空 |
| `userId` | `userId` |
| `tenantId` | Context 扩展值 `tenantId` |
| `spaceId` | Context 扩展值 `spaceId` |
| `metadata.traceId` | Context 扩展值 `traceId` |
| `metadata.requestId` | Context 扩展值 `requestId` |

普通调用只选择最后一条具有非空 content 的 user 消息；找不到 user 时回退到最后一条有效消息。
Adapter 把该内容转换为一个 `UserMessage`，不会把 Runtime 已有历史再次批量写入 AgentScope。

## 3. 执行方式

`AgentScopeInvoker` 隐藏 ReAct 和 Harness 的调用差异：

| 操作 | ReActAgent | HarnessAgent |
|---|---|---|
| 非流式 | `agent.call(messages, context)` | `agent.call(messages, context)` |
| 流式 | `agent.streamEvents(messages, context)` | `agent.streamEvents(messages, context)` |
| 状态 | `agent.getAgentState(userId, sessionId)` | `agent.getDelegate().getAgentState(...)` |
| 中断 | `agent.interrupt(userId, sessionId)` | `agent.getDelegate().interrupt(...)` |

非流式调用等待 Mono 完成并获取最终 `Msg`，然后读取相同 user/session 的 `AgentState` 解释暂停状态。
流式调用订阅 AgentEvent Flux，将事件逐个映射成 `QueryChunk`，并等待流出现合法终态。

## 4. 输出映射

### 4.1 非流式

正常结果为：

```json
{
  "role": "assistant",
  "content": "AgentScope result text"
}
```

若最终 `GenerateReason` 表示可恢复暂停，同一结果增加 `_interrupt`。`MODEL_STOP`、
`STRUCTURED_OUTPUT`、`MAX_ITERATIONS` 和 `ALL_TOOLS_DENIED` 作为正常终态处理。
`INTERRUPTED` 和未形成可支持暂停状态的 `TOOL_CALLS` 被视为不支持的终态并失败。

### 4.2 流式

| AgentScope 事件 | Runtime 输出 |
|---|---|
| `TextBlockDeltaEvent` | `TYPE_CHUNK`，data 为 `{type: answer_delta, content: delta}` |
| `RequireUserConfirmEvent` | 一次 `TYPE_INTERRUPT`，kind 为 `confirmation` |
| `AgentResultEvent(TOOL_SUSPENDED)` | 一次 `TYPE_INTERRUPT`，kind 为 `tool_result` |
| `AgentResultEvent(PERMISSION_ASKING)` | 一次 `TYPE_INTERRUPT`，kind 为 `confirmation` |
| 中间件、推理或行动停止 | 一次 `TYPE_INTERRUPT`，kind 为 `message` |
| 其他事件 | 不输出 chunk |

Adapter 会去重中断；同一 stream 最多输出一个 interrupt。正常 Flux complete 之前必须已经看到非空最终结果
或已识别 interrupt，否则输出 `TYPE_ERROR` 并调用 `observer.onError`，不会伪装成成功完成。

## 5. 中断与恢复

### 5.1 Confirmation

`RequireUserConfirmEvent` 必须与当前 AgentState 中所有 `ASKING` pending tool ID 完全一致。对外中断只暴露
工具名称，不暴露 AgentScope 内部 ID 或参数。续轮只接受 `APPROVE` 或 `REJECT`，并为所有 ASKING
工具构造 `ConfirmResult`，放入 metadata-only `UserMessage`。

### 5.2 External Tool Result

`TOOL_SUSPENDED` 要求当前状态中恰好一个非 ASKING pending tool。对外中断携带工具名和浅复制的 input
作为 `arguments`。续轮正文被包装为 `TOOL` role 的 `ToolResultBlock`，其 ID 和 name 从当前
AgentState 读取，客户端无需回传内部 ID。

### 5.3 Message Pause

中间件、推理或行动请求停止时输出 `kind=message`。续轮不产生新的 AgentScope 消息，仅作为继续调用
信号；不会把该正文当成新的业务问题。

Runtime 必须在 `ServeRequest.metadata._interrupt.payload.kind` 中提供上一轮保存的 kind。缺少 kind、
状态与 kind 不匹配或恢复内容非法时，Adapter 在调用 AgentScope 前失败。

## 6. 并发、超时和取消

- 相同 `conversationId` 同时只能有一个在途调用；后到请求立即失败，不排队。
- 非流式默认超时 5 分钟，流式默认超时 30 分钟；当前不是公共配置。
- Runtime 调用 `interrupt(conversationId, reason)` 时，Adapter 对匹配 session 发起 best-effort interrupt，
  并释放订阅和在途记录。
- Observer 取消后不再输出 chunk 或 terminal，并尝试中断 AgentScope session。
- 中断底层失败只记录警告，本地清理仍继续。

## 7. 状态边界

Adapter 只通过 AgentScope 公开的 `getAgentState` 读取当前 session 的 live state，用于识别 pending tool
和构造恢复对象。它不直接读写 StateStore 或 checkpoint，不持久化 `AgentState`，也不把完整状态、
provider metadata 或内部 tool-call ID 放入 Runtime interrupt。

Handler 使用预构建的 AgentScope Agent，未实现模型、工具、Workspace 或 StateStore 的网络探活。Runtime
把 Handler 启动成功视为已加载，只能证明对象可装配，不能证明这些下游依赖此刻健康。

## 8. 限制

- 当前只支持 `ReActAgent` 和 `HarnessAgent`。
- 外部工具结果恢复仅支持一个 pending tool。
- 输入输出以文本为主，其他 content block 不形成独立公开契约。
- Handler 未覆盖 `clearSession`，Runtime reset 不保证清除 AgentScope session 状态。
- Handler 未覆盖 `stop()` 来批量取消全部内部 `inFlight`；停机时非流式调用仍可能运行到完成或 5 分钟超时。
- 多副本恢复依赖同一 session 的状态在目标实例可见；当前建议单副本或按 `conversationId` 粘性路由。
- A2A `CancelTask` 尚未形成经过端到端验证的 AgentScope 立即取消保证。
- WebFlux Query 不是该 Adapter 的已验证入口；使用 MVC Query 或 A2A。

## 9. 相关文档

- [配置参考](../configuration.md)
- [入口与数据契约](../entrypoints-and-contracts.md)
- [外部 Agent Runtime 接入指南](../guides/external-agent-runtime-integration.md)
