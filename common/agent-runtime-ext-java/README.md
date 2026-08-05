# Agent Runtime Ext Java

`agent-runtime-ext-java` 为 OpenJiuwen Agent Runtime Java 提供协议入口和 Agent 框架扩展。本目录当前重点文档化六项能力：Custom REST、Versatile Adapter、AgentScope Adapter、AgentCore-ext 远端 A2A 工具、Client Tools 和 Agent Bus Consumer。除这六项外，`agent-service-adapters-agentcore-ext` 同一 artifact 还附带一项可选能力：SkillHub 中间件——Agent 启动阶段从外部 Skill Hub 下载、校验并注册 skill，默认不启用，详见 [skillhub-runtime-demo](../example/skillhub-runtime-demo/README.md)。

这些模块建立在基础 Runtime 的 `AgentHandler`、Query 和 A2A 能力之上，不是独立运行的完整应用。接入前应先准备一个能够启动基础 Runtime 的 Spring Boot 宿主应用。

## 1. 如何选择

| 需求 | 选择 | Maven 模块 | 接入方式 |
| --- | --- | --- | --- |
| 暴露宿主自定义 JSON REST 协议 | Custom REST | `agent-service-app-custom-rest` | 实现 `CustomRestProtocolAdapter`，配置请求路径 |
| 调用独立部署的 Versatile HTTP/SSE 工作流 | Versatile Adapter | `agent-service-adapters-versatile` | 配置 `VersatileProperties`，注册 `VersatileAgentHandler` |
| 在当前 JVM 中运行 AgentScope Agent | AgentScope Adapter | `agent-service-adapters-agentscope` | 构造 `ReActAgent` 或 `HarnessAgent`，注册 `AgentScopeAgentHandler` |
| 把远端 A2A Agent 安装为 AgentCore 模型工具 | AgentCore-ext | `agent-service-adapters-agentcore-ext` | 使用实例型 Agent，注册 `JiuwenCoreAgentExtHandler`，配置远端 Agent |
| 由 A2A 客户端随请求声明并执行本地工具 | Client Tools | `agent-service-adapters-agentcore-ext` | 使用 ext Handler，在 `params.metadata.clientTools` 声明工具 |
| 让 Runtime 订阅并处理 Agent Bus 请求事件 | Agent Bus Consumer | `agent-service-bus-consumer` | 引入模块，启用 consumer 和 agent-bus runtime role |

Custom REST 和 Agent Bus Consumer 是入站协议扩展；Versatile 是出站 HTTP Adapter；AgentScope 是进程内 Agent Adapter；AgentCore-ext 和 Client Tools 是 AgentCore 执行链上的工具扩展。它们解决的问题不同，不应仅按模块名称互相替代。

## 2. Maven 模块

| 模块 | 说明 |
| --- | --- |
| `agent-runtime-ext-java` | 聚合 POM，Java 版本为 17 |
| `agent-service-app/agent-service-app-custom-rest` | 自定义 REST Controller、协议 SPI、A2A Bridge 和 SSE 传输 |
| `agent-service-adapters/agent-service-adapters-versatile` | Versatile 请求构造、HTTP/SSE 调用和结果映射 |
| `agent-service-adapters/agent-service-adapters-agentscope` | AgentScope ReAct/Harness Handler、事件与恢复映射 |
| `agent-service-adapters/agent-service-adapters-agentcore-ext` | AgentCore 扩展 Handler、远端 A2A 工具、Client Tools；同模块还包含已实现的 SkillHub 中间件（可选能力，需显式启用） |
| `../agent-service-spec-ext`                                  | 扩展公共 SPI；当前主要服务于 SkillHub（`SkillHubProvider`、`SkillHubConfig`、`SkillHubException` 等） |
| `agent-service-bus-consumer` | Runtime 侧 Agent Bus 事件订阅、A2A 请求桥接、Task 状态投影和响应事件发布 |

业务应用通常只引入所需的功能模块，不需要依赖聚合 POM。例如：

```xml
<dependency>
  <groupId>com.openjiuwen</groupId>
  <artifactId>agent-service-app-custom-rest</artifactId>
  <version>0.1.0</version>
</dependency>
```

## 3. 阅读路径

先根据任务选择使用指南，再在需要确认机制、配置或协议时查阅对应参考。

### 使用指南

- [接入 Custom REST](doc/guides/custom-rest-integration.md)
- [接入 Versatile 或 AgentScope](doc/guides/external-agent-runtime-integration.md)
- [使用 AgentCore-ext 远端工具和 Client Tools](doc/guides/agentcore-ext-tools.md)
- [接入 Agent Bus Consumer](doc/guides/agent-bus-consumer-integration.md)

### 特性说明

- [Custom REST](doc/features/custom-rest.md)
- [Versatile Adapter](doc/features/versatile-adapter.md)
- [AgentScope Adapter](doc/features/agentscope-adapter.md)
- [AgentCore-ext](doc/features/agentcore-ext.md)
- [Client Tools](doc/features/client-tools.md)
- [Agent Bus Consumer](doc/features/agent-bus-consumer.md)

### 公共参考

- [完整配置参考](doc/configuration.md)
- [入口与数据契约](doc/entrypoints-and-contracts.md)
- [文档建设与核对计划](doc/documentation-plan.md)

## 4. 共同边界

- Versatile 自动配置只绑定属性，不创建 `VersatileAgentHandler`。
- AgentScope 没有自动配置和专属 YAML，宿主必须创建 Agent 和 Handler。
- 本轮覆盖的 `AgentCoreExtAutoConfiguration` 只提供远端工具安装器，不创建
  `JiuwenCoreAgentExtHandler`；同一 artifact 还包含已实现的可选 SkillHub 中间件自动配置，默认不启用，仅在 `openjiuwen.service.middleware.skillhub.enabled=true` 且容器中存在 `SkillHubProvider` 时激活。
- Client Tools 只在 `JiuwenCoreAgentExtHandler` 的单次调用期间生效，不是服务端持久工具注册。
- Custom REST 的业务请求和响应 schema 由宿主 Adapter 定义；框架只固定传输、A2A Bridge、任务续接和错误边界。
- Agent Bus Consumer 复用基础 Runtime 的 A2A 控制面和业务 `AgentHandler`，不创建独立 Agent，
  也不要求业务代码实现 Broker 订阅。
- Agent Bus Consumer 的必要配置和边界见其[接入指南](doc/guides/agent-bus-consumer-integration.md)
  和[特性说明](doc/features/agent-bus-consumer.md)；其他扩展模块的完整 YAML 字段以
  [配置参考](doc/configuration.md)为准，入口和请求/返回格式以
  [入口与数据契约](doc/entrypoints-and-contracts.md)为准。
