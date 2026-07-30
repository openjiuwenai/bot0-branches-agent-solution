# 使用 AgentCore-ext 工具扩展

本指南在实例型 AgentCore Agent 上启用两类工具：服务端发现并调用的远端 A2A Agent，以及每次请求
由客户端动态声明和执行的 Client Tools。两类能力共享 `JiuwenCoreAgentExtHandler`，但协议和生命周期
不同。

## 1. 前置条件

- 宿主能够创建 `BaseAgent` 或 `DeepAgent` 实例。
- 应用已经引入基础 Runtime `agent-service-app` 和 AgentCore Adapter。
- 使用远端 A2A 工具时，目标 Agent 已暴露可访问的 Agent Card 和 JSON-RPC 入口。
- 使用 Client Tools 时，调用方使用 A2A `SendMessage` 或 `SendStreamingMessage`。

## 2. 添加依赖并注册 Handler

```xml
<dependency>
  <groupId>com.openjiuwen</groupId>
  <artifactId>agent-service-adapters-agentcore-ext</artifactId>
  <version>0.1.1</version>
</dependency>
```

```java
@Bean
AgentHandler agentHandler(MyAgentFactory factory) {
    BaseAgent agent = factory.createAgent();
    return new JiuwenCoreAgentExtHandler(agent);
}
```

DeepAgent 同样直接传实例。不要传字符串 Agent ID；构造器会拒绝。

可以设置 `openjiuwen.service.handler=agentcore-ext` 防止基础 agentcore 条件创建缺省 ID Handler，但该配置
不会创建 ext Handler，Java Bean 仍然必需。

## 3. 配置远端 A2A 工具

### 3.1 Agent A 配置

```yaml
openjiuwen:
  service:
    handler: agentcore-ext
    a2a:
      remote-invocation:
        max-concurrency: 16
        max-queue-size: 256
        queue-timeout-seconds: 30
      remote-agents:
        - name: banking-agent
          url: http://127.0.0.1:18091
          timeout-seconds: 300
          streaming: true
```

`name` 同时是 Registry key、模型工具名和委派目标名。使用简短、稳定、符合模型工具命名限制的名称，
不要依赖 Adapter 自动修剪或规范化。

### 3.2 Agent B Card

Agent B 至少声明一个 Skill。工具 description 优先使用所有 Skill description：

```yaml
openjiuwen:
  service:
    a2a:
      agent-description: Banking workflow agent
      skills:
        - id: account-balance
          name: Account balance
          description: Query an account balance and continue an interrupted banking workflow.
          tags: [banking, balance]
```

若 Agent B 位于反向代理、容器或网关之后，确保 Card 中的 JSON-RPC URL 是 Agent A 可访问地址。

### 3.3 验证安装和调用

启动 Agent B 后再启动 Agent A，或者保证 Agent A 调用前已经完成 Card 发现。触发模型选择
`banking-agent`，模型参数应包含：

```json
{"remoteInput": "查询尾号 4241 的银行卡余额"}
```

成功判据：

- Agent A 日志出现一次远端 Rail 安装，工具名为 `banking-agent`。
- AgentCore interrupt 的 kind 为 `a2a_delegate`，agentName 为 `banking-agent`。
- Runtime 调用 Agent B，并在完成后把结果恢复为原 ToolCall 的结果。
- 多个远端 ToolCall 使用各自真实 toolCallId 关联，完成顺序不影响结果归位。

## 4. 声明 Client Tool

首轮 A2A 请求在 `params.metadata.clientTools` 声明工具：

```json
{
  "jsonrpc": "2.0",
  "id": "client-tool-1",
  "method": "SendMessage",
  "params": {
    "message": {
      "role": "ROLE_USER",
      "messageId": "message-1",
      "contextId": "client-tool-conversation-1",
      "parts": [
        {"text": "查询深圳天气"}
      ]
    },
    "metadata": {
      "clientTools": [
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
    }
  }
}
```

成功判据：Task 进入 `TASK_STATE_INPUT_REQUIRED`，`status.message.metadata._interrupt` 中：

```text
toolName = getLocalWeather
context._interrupt_kind = client_tool
context.arguments.city = 模型生成的城市
```

## 5. 恢复单个 Client Tool

客户端执行工具后，使用第一轮实际 taskId 和 contextId：

```json
{
  "jsonrpc": "2.0",
  "id": "client-tool-2",
  "method": "SendMessage",
  "params": {
    "message": {
      "role": "ROLE_USER",
      "messageId": "message-2",
      "taskId": "task-from-round-1",
      "contextId": "context-from-round-1",
      "parts": [
        {"text": "{\"temperatureC\":31,\"condition\":\"sunny\"}"}
      ]
    }
  }
}
```

单 pending 可以省略 toolCallId。成功判据：Task 完成，模型最终回答使用了客户端结果，并且没有再次
触发同一个工具。

## 6. 恢复多个 Client Tools

第一轮 `_interrupt.items[]` 可能包含多个工具。客户端必须执行全部 pending 项，并逐项使用真实 ID：

```json
{
  "role": "ROLE_USER",
  "taskId": "task-from-round-1",
  "contextId": "context-from-round-1",
  "parts": [
    {
      "text": "weather result",
      "metadata": {"toolCallId": "call-weather"}
    },
    {
      "text": "calendar result",
      "metadata": {"toolCallId": "call-calendar"}
    }
  ]
}
```

成功判据：提交集合与 pending 集合完全相等，两个结果分别恢复到正确 ToolCall，Task 完成。只提交
一个结果或使用未知 ID 都应失败，而不是广播或部分恢复。同一 `toolCallId` 可以使用多个连续 TextPart，
其文本会按出现顺序拼接；但不能混合带目标 ID 和不带目标 ID 的 Part。

## 7. 同时使用两类工具

远端 A2A 工具在 Agent 实例上增量安装；Client Tools 只在当前请求期间注册。二者可以同时存在，但：

- 名称必须全局不冲突。
- 远端 A2A 工具 description 来自 Card，Client Tool description 来自请求。
- `a2a_delegate` 由 Runtime 远端协调器处理，`client_tool` 由调用客户端处理。
- 同一 interrupt items 不能混合 Client Tool 与其他 kind 后再按 Client Tool 协议恢复。
- 不要把远端 Agent 伪装成 Client Tool，这会绕过 Runtime 的远端 Task、超时和并发治理。

## 8. 请求隔离验证

完成 Client Tool Task 后，使用相同 contextId 创建一个不带原 taskId 的新 Task，并且不发送
`clientTools`。成功判据：新请求的模型工具列表不包含上一轮 Client Tool。若仍然出现，检查 Handler
是否确实使用 try-with-resources 关闭 Binding，或是否绕过了 `JiuwenCoreAgentExtHandler`。

## 9. 排障

| 现象 | 检查项 |
|---|---|
| 远端工具未出现 | Registry 是否已有 Card、Card 是否有 Skill、Agent 是否为 BaseAgent/DeepAgent |
| 工具描述不正确 | 检查远端 `skills[].description`，已安装同名工具不会自动刷新描述 |
| 模型不调用远端 | 检查 Card Skill 描述和 system prompt，参数必须包含 remoteInput 字符串 |
| Client Tool 未出现 | 是否使用 ext Handler，`params.metadata.clientTools` 是否为数组且 session 匹配 |
| 提示工具名冲突 | 修改 Client Tool、远端注册名或服务端工具名，不能用覆盖方式解决 |
| arguments 解析失败 | 非空 ToolCall arguments 必须是 JSON Object；空值会按 `{}` 处理 |
| 单工具无法恢复 | 使用第一轮实际 taskId/contextId，确认 Task 仍为 INPUT_REQUIRED |
| 多工具结果被拒绝 | 检查每个 TextPart 的 toolCallId，集合必须完整且无未知值 |
| 后续请求仍看到工具 | 确认是新 Task、未重复传 clientTools，并检查 Binding 是否关闭 |

## 10. 相关文档

- [AgentCore-ext 特性](../features/agentcore-ext.md)
- [Client Tools 特性](../features/client-tools.md)
- [配置参考](../configuration.md)
- [入口与数据契约](../entrypoints-and-contracts.md)
