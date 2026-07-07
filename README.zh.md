# openJiuwen agent-solution

[中文版](README.zh.md) | [English Version](README.md)

## 简介

**openJiuwen agent-solution** 是 openJiuwen 面向 Agent 应用集成场景以及行业通用场景的扩展方案仓库。

当前版本聚焦在 `agent-runtime-java` 之上的运行时扩展 adapter，以及可直接参考的 Agent 编排示例。本仓不重复实现 HTTP 接入、A2A 协议、远端 card 发现与通信、会话编排等运行时能力；这些能力由 `agent-runtime-java` 提供。Agent 执行内核由 `agent-core-java` 提供。本仓主要补齐运行时扩展、远端工作流适配和示例接线方式。

## 快速开始

### 环境要求

- **Java 版本**：JDK 17+
- **构建工具**：Maven 3.9+
- **运行时依赖**：`com.openjiuwen:agent-runtime-java:0.1.0`
- **执行内核依赖**：`com.openjiuwen:agent-core-java:0.1.12`

### 构建扩展模块

```powershell
mvn -f common\agent-runtime-ext-java\pom.xml clean install
```

### 构建示例工程

```powershell
mvn -f common\example\versatile-a2a-adapter-demo\pom.xml clean install
mvn -f common\example\agentcore-ext-remote-a2a-tool-demo\pom.xml clean install
mvn -f common\example\agentcore-ext-deepagent-remote-a2a-demo\pom.xml clean install
mvn -f common\example\multi-deep-research-demo\pom.xml clean install
```

## 架构设计

本仓位于 `agent-runtime-java` 与具体业务 Agent 应用之间，主要提供运行时扩展 adapter 与示例接线工程。

| 模块 | 说明 |
|------|------|
| `common/agent-runtime-ext-java` | 运行时扩展 Maven 父工程，当前包含 AgentCore 增强 adapter 与 Versatile adapter。 |
| `agent-service-adapters-agentcore-ext` | 复用 runtime 远端 A2A card 注册结果，在 AgentCore handler 执行前注入远端 agent 工具，并通过 `a2a_delegate` interrupt 完成远端委托。 |
| `agent-service-adapters-versatile` | 实现 runtime `AgentHandler` SPI，将查询请求适配到远端 HTTP/SSE 工作流服务。 |
| `common/example` | 配套示例工程，用于演示扩展 adapter、A2A 暴露、远端委托和 runtime 接线方式。 |

更细的设计说明见：

- [agent-service-adapters-agentcore-ext-design.md](common/agent-runtime-ext-java/doc/agent-service-adapters-agentcore-ext-design.md)
- [agent-service-adapters-versatile-design.md](common/agent-runtime-ext-java/doc/agent-service-adapters-versatile-design.md)

## 功能特性

- **AgentCore 远端 A2A 工具注入**：基于 runtime 已发现的远端 agent card，将远端 agent 安装为 AgentCore 可见工具。
- **中断机制**：把远端工具调用转换成可由 runtime 接管的委托中断，并支持 resume 后将远端结果注回 AgentCore。
- **Versatile HTTP/SSE 适配**：将 runtime 查询请求转换为远端工作流服务调用，并消费 SSE 或行流式响应。

## 项目结构

```text
agent-solution
|-- common
|   |-- agent-runtime-ext-java
|   |   `-- agent-service-adapters
|   |       |-- agent-service-adapters-agentcore-ext
|   |       `-- agent-service-adapters-versatile
|   `-- example
|       |-- agentcore-ext-deepagent-remote-a2a-demo
|       |-- agentcore-ext-remote-a2a-tool-demo
|       |-- multi-deep-research-demo
|       `-- versatile-a2a-adapter-demo
|-- LICENSE
`-- README.md
```

## 示例目录

```text
common/example
|-- agentcore-ext-deepagent-remote-a2a-demo
|-- agentcore-ext-remote-a2a-tool-demo
|-- multi-deep-research-demo
`-- versatile-a2a-adapter-demo
```

## Maven 坐标

```xml
<dependency>
    <groupId>com.openjiuwen</groupId>
    <artifactId>agent-service-adapters-agentcore-ext</artifactId>
    <version>0.1.0</version>
</dependency>

<dependency>
    <groupId>com.openjiuwen</groupId>
    <artifactId>agent-service-adapters-versatile</artifactId>
    <version>0.1.0</version>
</dependency>
```

## 参与贡献

欢迎通过 Issue、Pull Request 或设计讨论参与 openJiuwen agent-solution 的演进。提交贡献前请阅读 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 开源许可证

本项目依据 [Apache License 2.0](LICENSE) 授权。
