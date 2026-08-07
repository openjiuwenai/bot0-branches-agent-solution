# openJiuwen agent-solution

[中文版](README.zh.md) | [English Version](README.md)

## 简介

**openJiuwen agent-solution** 是 openJiuwen 面向 Agent 应用集成场景以及行业通用场景的扩展方案仓库。

当前版本包含三个相互独立的扩展工程：运行时 adapter、纯 AgentCore SDK 扩展，以及具体 Agent 实现。本仓不重复实现 HTTP 接入、A2A 协议、远端 card 发现与通信、会话编排等运行时能力；这些能力由 `agent-runtime-java` 提供，Agent 执行内核由 `agent-core-java` 提供。

## 快速开始

### 环境要求

- **Java 版本**：JDK 17+
- **构建工具**：Maven 3.9+
- **运行时依赖**：`com.openjiuwen:agent-runtime-java:0.1.1.post1`
- **执行内核依赖**：`com.openjiuwen:agent-core-java:0.1.14.post1`

### 构建扩展模块

```powershell
mvn -f common\agent-core-ext-java\pom.xml clean install
mvn -f common\agents\pom.xml clean install
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

`common` 下的三个扩展工程相互平行并分别构建，彼此之间不存在 Maven parent 或 reactor 聚合关系。

| 模块 | 说明 |
|------|------|
| `common/agent-runtime-ext-java` | 运行时扩展 Maven 父工程，当前包含 AgentCore 增强 adapter 与 Versatile adapter。 |
| `common/agent-core-ext-java` | `agent-core-java` 的纯 SDK 扩展工程，当前聚合不含 Spring 的 `agent-core-ext-react-rails` 特性 jar。 |
| `common/agents` | 具体 Agent 实现工程，当前聚合自包含的 PEV Agent。 |
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
- **ReAct 认知 Rails**：通过显式 Java 注册提供验证、重规划和故障降级，不包含框架自动接线。
- **PEV Agent**：基于 `agent-core-java` 的 Plan-Execute-Verify-Diagnose-Dispatch 自包含实现。

## 项目结构

```text
agent-solution
|-- common
|   |-- agent-core-ext-java
|   |   `-- agent-core-ext-react-rails
|   |-- agent-runtime-ext-java
|   |   `-- agent-service-adapters
|   |       |-- agent-service-adapters-agentcore-ext
|   |       `-- agent-service-adapters-versatile
|   |-- agents
|   |   `-- pev
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
|-- agent-gateway-demo
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

<dependency>
    <groupId>com.openjiuwen</groupId>
    <artifactId>agent-core-ext-react-rails</artifactId>
    <version>0.1.0</version>
</dependency>

<dependency>
    <groupId>com.openjiuwen</groupId>
    <artifactId>pev</artifactId>
    <version>0.1.0</version>
</dependency>
```

## 参与贡献

欢迎通过 Issue、Pull Request 或设计讨论参与 openJiuwen agent-solution 的演进。提交贡献前请阅读 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 开源许可证

本项目依据 [Apache License 2.0](LICENSE) 授权。

---

# v0.1.0 Release Note

发布日期：2026 年 7 月 30 日

- **能力扩展**（openJiuwen agent-solution）覆盖运行时扩展、核心框架扩展与自进化引擎三部分：运行时扩展支持 Versatile 意图工作流路由、自定义 RESTful API 服务入口、AgentScope 等异构框架智能体兼容接入以及 SkillHub 订阅；核心框架扩展为 ReActAgent 补全评估验证、重计划控制、故障降级等认知 Rail 能力；自进化引擎提供「数据回流 → 轨迹评估 → 优化引擎」闭环，基于 Agent 真实运行轨迹完成质量判定并持续改进 Prompt 与 Skill，实现 Agent 自主演进；
- **通用智能体**（EDPAgent Java）面向金融等垂直行业的 Java 技术栈需求，在 OpenJiuwen DeepAgent 之上交付企业级通用智能体与配套治理能力，涵盖 DeepAgent 推理机制、拦截管控、人机协同工具、工作流调用、数据直通、任务规划、规则管控、思维链可视化、话术管理与隔离执行环境，满足安全性、可控性、可观测性的严格要求；

## 新特性

### 一、能力扩展

本板块包含运行时扩展、核心框架扩展与自进化引擎三部分。运行时扩展以 runtime 的 `AgentHandler` SPI、`A2ARemoteAgentCardRegistry` 与 Spring Boot 自动装配为接入点，运行时能力由 `agent-runtime-java` 提供，执行内核由 `agent-core-java` 提供；核心框架扩展为 ReActAgent 补全评估验证、重计划控制、故障降级等认知 Rail 能力；自进化引擎基于 Agent 真实运行轨迹完成质量判定与持续优化。

**1. 运行时扩展-Versatile 意图工作流适配**：支持按 intent 选择 endpoints URL 模板实现基于意图的路由分发，并支持 SSE 响应 `result-node-name` 最小结果节点抽取，在匹配 `node_name` 且 `node_type` 为 `"End"` 时提取最终结果。

**2. 运行时扩展-自定义 REST API 服务入口**：在标准 Agent 服务语义之上提供自定义 REST edge adapter，通过 `RestRequestMapper` / `RestResponseMapper` SPI 将既有 REST API 形态映射为标准 Agent 服务调用，支持同步 JSON 与 SSE 流式响应。当前版本一个 runtime 实例仅 host 一个 Agent、允许一个 REST path pattern。

**3. 运行时扩展-异构智能体框架兼容扩展**：在 `agentcore-ext` 与 `Versatile` adapter 基础上扩展兼容 AgentScope 框架，支持包装本地 `ReActAgent` 和 `HarnessAgent`，映射为 runtime 的 query、stream、失败和暂停语义，并验证了 message stop、人工确认和单个 external pending tool 三类暂停恢复。

**4. 运行时扩展-Skill Hub 订阅 Skill**：通过可替换的 Skill Hub SPI 在启动阶段按 required / optional 语义下载 Agent 声明的 skill 包，支持 SHA-256 或常规完整性校验；required skill 失败阻断 ready，optional skill 失败可降级启动，凭据脱敏不外泄。

**5. 核心框架扩展-ReActAgent 认知能力补全（agent-core-ext-react-rails）**：新增 `agent-core-ext-react-rails` 模块，为 ReActAgent 补三条认知 rail：`CriteriaVerificationRail`（按成功标准验证最终答案）、`ReplanRail`（限制重规划次数防发散）、`RootCauseRail`（设备故障降级终止），均通过 `forceFinish` gate 在 `afterModelCall` 短路循环。纯 Java SDK，不依赖 Spring 或 runtime-ext。

**6. 自进化引擎-数据回流**：从 Agent 运行日志或 OpenTelemetry 数据中回流结构化轨迹，支持 log 与 standard（OTel）两种模式，经清洗归一为标准对话格式供评估使用。

**7. 自进化引擎-轨迹评估**：提供指标评估器（F1、精确率、关键词匹配、语义相似度）与 LLM 评估器（任务完成度、轨迹质量、安全性多维评分），定位 Skill / Prompt 优化点并输出可执行建议。

**8. 自进化引擎-优化引擎**：基于评估结果执行 Skill 优化（反思→聚合→选择→应用，支持 SkillOpt/TF-GRPO）与 Prompt 优化（自动迭代并通过业务 Agent 热更新验证），结果回写目标 Agent。

**9. 自进化引擎-自进化 Agent**：通过智能体原生能力串联数据集导入 → 轨迹评估 → 策略优化 → 沙箱 Rollout 验证全流程，实现业务 Agent 自进化闭环。

### 二、通用智能体

EDPAgent Java v0.1.0 是 EDPAgent（企业级通用动态规划智能体）的首个 Java 正式版本，以下为本次发布的核心能力。

**1. ReAct 机制升级为 DeepAgent 机制**：基于 DeepAgent 推理循环范式实现"规划—执行—观察—反思"闭环架构，替代传统单轮 ReAct，支持任务状态管理、动态路径调整、依赖自动解析和规划前置硬拦截。

**2. 拦截与管控机制**：通过多拦截器按优先级构成处理链，覆盖任务取消、状态维护、执行限制、工具调用、中断处理、日志记录、事件推送、话术渲染等全流程，形成全方位治理与安全管控体系。

**3. ask_user（追问用户信息）工具**：在关键决策点让用户参与确认以规避业务风险，支持中断持久化、丰富参数配置、强制场景约束和中断后自动恢复执行。

**4. call_mcp（通用脚本调用）工具**：通过 MCP SSE 服务调用脚本，提供安全隔离的 Python 脚本执行沙箱，支持主备自动切换、Token 鉴权、数据直通写入和调用次数限制。

**5. Versatile 工作流调用**：将复杂业务流程委托给外部工作流服务执行，实现 Agent 与业务系统职责分离，支持 REST/A2A 双模式、中断续传、结果归一化和数据直通读取。

**6. cancel_task（终止当前任务）工具**：支持用户随时终止当前正在执行的业务流程。

**7. 工具之间数据通道直通**：通过会话级键值存储实现工具间结构化数据直接传递，无需 LLM 转述，避免数据丢失和幻觉注入，支持多级作用域隔离和并发安全。

**8. 任务规划**：基于 Todo 状态机提供任务规划与生命周期管理，支持任务模板、状态更新、动态路径规则和跨轮次持久化，支撑复杂业务多步骤执行。

**9. 规则业务管控**：通过框架默认配置和场景配置两种模式，分层分级管控业务范围、工具白名单、调用次数、子任务数量、执行步数和合规，确保 Agent 在授权范围内安全执行。

**10. 思维链**：通过帧控制和阶段话术配置实现 Agent 思考过程可视化展示，支持真实流式和固定话术双模式，提升交互体验。

**11. 话术管理**：通过通用话术、场景话术、Skill 话术三级配置与变量替换、场景级覆盖机制，确保 Agent 输出一致、可控、合规。

**12. 隔离执行环境**：通过多层隔离机制保障 Agent 执行环境的安全性与稳定性，满足企业级部署要求。

---