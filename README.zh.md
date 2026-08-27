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

### 一键构建（聚合 pom）

根目录的聚合 pom（`pom.xml`）可一条命令构建纳入管理的扩展与智能体 jar。它仅是构建包装，**不改变**任何模块的 parent 或依赖，各模块仍为独立对等工程。

```bash
mvn install                      # 构建全部 8 个受管 jar（默认）
mvn install -Pmechanism          # 仅构建 6 个机制特性 jar
mvn install -Pbusiness           # 仅构建 2 个业务特性 jar
mvn install -DskipTests          # 构建全部，跳过测试
```

受管 jar：`agent-service-adapters-agentcore-ext`、`agent-service-adapters-agentscope`、`agent-service-adapters-versatile`、`agent-service-app-custom-rest`、`agent-service-spec-ext`、`agent-core-ext-react-rails`（机制）；`edp-agent-engine`、`adapter-versatile-agent-java`（业务）。聚合 pom 本身不会被发布（`maven.deploy.skip=true`）。

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

---

欢迎体验 OpenJiuwen 智能体解决方案 v0.1.0！本版本围绕智能体服务的接入、运行与自主演进，交付智能体运行时、核心框架能力扩展、智能体自进化引擎与通用智能体四部分的端到端能力基座：运行时支持 Versatile 意图工作流路由、自定义 REST 服务入口、AgentScope 等异构框架智能体兼容接入与 SkillHub 订阅，核心框架为 ReActAgent 补全评估验证、重计划控制与故障降级等认知护栏能力，自进化引擎以「数据回流 → 轨迹评估 → 优化引擎」闭环基于智能体真实运行轨迹持续改进 Prompt 与 Skill，并面向金融等垂直行业 Java 技术栈交付涵盖推理、管控、协同、规划与治理完整能力的企业级通用智能体 EDPAgent，让智能体服务的接入更灵活、执行更可靠、进化更自主、落地更安心。

---

## 新特性

### 智能体运行时

本次发布为运行时带来四项新能力：Versatile 意图工作流路由、自定义 REST API 服务入口、AgentScope 等异构框架智能体兼容接入与 SkillHub 订阅，覆盖智能体服务接入与运行的常见场景：

- **Versatile 意图工作流路由**：按意图自动选择工作流服务地址实现路由分发，流式响应中的最终结果按结果节点自动抽取，无需关注节点细节。
- **自定义 REST API 服务入口**：既有 REST API 形态无需改造即可映射为标准智能体服务调用，同步 JSON 与 SSE 流式响应均可承载；当前版本单个运行时实例承载一个智能体、支持一个路径匹配规则。
- **异构智能体框架兼容接入**：AgentScope 等异构框架构建的智能体可直接包装接入运行时，统一获得查询、流式、失败与暂停的标准语义；消息中止、人工确认与外部工具等待三类暂停场景均可恢复续跑。
- **SkillHub 订阅**：智能体声明的 Skill 包在启动阶段自动下载，完整性校验保障包可信；关键 Skill 缺失时阻断就绪避免带病上线，可选 Skill 失败可降级启动，凭据信息脱敏不外泄。

### 核心框架能力扩展

本次发布为执行内核补全认知护栏能力，让 ReActAgent 的推理执行具备质量校验与风险防护：

- **ReActAgent 认知能力补全**：为 ReActAgent 新增三条认知护栏——按成功标准验证最终答案、限制重规划次数防止发散、设备故障时降级终止；纯 Java SDK 交付，不依赖 Spring 与运行时扩展。

### 智能体自进化引擎

本次发布交付自进化引擎「数据回流 → 轨迹评估 → 优化引擎」完整闭环与自进化 Agent，基于智能体真实运行轨迹完成质量判定，持续改进 Prompt 与 Skill，实现智能体自主演进：

- **数据回流**：从运行日志或 OpenTelemetry 链路数据中回流结构化轨迹，支持日志与标准链路两种模式，自动清洗归一为标准对话格式供评估使用。
- **轨迹评估**：指标评估与 LLM 评估双通道，覆盖精确率、关键词匹配、语义相似度与任务完成度、轨迹质量、安全性多维度，自动定位 Skill 与 Prompt 的优化点并输出可执行建议。
- **优化引擎**：基于评估结果执行 Skill 优化与 Prompt 优化，优化结果经业务智能体热更新验证后回写目标智能体生效。
- **自进化 Agent**：以智能体原生能力串联数据集导入、轨迹评估、策略优化与沙箱验证全流程，业务智能体自进化闭环开箱即用。

### 通用智能体

面向金融等垂直行业 Java 技术栈的企业级通用动态规划智能体 EDPAgent Java 首个正式版本，交付推理、管控、人机协同、工作流调用、数据直通、任务规划与话术治理等核心能力：

- **DeepAgent 推理机制**：基于「规划—执行—观察—反思」闭环架构替代传统单轮 ReAct，支持任务状态管理、动态路径调整、依赖自动解析和规划前置硬拦截。
- **拦截与管控**：多拦截器按优先级构成处理链，覆盖任务取消、状态维护、执行限制、工具调用、中断处理、日志记录、事件推送、话术渲染等全流程，形成全方位治理与安全管控体系。
- **ask_user 追问工具**：在关键决策点让用户参与确认以规避业务风险，支持中断持久化、丰富参数配置、强制场景约束和中断后自动恢复执行。
- **call_mcp 脚本调用工具**：通过 MCP SSE 服务调用脚本，提供安全隔离的 Python 脚本执行沙箱，支持主备自动切换、Token 鉴权、数据直通写入和调用次数限制。
- **Versatile 工作流调用**：将复杂业务流程委托给外部工作流服务执行，实现智能体与业务系统职责分离，支持 REST/A2A 双模式、中断续传、结果归一化和数据直通读取。
- **cancel_task 任务终止工具**：支持用户随时终止当前正在执行的业务流程。
- **工具间数据直通**：通过会话级键值存储实现工具间结构化数据直接传递，无需大模型转述，避免数据丢失和幻觉注入，支持多级作用域隔离和并发安全。
- **任务规划**：基于 Todo 状态机提供任务规划与生命周期管理，支持任务模板、状态更新、动态路径规则和跨轮次持久化，支撑复杂业务多步骤执行。
- **规则业务管控**：通过框架默认配置和场景配置两种模式，分层分级管控业务范围、工具白名单、调用次数、子任务数量、执行步数和合规，确保智能体在授权范围内安全执行。
- **思维链可视化**：通过帧控制和阶段话术配置实现思考过程可视化展示，支持真实流式和固定话术双模式，提升交互体验。
- **话术管理**：通过通用话术、场景话术、Skill 话术三级配置与变量替换、场景级覆盖机制，确保智能体输出一致、可控、合规。
- **隔离执行环境**：通过多层隔离机制保障智能体执行环境的安全性与稳定性，满足企业级部署要求。

---

# v0.1.1 Release Note

发布日期：2026 年 8 月 30 日

---

欢迎体验 OpenJiuwen 智能体解决方案 v0.1.1！本版本围绕智能体服务调用链路，交付智能体客户端、智能体总线、智能体运行时、核心框架能力扩展与智能体自进化引擎五部分的端到端标准化能力，完成交付物体积与依赖版本两项工程优化，并增强通用智能体 EDPAgent 的并行执行编排，让智能体服务的接入更简单、协作更顺畅、进化更自主、交付更顺滑。

---

## 新特性

### 智能体客户端

应用与智能体服务之间的调用入口，统一封装服务调用、本地工具协同与流式展示，屏蔽协议适配、断线重连等底层细节：

- **标准化服务调用**：统一 API 完成智能体调用的创建、查询与取消；链路中断后支持断点重连，基础设施故障自动熔断保护，长任务结果不丢失。
- **本地工具协同**：本地工具注册后可交由远端智能体驱动调用，默认不向服务端暴露；观察类操作自动执行、动作类操作经授权后执行，数据安全可控。
- **多路流式分流**：多个智能体协作时，交织到达的流式输出自动按来源分流渲染，每路内容都能对应到具体智能体，断线后可恢复现场继续展示。

### 智能体总线

承接客户端与智能体服务之间的调用与事件流转，网关、事件总线、注册发现中心三类组件均可独立部署、按需替换：

- **调用路由转发**：按智能体 ID 将客户端调用路由至目标运行时，统一承担认证鉴权与租户识别；阻塞、流式、查询与取消全类型调用均可转发，断线后可跨实例恢复任务。
- **总线事件流转**：调用与响应可经事件总线异步流转，客户端与智能体服务解耦；智能体之间的协作调用事件同样支持总线转发。
- **实例路由查询**：按智能体查询可用运行时实例，支持多实例候选与版本匹配；注册中心暂不可用时自动降级调用，业务不中断。

### 智能体运行时

本次发布为运行时带来六项新能力：对接 Versatile 控制器的意图转调、用户交互中断恢复、端侧工具调用响应、总线事件订阅、调用链追踪与任务并发限流，覆盖智能体服务从接入、交互到生产运行的全链路：

- **意图转调**：对接 Versatile 控制器，自动识别意图报文并调用目标智能体；控制器异常与退回信号自动区分处理，全程会话连续。
- **交互中断恢复**：智能体等待用户补充信息时任务挂起，客户端提交输入后从断点恢复；本地与远端智能体体验一致。
- **端侧工具响应**：智能体需要使用客户端本地工具时暂停并下发请求，客户端提交结果后自动恢复执行。
- **总线事件订阅**：运行时嵌入即可订阅消费总线事件，无需额外 sidecar 组件。
- **调用链追踪**：跨平台调用自动携带统一追踪标识，调用链路端到端可查；轨迹数据支持 OpenTelemetry 标准上报，配置即用、默认关闭。
- **并发与限流**：支持配置最大并发任务数，超载自动拒绝新任务，保障已运行任务的服务质量。

### 核心框架能力扩展

本次发布为执行内核新增两项协同能力：智能体感知与任务匹配让智能体之间互相发现、按任务委托协作，端侧工具动态装配让智能体按需调用客户端本地工具：

- **智能体感知与任务匹配**：智能体可自动感知平台上的其他智能体，按任务语义精准匹配并发起委托调用；复杂请求先拆解再逐任务执行。
- **端侧工具动态装配**：按任务动态装配工具可见面，智能体选定工具后移交运行时执行；工具不缓存、任务间不共享。

### 智能体自进化引擎

本次发布为自进化引擎带来四项新能力：轨迹增强、Agent 评估器、GEPA 优化算法适配与北向 SkillHub 对接，在「数据回流 → 轨迹评估 → 优化引擎」闭环基础上，进一步覆盖动态规划场景下的轨迹归因与 Skill 版本离线迭代：

- **轨迹增强**：动态规划场景下，支持轨迹 span 节点与 skill / agent.md 内联映射，实现轨迹与 Skill / AgentRule 的细粒度对应，为归因与优化提供更精确的数据。
- **Agent 评估器**：支持 Agent as a Judge 评估器，具备路径识别与链路归因能力，可对轨迹的执行路径进行判定与归因分析。
- **Prompt 优化器**：支持基于评估反馈自动迭代优化提示词，支持 SkillOpt 算法；本版本新增适配 GEPA 优化算法。
- **北向对接 SkillHub**：支持通过 SkillHub 对接实现 Skill 版本的离线更新迭代，便于完成 Skill 版本管理与迭代。

### 工程与兼容性优化

面向企业级交付环境的两项工程优化，交付物体积与依赖版本全面适配企业流水线：

- **交付物体积优化**：EDPAgent 的交付物Jar包体积优化至企业流水线 200MB 限额以内，部署不再受阻。
- **开源依赖版本统一**：agent-core 与 agent-runtime 开源依赖版本对齐，消除模块间依赖不一致。

### 通用智能体

面向企业场景的通用动态规划智能体 EDPAgent Java，本版本聚焦执行编排，复杂任务由多个子智能体并行完成：

- **规划工作流与子智能体并行执行**：主智能体一次规划、同轮并行发起多个子智能体执行，结果全部返回后一次性汇总推理，多子任务场景告别串行等待。