# OpenJiuwen 智能体解决方案 v0.1.0 Release Note

发布日期：2026 年 7 月 30 日

---

欢迎体验 OpenJiuwen 智能体解决方案 v0.1.0！本次发布涵盖**平台能力**、**Agent 引擎**与**EvoAgent 自进化引擎**三大板块：

- **平台能力**（openJiuwen agent-solution）提供异构智能体框架兼容、标准化客户端调用、本地工具注册与驱动，以及自定义 REST API 服务入口等核心能力；
- **Agent 引擎**（EDPAgent Java）面向金融等垂直行业的 Java 技术栈需求，在 OpenJiuwen DeepAgent 之上交付企业级 Agent 引擎与配套治理能力，满足安全性、可控性、可观测性的严格要求；
- **EvoAgent 自进化引擎**提供「数据回流 → 轨迹评估 → 优化引擎」闭环能力，基于 Agent 真实运行轨迹完成质量判定，并通过优化引擎持续改进 Prompt 与 Skill，实现 Agent 自主演进。

---

## 新特性

### 一、平台能力

扩展以 runtime 的 `AgentHandler` SPI、`A2ARemoteAgentCardRegistry` 与 Spring Boot 自动装配为接入点；HTTP 接入、A2A 协议、远端 card 发现与通信、会话编排等运行时能力由 agent-runtime-java 提供。执行内核由 agent-core-java 提供。客户端调用与本地工具治理由 agent-client 提供。

#### 1. Versatile 意图工作流适配

支持 Versatile 和 Versatile 意图工作流适配，支持按 intent 选择 endpoints URL 模板实现基于意图的路由分发，并增强 SSE 响应最小结果节点抽取。SSE 响应解析支持 `result-node-name` 最小结果节点抽取，当匹配 `node_name` 且 `node_type` 为 `"End"` 时提取最终结果。

#### 2. 自定义 REST API 服务入口

runtime 在标准 Agent 服务语义之上提供自定义 REST edge adapter，使调用方以业务 REST/SSE 形态访问同一个 hosted Agent：

- **自定义 REST 服务入口**：允许用户通过自定义扩展方式把既有 REST API 形态映射为标准 Agent 服务调用。
- **请求映射 SPI**：用户自定义 `RestRequestMapper` 与 `RestResponseMapper`，负责请求归一和响应投影。
- **同步/流式消息调用**：支持同步 JSON 响应和 SSE 流式响应。
- **A2A 语义归一**：每次提交归一为标准 Agent 服务调用，Task、错误和租户语义归一到标准化 Agent 服务入口。
- **单入口单路径**：当前版本一个 runtime 实例只 host 一个 Agent，只允许一个 REST path pattern。

#### 3. 异构智能体框架兼容扩展

在 `agentcore-ext` 与 `Versatile` adapter 基础上，扩展异构框架兼容范围，支持扩展兼容 AgentScope 框架智能体：

- 支持包装宿主已构建的本地 `ReActAgent`，把 `Mono<Msg>` / `Flux<AgentEvent>` 映射为 runtime query、stream、失败和暂停语义。
- 支持包装宿主已构建的本地 `HarnessAgent`，通过公开 API 调用和状态读取，对上保持与 ReAct 相同的 runtime 协议。
- 已验证 message stop、人工确认和单个 external pending tool 三类暂停恢复。

#### 4. Skill Hub 订阅 Skill

新增支持 Skill Hub 订阅 Skill，默认支持 OpenJiuwen Skill Hub，可通过 SPI 扩展支持自建 Skill Hub：

- runtime 在部署或启动阶段读取 Agent skill 选择配置（区分 required / optional 语义）。
- 通过可替换的 Skill Hub SPI 访问 Skill Hub，下载 Agent 声明需要的 skill 包。
- 支持下载完整性校验：Skill Hub 支持摘要时校验 SHA-256，不支持时执行文件非空、可完整读取、必要文件存在等常规校验。
- required skill 的配置/认证/授权/查找或启动阶段移交失败时阻断 Agent ready；下载或完整性校验失败时允许降级进入 ready，并在请求链路外重试。
- optional skill 获取失败时可跳过并继续启动，输出脱敏诊断。
- 凭据与敏感信息不写入日志、错误响应或遥测数据。

#### 5. 标准化智能体客户端调用

面向业务应用提供标准 client facade，在创建调用时声明调用模式并回显调用关联与任务状态投影：

- **三种调用模式**：`BLOCKING`（一次性响应窗口）、`STREAMING`（全链路流式能力）、`ASYNC`（提交后异步观察）。
- **Conversation 传递**：支持业务应用传入或委托生成 `conversationId`，conversation 主权归业务应用。
- **Invocation 回显**：每次调用回显 `conversationId`、`invocationRef`、幂等键、调用模式、状态投影和恢复线索，不要求业务应用持有 `taskId`。
- **状态观察**：支持基于 `invocationRef` 查询、重订阅、取消、继续等待输入和 UNKNOWN 恢复。
- **幂等与重试**：创建类调用和继续等待输入类调用均具备独立幂等语义，重试不造成重复副作用。
- **错误分类**：区分网络错误、路由错误、服务端错误、业务失败、取消、拒绝、接受未知和流式能力不可用。

#### 6. 客户端本地工具注册与调用

新增本地工具的标准化 SPI 与注册管理，支持被远端智能体驱动调用：

- **本地工具 SPI 注册**：业务应用在集成开发阶段通过 SPI 注册本地工具描述与 handler，包括稳定 `toolId`、名称、描述、输入输出 schema、授权策略和审计策略。
- **Observation / Action 分类**：Observation 偏只读观测（自动执行），Action 产生副作用（需授权审批）。
- **默认不暴露**：未经业务应用显式声明，client 不向服务端暴露任何本地工具。
- **ToolExposurePolicy**：支持 conversation 级和 invocation 级可暴露工具范围声明，invocation 级策略可收窄或覆盖 conversation 级。
- **ToolView 生成与上报**：随真实 invocation 请求附上当前 ToolView，由本地工具目录、暴露策略和工具可用性共同计算。
- **远端驱动调用**：服务端只能通过受治理消息请求 ToolView 中可见的客户端工具，不能直接访问客户端本地资源。
- **结果提交**：工具执行结果经 Gateway 主动提交给 runtime，作为客户端内部恢复请求。

#### 7. 运行时端侧工具响应

新增支持带有端侧工具的请求处理，runtime 在 Agent 执行需要客户端本地工具时挂起当前 Task，通过响应投影工具请求，并在 client 提交工具 outcome 后校验恢复关系继续原 Task：

- **ToolView 承接与 Task 级绑定**：runtime 从标准 client invocation 中接收当前 ToolView，绑定到当前 Task 执行上下文。
- **客户端工具调用挂起**：Agent 执行中产生客户端本地工具调用时，runtime 挂起当前 Task，通过本次调用响应返回工具请求投影。
- **非完成状态**：等待客户端工具结果时，Task 不得被标记为 completed。
- **continuation 恢复**：client 按标准 continuation invocation 提交工具结果，runtime 校验关联后恢复原 Task。
- **客户端异常透传**：工具未声明、权限不足、参数非法、执行失败或超时等 outcome 作为工具结果输入恢复执行链路。

#### 8. ReActAgent 认知能力补全（react-rails）

新增 `react-rails` 模块，为 agent-core-java 的 ReActAgent 补三条认知 rail，解决原生 ReActAgent 只有 reason+act 循环（无 verify、无 replan 意识、工具失败不降级）的 capability gap：

- **CriteriaVerificationRail（external-judge gate）**：`afterModelCall` 检测最终答案，调用 `CriteriaVerifier.verify()` 按成功标准验证，PASS 时 `forceFinish(verified=true)`，FAIL 时 `forceFinish(degraded=true, unmet=[...])`；默认提供基于规则的确定性验证器（零 LLM）。
- **ReplanRail（replan 计数/超限 escalate）**：`afterModelCall` 检测 `__replan__` tool_call 并计数，超过 maxReplan 时 `forceFinish(degraded)`，防止 LLM 反复换策略不收敛。
- **RootCauseRail（device-failure degrade）**：`onToolException` 标记 pendingDegrade，下一轮 `afterModelCall` 触发 `forceFinish(degraded)`，设备故障重试无效时诚实降级终止。
- **forceFinish gate 承重**：三条 rail 均通过 `requestForceFinish(Map)` 在 `afterModelCall` 钩子短路 ReActAgent 循环（字节码 offset 225/700 实证）。

纯 Java SDK，不依赖 Spring 或 runtime-ext；由应用显式注册 rail 与工具。

### 二、Agent 引擎

EDPAgent Java v0.1.0 是 EDPAgent（企业级通用动态规划智能体）的首个 Java 正式版本。继 Python 版本成功发布后，本版本面向金融等垂直行业的 Java 技术栈需求，在 OpenJiuwen DeepAgent 之上交付企业级 Agent 引擎与配套治理能力，实现了企业级 Agent 所需的核心能力，满足金融行业对安全性、可控性、可观测性的严格要求。

#### 1. ReAct 机制升级为 DeepAgent 机制

基于 DeepAgent 推理循环范式，实现"规划—执行—观察—反思"的闭环智能体架构，替代传统单轮 ReAct 模式，支持任务状态管理、动态路径调整、依赖关系自动解析和规划前置硬拦截。

#### 2. 拦截与管控机制

通过多个拦截器构成处理链，按优先级顺序执行，形成全方位的行为治理与安全管控体系，覆盖任务取消、状态维护、执行限制、工具调用、中断处理、日志记录、事件推送、话术渲染等全流程。

#### 3. ask_user（追问用户信息）工具

人机协同关键机制，在关键决策点让用户参与确认，避免 Agent 自行猜测导致业务风险，支持中断持久化、丰富参数配置、强制场景约束和中断后自动恢复执行。

#### 4. call_mcp（通用脚本调用）工具

通过 MCP SSE 服务调用脚本，提供安全隔离的 Python 脚本执行沙箱环境，支持主备自动切换、Token 鉴权、数据直通自动写入和调用次数限制。

#### 5. Versatile 工作流调用

将复杂业务流程委托给外部工作流服务执行，实现 Agent 与业务系统的职责分离，支持 REST/A2A 双调用模式、中断续传、结果归一化和数据直通读取。

#### 6. cancel_task（终止当前任务）工具

提供任务取消能力，支持用户在任何时候终止当前正在执行的业务流程。

#### 7. 工具之间数据通道直通

通过会话级键值存储机制，实现工具间结构化数据的直接传递，无需依赖 LLM 转述，避免数据丢失、格式错误或幻觉注入，支持多级作用域隔离和并发安全。

#### 8. 任务规划

基于 Todo 状态机的完整任务规划与生命周期管理能力，支撑复杂业务流程的多步骤执行，支持任务模板、状态更新、动态路径规则和跨轮次持久化。

#### 9. 规则业务管控

多层治理机制约束 Agent 行为边界，确保 Agent 在授权范围内安全、可控地执行业务，满足金融行业合规要求，支持框架默认配置和场景配置两种模式，分层分级对业务范围控制、工具白名单、调用次数限制、子任务数量限制、执行步数限制和合规进行把关。

#### 10. 思维链

实现 Agent 思考过程的可视化展示，通过精细化的帧控制和阶段话术配置，为用户提供流畅、自然的思考过程反馈，提升交互体验，支持真实流式和固定话术双展示模式。

#### 11. 话术管理

统一话术配置、变量替换和场景级覆盖机制，通过分层话术体系确保 Agent 输出的话术一致、可控、合规，支持通用话术、场景话术、Skill 话术三级配置和合规出口约束。

#### 12. 隔离执行环境

通过多层隔离机制保障 Agent 执行环境的安全性与稳定性，满足企业级部署要求。

### 三、EvoAgent 自进化引擎

EvoAgent v0.1.0 提供「数据回流 → 轨迹评估 → 优化引擎」闭环能力，基于 Agent 真实运行轨迹完成质量判定，并通过优化引擎持续改进 Prompt 与 Skill，实现 Agent 自主演进。

#### 1. 数据回流

采集 Agent 运行轨迹，完成清洗与结构化归一，为评估与优化提供高质量输入：

- **轨迹采集**：从 Agent 运行日志 / Agent 上报 OpenTelemetry 格式数据中回流结构化轨迹；支持 log 模式与 standard（OTel）模式，两种模式产出记录格式同构。
- **轨迹清洗**：将异构轨迹归一为标准对话格式，剔除评估暂不需要的元数据。

#### 2. 轨迹评估

提供指标评估器与 LLM 评估器，对轨迹质量进行判定与评分，定位 Skill / Prompt 优化点：

- **指标评估器**：支持 F1、精确率、关键词匹配、语义相似度等指标评估。
- **LLM 评估器**：对任务完成度、轨迹质量、安全性进行多维评分，并支持 Skill 归因与可执行优化建议输出。

#### 3. 优化引擎

基于评估结果执行 Prompt 优化与 Skill 优化，将优化结果回写到目标 Agent：

- **Skill 优化器**：通过反思 → 聚合 → 选择 → 应用优化 Skill 文档，支持 SkillOpt/TF-GRPO 算法。
- **Prompt 优化器**：支持基于评估反馈自动迭代优化提示词，并通过业务 Agent 热更新测试验证。

#### 4. 自进化 Agent

通过智能体原生能力实现业务 Agent 自进化全流程：

- **业务 Agent 自进化**：通过智能体原生能力实现数据集导入 → 轨迹评估 → 策略优化 → 沙箱 Rollout 验证全流程。

---

## 测试与质量

### 一、平台能力

扩展模块与示例工程覆盖单元测试与集成测试。测试覆盖范围包括：

- **Versatile 适配器**：HTTP 请求映射、SSE 响应解析、中断检测和 URL 模板替换。
- **自定义 REST 入口**：请求映射 SPI、同步/流式调用、A2A 语义归一和 Task 查询/取消。
- **异构框架兼容**：AgentScope ReActAgent / HarnessAgent 的正常完成、失败终态、暂停恢复和取消边界。
- **Skill Hub 订阅**：required / optional skill 下载、完整性校验、降级首次生效和凭据脱敏。
- **客户端调用**：BLOCKING / STREAMING / ASYNC 三种调用模式、幂等重试、UNKNOWN 恢复和错误分类。
- **本地工具**：SPI 注册、ToolExposurePolicy、ToolView 上报、Observation 自动执行、Action 授权审批和拒绝场景。
- **端侧工具响应**：带 ToolView 调用、流式/非流式工具请求挂起、continuation 恢复、等待期间查询与取消。
- **react-rails 认知 rail**：三条 rail 控制流硬断（mutation-RED）、真 ReActAgent + 真 LLM e2e 数据通道、forceFinish gate offset 真消费。

### 二、Agent 引擎

- **单元测试覆盖**：覆盖核心模块单元测试。
- **端到端测试**：
  - **集成测试**：
    1. 安装指导是站在用户者视角进行的测试，验证了 docker 打包以及拉起方式。
    2. 功能验证了特性列表，包含了正常以及异常场景的验证。
    3. DFx 维度包括性能、可靠性、可维护性、安全性、韧性、稳定性、可扩展性、可观测性。
  - **场景测试**：测试范围为 XX 个理财场景用例，构建 Mock 环境进行环境搭建，通过发送 curl 命令给后台智能体，获取 SSE 信息评估用例执行结果，完成多产品购买、取消购买后重新推荐、流程中各环节取消、转账异常、边界值、理财推荐。

### 三、EvoAgent 自进化引擎

覆盖单元测试、集成测试与端到端测试。测试覆盖范围包括：

- **数据回流**：log / standard 采集链路、轨迹清洗归一与过滤。
- **轨迹评估**：指标评估器、LLM 评估器、数据集上传与批量评估。
- **优化引擎**：SkillOpt / TF-GRPO、Prompt managed-doc 优化、验证门控与取消回滚。
- **自进化 Agent**：数据集导入 → 轨迹评估 → 策略优化 → 沙箱 Rollout 验证全流程。

---

## 缺陷修复

本版本为首发版本，不涉及历史缺陷修复。

---

## 文档

### 一、平台能力

- `common/README.md`：目录说明与正式 / 非正式版本的编译打包流程。
- 各 example README：打包、启动、请求脚本。

### 二、Agent 引擎

- `docs/快速入门/`：涵盖核心特性、产品介绍、开发与运维快速入门。
- `docs/开发指南/`：包含 Redis 集成、内置工具、外部集成、开发方式、开发环境准备、技能开发与配置指南。
- `docs/运维指南/`：提供 Docker 部署、健康检查与日志、日常运维及环境配置指南。
- `docs/参考指南/`：工具 API 与环境变量参考。
- `docs/支持与排错/`：故障排查、常见问题、技术支持与版本变更。

### 三、EvoAgent 自进化引擎

- `docs/README.md`：项目总览与文档导航。
- `docs/02-部署指南/evoagent部署指南.md`：环境安装与双容器部署。
- `docs/03-API文档/api-evoagent.md`：API 接口说明。
- `docs/04-特性使用指南/`：数据回流、轨迹评估、优化引擎与自进化 Agent 使用指南。

---

## 已知限制

### 一、平台能力

- 客户端本地工具默认不暴露，需业务应用显式声明 ToolExposurePolicy。
- 当前版本一个 runtime 实例只承诺服务一个 Agent，多 Agent 部署应使用多个 runtime 实例或上层路由。
- runtime 不直接访问客户端本地工具、DOM、插件、文件、本地端口或业务 UI。

### 二、Agent 引擎

不涉及

### 三、EvoAgent 自进化引擎

- EvoAgent 与 EvoAgentAdapter 需同时可用；轨迹采集、Skill / managed-doc 读写依赖 Adapter。
- 评估 / 优化任务默认保存在服务进程内存中，服务重启后旧 `job_id` 无法继续查询。
- Prompt 优化当前一次任务只优化一个 Prompt 文档。
- 轨迹清洗规则固定，当前不支持通过配置自定义清洗规则。
- 轨迹采集的 log / standard 模式互斥，切换需重启 Adapter。

---

## 构建与验证

扩展依赖 `agent-runtime-java` 0.1.1 与 `agent-core-java` 0.1.14。

```bash
mvn -f common/agent-runtime-ext-java/pom.xml clean install
mvn -f common/example/versatile-a2a-adapter-demo/pom.xml clean install
mvn -f common/example/agentcore-ext-remote-a2a-tool-demo/pom.xml clean install
mvn -f common/example/agentcore-ext-deepagent-remote-a2a-demo/pom.xml clean install
mvn -f common/example/multi-deep-research-demo/pom.xml clean install
mvn -f common/agent-core-ext-java/pom.xml -pl :react-rails -am clean install
```

#### Maven 坐标

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

依赖要求：`com.openjiuwen:agent-runtime-java:0.1.1`、`com.openjiuwen:agent-core-java:0.1.14`。

---

## 致谢

感谢所有为 OpenJiuwen 智能体解决方案 v0.1.0 提交需求、Issue、Pull Request、设计评审、代码开发与测试验证的贡献者！

- **平台能力**：感谢构建 Versatile HTTP/SSE 适配、AgentCore 远端 A2A 工具注入与双 Agent demo 的各位贡献者，让扩展更易上手、边界更清晰。你们的反馈是 openJiuwen agent-solution 持续演进的动力。
- **Agent 引擎**：EDPAgent Java 版本的发布，标志着企业级通用动态规划智能体在 Java 技术栈的落地，为金融等行业的智能化转型提供了安全、可控、可观测的 Agent 引擎基础。你们的专业付出是 EDPAgent 满足金融等垂直行业企业级要求的坚实基础。
- **EvoAgent 自进化引擎**：你们的反馈是智能体自进化引擎（EvoAgent）持续演进的动力。
