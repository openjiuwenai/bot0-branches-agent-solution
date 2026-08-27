# AgentCore-ext 特性

## 1. 定位

AgentCore-ext 在基础 `JiuwenCoreAgentHandler` 之前安装扩展能力，同时复用父类的 AgentCore 输入映射、
Runner 调用、流式归一化、非流式聚合和会话管理。本文重点说明远程 A2A Agent 作为模型工具的能力；
请求级 Client Tools 见独立特性文档。

Maven 模块：

```xml
<dependency>
  <groupId>com.openjiuwen</groupId>
  <artifactId>agent-service-adapters-agentcore-ext</artifactId>
  <version>0.1.0</version>
</dependency>
```

## 2. Handler 构造

`JiuwenCoreAgentExtHandler` 接受 Agent 实例，并提供与基础 Handler 对齐的 Registrar 构造方式：

```java
new JiuwenCoreAgentExtHandler(agent)
new JiuwenCoreAgentExtHandler(agent, middlewareRegistrar)
new JiuwenCoreAgentExtHandler(agent, externalRegistrar)
new JiuwenCoreAgentExtHandler(agent, middlewareRegistrar, externalRegistrar)
```

字符串 Agent ID 会被明确拒绝。远程 A2A 工具需要访问并修改当前 Agent 实例，不能使用基础 Handler
的 resource ID 模式。

本特性涉及的 `AgentCoreExtAutoConfiguration` 只创建缺省 `RemoteA2aToolInstaller`，不会创建
`JiuwenCoreAgentExtHandler`。宿主必须注册自己的 `AgentHandler` Bean。相同 artifact 还导入可选的
SkillHub 自动配置，但不属于本文范围。`openjiuwen.service.handler=agentcore-ext` 仅能阻止基础
agentcore 缺省 Handler 条件命中，不能替代 Bean 注册。

## 3. 执行生命周期

每次 `query` 或 `streamQuery` 的顺序为：

```text
installBeforeRun
  -> RemoteA2aToolInstaller.install(agent)
  -> 可选的 SkillHubManager.register(agent)
  -> ClientToolRail.bind(agent, request)
  -> super.query / super.streamQuery
  -> ClientToolRail.Binding.close
```

远端工具安装是增量且幂等的：每个目标 BaseAgent 使用弱引用记录已安装的远端注册名；后续发现新 Card
时可以继续安装，不会重复注册同名远端工具。

Handler 的 start/stop 还会委托可选 SkillHubManager，但 SkillHub 的配置、下载和注册不属于本文范围。

## 4. 远端 Agent 来源

基础 Runtime 根据 `openjiuwen.service.a2a.remote-agents` 发现 Agent Card，并注册到
`A2ARemoteAgentCardRegistry`。AgentCore-ext 只读取 Registry，不直接发起 Card HTTP 请求，也不读取
YAML URL。

一个 Registry entry 只有同时满足以下条件才会成为工具：

- entry name 非空。
- Agent Card 已发现且非空。
- Card 至少声明一个 Skill。
- 目标 Agent 是 `BaseAgent`，或是能取得内部 BaseAgent 的 `DeepAgent`。

展示 Card name 不决定工具名。工具名、远端路由键和内部 remoteAgentId 都使用 Registry entry name，
也就是 `remote-agents[].name`。

## 5. 工具定义

工具描述按以下优先级生成：

1. 合并 Card 中所有非空 `skills[].description`，以换行分隔。
2. Card 的非空 description。
3. `Delegate this request to remote A2A agent '<name>'.`。

输入 Schema 固定要求 `remoteInput` 字符串，并允许额外属性：

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

模型能否正确选择远端工具主要取决于 Card Skill 描述，因此远端服务应提供具体、可区分的能力说明。

## 6. Interrupt Rail

模型调用注入工具时，`RemoteA2aInterruptRail`：

1. 根据当前 ToolCallInputs 或 ToolCall name 找到目标 spec。
2. 尝试解析 arguments JSON Object 的 `remoteInput`。
3. `remoteInput` 是非空字符串时作为远端 user message。
4. JSON 无效或字段缺失时回退为原始 arguments 字符串。
5. 产生 `context.agentName` 和 `_interrupt_kind=a2a_delegate` 的 interrupt。

当 AgentCore 恢复同一 ToolCall 且 resume input 非 `null` 时，Rail 返回 reject 决策，把输入作为工具
结果交给 AgentCore，不再次产生远端委派；空字符串同样属于已提供的恢复值。

## 7. 与 Runtime 编排的边界

AgentCore-ext 不负责：

- Agent Card 的网络发现和刷新。
- 选择远端流式或非流式协议。
- 发起远端 A2A 请求。
- 保存父 Task、shadow Task 或远端 Task ID。
- 聚合多个并行远端工具调用。
- 将客户端的续轮输入按 `toolCallId` 分配给 pending 成员。

这些工作由基础 Runtime 的 A2A Orchestrator 和远端批次协调器完成。Handler 只产生带真实
`toolCallId` 的 interrupt，并在 Runtime 准备好每个工具结果后一次性恢复 AgentCore。
Runtime 对实际流式下游建立直接委派边后，会把同一 `toolCallId` 写入
`Artifact.metadata.agentEvent` 的 `type=delegation` 事件，用于关联 ToolCall 与远端 Task；
非流式下游不产生该事件，普通 `output/status` 事件也不携带该字段。

单成员与多成员使用同一批次模型。多成员续轮必须使用每个成员真实的 `toolCallId`，不能依赖完成顺序
或把一条输入广播给所有成员。

## 8. 配置关系

AgentCore-ext 没有专属 `@ConfigurationProperties`。实际接入需要：

- 宿主显式创建 `JiuwenCoreAgentExtHandler` Bean。
- 本地 Agent 是 BaseAgent 或 DeepAgent 实例。
- `openjiuwen.service.a2a.remote-agents[]` 配置远端注册名、URL、超时和 streaming 偏好。
- 远端 Agent Card 至少包含一个 Skill，最好提供准确 description。
- 代理、容器或网关后部署时，远端服务正确设置公开 A2A URL。

完整字段见[配置参考](../configuration.md)。

## 9. 与 Client Tools 的关系

远端 A2A 工具和 Client Tools 都使用同一个 Handler，但来源和执行方不同：

| 对比项 | 远端 A2A 工具 | Client Tools |
|---|---|---|
| 工具来源 | 服务端 Registry 和 Agent Card | 每次请求的 `metadata.clientTools` |
| 执行方 | Runtime 调用远端 Agent | A2A 客户端本地执行 |
| 生命周期 | 按 Agent 实例增量安装 | 单次 Handler 调用 |
| interrupt kind | `a2a_delegate` | `client_tool` |
| 恢复结果 | Runtime 远端协调器生成 | 客户端续轮提供 |

两类工具不能使用相同名称。

## 10. 限制

- 字符串 Agent ID 模式不支持远端工具安装。
- 仅支持 BaseAgent 和 DeepAgent，其他类型会跳过安装。
- 工具名只校验非空，不修剪也不验证模型供应商的命名规则；配置方必须提供稳定、合法且唯一的注册名。
- Card 无 Skill 时即使有 description 也不会注入工具。
- 已安装记录按进程内 Agent 实例保存，不跨进程共享。
- Card 内容更新不会替换已经安装的同名工具描述；新增注册名可以增量安装。
- Rail 对缺失 `remoteInput` 的 arguments 使用原文回退，调用方不能把严格 Schema 校验责任完全交给 Rail。

## 11. 相关文档

- [配置参考](../configuration.md)
- [入口与数据契约](../entrypoints-and-contracts.md)
- [AgentCore 工具扩展指南](../guides/agentcore-ext-tools.md)
- [Client Tools 特性](client-tools.md)
