# Client Tools 特性

## 1. 定位

Client Tools 允许 A2A 客户端在单次请求中声明只能由客户端执行的工具。AgentCore 模型可以看到并选择
这些工具，但服务端不会执行它们；服务端把真实工具调用转换为 `INPUT_REQUIRED`，客户端执行后再用
原 Task 续轮提交结果。

该能力实现在 `agent-service-adapters-agentcore-ext` 中，只由 `JiuwenCoreAgentExtHandler` 在每次
`query` 或 `streamQuery` 周围安装。它不是基础 `JiuwenCoreAgentHandler` 的自动能力，也没有 YAML 配置。

## 2. 支持的 Agent

`ClientToolRail.bind` 支持：

- `BaseAgent`：session ID 必须与 `conversationId` 精确相等。
- `DeepAgent`：Rail 安装到内部 BaseAgent，并匹配 `conversationId_<number>` 形式的派生 session。

其他 Agent 类型会在绑定阶段拒绝。没有 `clientTools` 且没有待恢复 client-tool interrupt 时返回 no-op
Binding，不修改 Agent。

## 3. 首轮声明

客户端在 A2A `params.metadata.clientTools` 中传入数组：

```json
[
  {
    "name": "getLocalWeather",
    "description": "Read weather from the client device",
    "inputSchema": {
      "type": "object",
      "properties": {
        "city": {"type": "string"}
      },
      "required": ["city"]
    }
  }
]
```

绑定阶段执行以下校验：

- `clientTools` 必须为数组，每项必须为对象。
- `name` 必须非空，同一请求不能重复。
- `inputSchema` 必须是对象；省略时使用空对象。
- 名称不能与 Agent 已注册的服务端工具冲突。
- 注入模型前再次检查，不能与当轮模型工具列表冲突。

通过校验后，工具定义只合并到属于当前请求 session 的模型输入。

## 4. 执行链路

```text
A2A request metadata.clientTools
  -> JiuwenCoreAgentExtHandler
  -> ClientToolRail.bind
  -> beforeModelCall 注入 ToolInfo
  -> 模型产生 ToolCall
  -> beforeToolCall 拦截客户端工具
  -> client_tool interrupt
  -> Runtime 保存 Task 为 INPUT_REQUIRED
  -> 客户端执行 toolName + arguments
  -> 使用原 taskId/contextId 提交结果
  -> Rail 把结果作为 AgentCore tool result
  -> 模型继续生成最终回答
  -> Binding.close 精确卸载本次 Rail
```

## 5. 中断协议

Rail 要求模型产生非空 `toolName` 和 `toolCallId`。非空 `ToolCall.arguments` 必须能解析为 JSON
Object；`null` 或空字符串按空对象 `{}` 处理。首次调用产生的 interrupt context 为：

```json
{
  "_interrupt_kind": "client_tool",
  "arguments": {
    "city": "深圳"
  }
}
```

Runtime 的 AgentCore Handler 补充 `type=__interaction__`、`toolName`、`toolCallId` 和 message。
同一轮多个工具调用以 `_interrupt.items[]` 返回，各成员保留自己的真实 `toolCallId`。

## 6. 恢复规则

恢复请求由 Runtime 从原 Task 的 `_interrupt` 重建 pending 集合。

- 单 pending：一个普通 TextPart 可以省略 `metadata.toolCallId`。
- 单 pending：也可以显式携带正确的 `toolCallId`。
- 多 pending：每个 TextPart 必须携带对应 `metadata.toolCallId`。
- 多 pending 的目标集合必须与 pending ID 完全相等，不允许缺失、未知或广播。
- 同一恢复请求不能混合 `client_tool` 与其他 interrupt kind。

有显式目标时，Adapter 将 Runtime 准备的 `runtime.remoteToolInputs` 校验后转换成
`runtime.remoteToolResults`，供基础 AgentCore Handler 按 toolCallId 恢复。Rail 收到 pending 调用的
resume input 后执行 reject 分支，把该 input 作为工具结果交给 AgentCore，而不再次中断。

## 7. 请求级隔离

Rail 的生命周期被 Handler 的 try-with-resources 包围。无论正常返回、异常还是流式调用结束，
`Binding.close` 都只卸载当前创建的 Rail，并且重复 close 安全。后续请求若不再携带 `clientTools`，
不会看到上一轮动态工具。

DeepAgent 可能为同一业务请求创建派生 session，因此隔离判断同时支持业务 `conversationId` 和其数字
后缀 session；不会把工具注入其他会话。

## 8. 错误与安全边界

以下情况在调用模型或恢复 AgentCore 前失败：

- 工具定义、Schema 或 arguments 不是预期的 JSON 结构。
- 工具名重复，或与服务端/模型工具冲突。
- pending toolName 与恢复调用的 toolName 不一致。
- 多工具结果没有完整、精确地按 toolCallId 定向。
- interrupt items 混合 client tool 与其他类型。

工具描述和 Schema 会进入模型上下文，arguments 和客户端结果会跨 A2A 边界。不要在工具 Schema、
模型生成参数或恢复文本中放置 API Key、Cookie 等凭据。服务端不校验客户端业务执行结果的真实性，
调用方需要在 A2A 入口完成身份认证、授权和结果来源校验。

## 9. 限制

- 仅支持 AgentCore-ext 的实例型 `BaseAgent` 或 `DeepAgent`。
- 工具只在请求期间存在，不提供服务端持久注册。
- 非空 arguments 必须是 JSON Object，标量或数组会被拒绝；空 arguments 映射为 `{}`。
- 多 pending 必须一次提交完整结果集合。
- Client Tools 与远端 A2A 工具可以使用同一 Handler，但同名工具不允许共存。

## 10. 相关文档

- [入口与数据契约](../entrypoints-and-contracts.md)
- [配置参考](../configuration.md)
- [AgentCore 工具扩展指南](../guides/agentcore-ext-tools.md)
- [AgentCore-ext 特性](agentcore-ext.md)
