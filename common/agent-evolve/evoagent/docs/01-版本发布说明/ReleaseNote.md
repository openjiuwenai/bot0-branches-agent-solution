# 智能体自进化引擎（EvoAgent）v0.1.0 版本更新日志

欢迎体验智能体自进化引擎（EvoAgent）v0.1.0正式发布版本。本版本提供「数据回流 → 轨迹评估 → 优化引擎」闭环能力，基于 Agent 真实运行轨迹完成质量判定，并通过优化引擎持续改进 Prompt 与 Skill，实现 Agent 自主演进。

## 全新功能

### 数据回流

- **轨迹采集**：从 Agent 运行日志 / Agent 上报OpenTelemetry格式数据中回流结构化轨迹。
- **轨迹清洗**：将异构轨迹归一为标准格式，支持工具失败、用户反馈等确定性过滤。

### 轨迹评估

- **指标评估器**：支持F1、精确率、关键词匹配、语义相似度等指标评估。
- **LLM 评估器**：对任务完成度、轨迹质量、安全性进行多维评分，并支持 Skill 归因。

### 优化引擎

- **Skill 优化器**：通过反思 → 聚合 → 选择 → 应用优化Skill文档，支持SkillOpt/TF-GRPO算法。
- **Prompt 优化器**：支基于评估反馈自动迭代优化提示词，支持SkillOpt/算法。

### 自进化 Agent
- **业务agent自进化**：通过智能体原生能力实现数据集导入→轨迹评估→策略优化→沙箱 Rollout 验证全流程




发布日期：2026 年 7 月 30 日

欢迎体验 v0.1.1 —— openJiuwen agent-solution 的功能版本！本版本在 v0.1.0 基础上全面扩展了异构智能体框架兼容、标准化客户端调用、本地工具注册与驱动、客户端调用多模式转发（路由/总线/事件）、A2A 调用事件转发、Agent Card 注册与发现、运行时实例路由查询、总线事件订阅消费，以及自定义 REST API 服务入口等核心能力：

- **支持 Versatile 和 Versatile 意图工作流适配**：支持按 intent 选择 endpoints URL 模板实现基于意图的路由分发，并增强 SSE 响应最小结果节点抽取。
- **自定义 REST API 服务入口**：新增自定义 REST API 到 A2A JSON-RPC 协议适配转换 SPI。
- **异构 Agent 框架兼容扩展**：支持扩展兼容 AgentScope 框架智能体。
- **支持 Skill Hub 订阅 Skill**：新增支持 Skill Hub 订阅 Skill，默认支持 OpenJiuwen Skill Hub，可通过 SPI 扩展支持自建 Skill Hub。
- **标准化智能体客户端调用**：新增标准化智能体服务调用 API，支持 BLOCKING / STREAMING / ASYNC 三种调用模式与完整的状态管理。
- **客户端本地工具注册与调用**：新增本地工具标准化 SPI 与注册管理，支持被远端智能体驱动调用，通过多轮请求返回执行结果。
- **运行时端侧工具响应**：新增带有端侧工具的请求处理，支持需要调用客户端侧工具的中断，通过智能体服务调用响应返回给客户端。
- **客户端调用路由转发**：新增支持按智能体 ID 的客户端调用路由转发，支持同步阻塞与流式调用。
- **客户端调用总线转发**：新增支持按智能体 ID 的客户端调用总线转发（pub-sub 模式），支持同步阻塞调用。
- **客户端调用事件转发**：新增支持客户端调用与服务端响应的事件转发。
- **A2A 调用事件转发**：新增支持智能体服务之间的 A2A 调用事件转发。
- **Agent Card 注册与发现**：新增支持 Agent Card 的注册功能与查询功能。
- **运行时实例路由查询**：新增支持通过智能体查询运行时实例路由信息。
- **订阅消费总线事件消息**：新增支持订阅消费事件总线的客户端调用事件消息与智能体服务 A2A 调用事件消息。
- **ReActAgent 认知能力补全（react-rails）**：新增 react-rails 模块，为 agent-core-java 的 ReActAgent 补三条认知 rail，通过 afterModelCall forceFinish gate 补 external-judge 验证、replan 计数超限 escalate 和设备故障诚实降级能力。

扩展以 runtime 的 `AgentHandler` SPI、`A2ARemoteAgentCardRegistry` 与 Spring Boot 自动装配为接入点；HTTP 接入、A2A 协议、远端 card 发现与通信、会话编排等运行时能力由 agent-runtime-java 提供。执行内核由 agent-core-java 提供。Gateway 路由与总线转发由 agent-bus 提供。客户端调用与本地工具治理由 agent-client 提供。

## 🚀 新特性

### 🧠 支持 Versatile 和 Versatile 意图工作流适配

支持 Versatile 和 Versatile 意图工作流适配：

- SSE 响应解析支持 `result-node-name` 最小结果节点抽取，当匹配 `node_name` 且 `node_type` 为 `"End"` 时提取最终结果。

### 🚪 自定义 REST API 服务入口（RestAPI 到 A2A JSON-RPC 协议适配转换 SPI）

runtime 在标准 Agent 服务语义之上提供自定义 REST edge adapter，使调用方以业务 REST/SSE 形态访问同一个 hosted Agent：

- **自定义 REST 服务入口**：允许用户通过自定义扩展方式把既有 REST API 形态映射为标准 Agent 服务调用。
- **请求映射 SPI**：用户自定义 `RestRequestMapper` 与 `RestResponseMapper`，负责请求归一和响应投影。
- **同步/流式消息调用**：支持同步 JSON 响应和 SSE 流式响应。
- **A2A 语义归一**：每次提交归一为标准 Agent 服务调用，Task、错误和租户语义归一到标准化 Agent 服务入口。
- **单入口单路径**：当前版本一个 runtime 实例只 host 一个 Agent，只允许一个 REST path pattern。

### 🔌 异构智能体框架兼容扩展

在 v0.1.0 的 `agentcore-ext` 与 `Versatile` adapter 基础上，进一步扩展异构框架兼容范围，支持扩展兼容 AgentScope 框架智能体：

- 支持包装宿主已构建的本地 `ReActAgent`，把 `Mono<Msg>` / `Flux<AgentEvent>` 映射为 runtime query、stream、失败和暂停语义。
- 支持包装宿主已构建的本地 `HarnessAgent`，通过公开 API 调用和状态读取，对上保持与 ReAct 相同的 runtime 协议。
- 已验证 message stop、人工确认和单个 external pending tool 三类暂停恢复。

### � 支持 Skill Hub 订阅 Skill

新增支持 Skill Hub 订阅 Skill，默认支持 OpenJiuwen Skill Hub，可通过 SPI 扩展支持自建 Skill Hub：

- runtime 在部署或启动阶段读取 Agent skill 选择配置（区分 required / optional 语义）。
- 通过可替换的 Skill Hub SPI 访问 Skill Hub，下载 Agent 声明需要的 skill 包。
- 支持下载完整性校验：Skill Hub 支持摘要时校验 SHA-256，不支持时执行文件非空、可完整读取、必要文件存在等常规校验。
- required skill 的配置/认证/授权/查找或启动阶段移交失败时阻断 Agent ready；下载或完整性校验失败时允许降级进入 ready，并在请求链路外重试。
- optional skill 获取失败时可跳过并继续启动，输出脱敏诊断。
- 凭据与敏感信息不写入日志、错误响应或遥测数据。

### 📞 标准化智能体客户端调用

面向业务应用提供标准 client facade，在创建调用时声明调用模式并回显调用关联与任务状态投影：

- **三种调用模式**：`BLOCKING`（一次性响应窗口）、`STREAMING`（全链路流式能力）、`ASYNC`（提交后异步观察）。
- **Conversation 传递**：支持业务应用传入或委托生成 `conversationId`，conversation 主权归业务应用。
- **Invocation 回显**：每次调用回显 `conversationId`、`invocationRef`、幂等键、调用模式、状态投影和恢复线索，不要求业务应用持有 `taskId`。
- **状态观察**：支持基于 `invocationRef` 查询、重订阅、取消、继续等待输入和 UNKNOWN 恢复。
- **幂等与重试**：创建类调用和继续等待输入类调用均具备独立幂等语义，重试不造成重复副作用。
- **错误分类**：区分网络错误、路由错误、服务端错误、业务失败、取消、拒绝、接受未知和流式能力不可用。

### 🛠️ 客户端本地工具注册与调用

新增本地工具的标准化 SPI 与注册管理，支持被远端智能体驱动调用：

- **本地工具 SPI 注册**：业务应用在集成开发阶段通过 SPI 注册本地工具描述与 handler，包括稳定 `toolId`、名称、描述、输入输出 schema、授权策略和审计策略。
- **Observation / Action 分类**：Observation 偏只读观测（自动执行），Action 产生副作用（需授权审批）。
- **默认不暴露**：未经业务应用显式声明，client 不向服务端暴露任何本地工具。
- **ToolExposurePolicy**：支持 conversation 级和 invocation 级可暴露工具范围声明，invocation 级策略可收窄或覆盖 conversation 级。
- **ToolView 生成与上报**：随真实 invocation 请求附上当前 ToolView，由本地工具目录、暴露策略和工具可用性共同计算。
- **远端驱动调用**：服务端只能通过受治理消息请求 ToolView 中可见的客户端工具，不能直接访问客户端本地资源。
- **结果提交**：工具执行结果经 Gateway 主动提交给 runtime，作为客户端内部恢复请求。

### 🔄 运行时端侧工具响应

新增支持带有端侧工具的请求处理，runtime 在 Agent 执行需要客户端本地工具时挂起当前 Task，通过响应投影工具请求，并在 client 提交工具 outcome 后校验恢复关系继续原 Task：

- **ToolView 承接与 Task 级绑定**：runtime 从标准 client invocation 中接收当前 ToolView，绑定到当前 Task 执行上下文。
- **客户端工具调用挂起**：Agent 执行中产生客户端本地工具调用时，runtime 挂起当前 Task，通过本次调用响应返回工具请求投影。
- **非完成状态**：等待客户端工具结果时，Task 不得被标记为 completed。
- **continuation 恢复**：client 按标准 continuation invocation 提交工具结果，runtime 校验关联后恢复原 Task。
- **客户端异常透传**：工具未声明、权限不足、参数非法、执行失败或超时等 outcome 作为工具结果输入恢复执行链路。

### 🌐 客户端调用路由转发

Gateway 作为客户端统一 A2A 调用路由转发入口，按 agentId 把客户端调用直连转发到目标 runtime：

- **入口治理**：完成认证鉴权、租户解析、幂等和审计。
- **agentId 路由**：按明确 agentId 查找 routeHandle 并转发，routeHandle 是受治理路由引用，不向客户端暴露物理 endpoint。
- **统一 A2A 封装转发**：以统一 A2A 兼容请求表面承接 SendMessage、SendStreamingMessage、GetTask 等语义。
- **SSE 桥接**：支持对目标服务 A2A SSE 的桥接，不生成 token、不缓存 token 流。
- **阻塞/流式/查询/取消统一入口**：所有调用模式通过同一 A2A facade 进入。
- **UNKNOWN 恢复**：允许使用同一 `clientInvocationId` 和 `idempotencyKey` 重试恢复。

### 📮 客户端调用总线转发

Gateway 将客户端调用标准化为总线控制事件，通过 Event Bus pub-sub 模式与 runtime consumer 协作：

- **pub-sub 入队**：client 生成 `clientInvocationId` 作为 Gateway 侧弱关联句柄，Gateway 发布 `CLIENT_INVOCATION_REQUESTED`。
- **外层事件信封**：携带 tenant、user、session、correlation、routeHandle、deadline、幂等键和 payload 描述。
- **大载荷引用**：大输入、多模态内容使用 `payloadRef` / `artifactRef`，不写入 Event Bus 事件正文。
- **同步阻塞等待**：支持五类响应状态（`COMPLETED_RESPONSE`、`ACCEPTED_WITH_TASK`、`REJECTED`、`FAILED`、`UNKNOWN`）。
- **流准备投影与桥接**：`INVOCATION_STREAM_READY` 表达可订阅事实，Gateway 在 client 连接存在时桥接 runtime A2A SSE。
- **token 流入总线**：不允许 token-by-token chunk 进入 Event Bus。

### ⚡ 客户端调用事件转发

agent-bus 作为事件总线转发客户端调用事件与服务端响应事件，保持 A2A 调用/响应兼容：

- **事件转发总契约**：gateway 与 event-bus 协作，把客户端调用封装为可治理的事件消息投递，服务端发布接受/响应/流准备/终态等事件回传。
- **三单元可替换部署**：gateway、event-bus、registry-discovery-center 可独立实现、独立构建、独立部署或由客户存量系统替换。
- **外层 bus 事件信封**：承载租户、trace、correlation、幂等、deadline、路由引用和 payload 描述。
- **A2A SSE 流桥接**：实时流内容继续使用服务端 A2A SSE，事件总线只转发流准备事件和引用。

### 🔗 A2A 调用事件转发

event-bus 承载智能体服务之间 A2A 调用事件与响应事件转发，保持 Task owner、流式边界、幂等和路由治理语义清晰：

- **A2A 调用事件发布与响应事件回传**：调用方服务端封装 A2A 调用事件经 event-bus 投递到目标服务。
- **外层 bus 事件信封承载治理字段**：租户、correlation、幂等、deadline、路由引用等不在 A2A body 中承载。
- **A2A payload 兼容**：A2A JSON-RPC 作为 payload 或 payload 引用出现。
- **流式调用**：请求事件通过 event-bus 投递，实时 token chunk 不进总线；`A2A_STREAM_READY` 与 `A2A_CALL_ACCEPTED` 分离。
- **Task 生命周期所有权不变**：被调用方的 runtime 仍是 Task owner。

### 🗂️ Agent Card 注册与发现

registry-discovery-center 承载 Agent Card 注册、发现、可见性、版本和能力目录事实：

- **部署发布事实接入**：通过可替换的 `DeploymentDiscoveryProvider` 获取部署环境中可信的 Agent Service 发布事实。
- **标准 A2A Agent Card 主动抓取**：从 `/.well-known/agent-card.json` 获取并以 `agentId` 为粒度注册。
- **多实例去重与多版本共存**：同一 `serviceId` 的多实例按 `agentId` 区分，支持多版本共存。
- **事件监听与周期全量对账**：增量事件 + 周期全量对账建立/更新/移除 Agent Card 目录。
- **结构化查询**：按 `agentId`、`serviceId`、`a2aSkillId`、`requiredSkillTags`、`capabilityVersion`、`requiredCapabilities` 等条件查询。
- **租户隔离**：按 `tenantId` 隔离注册与查询。

### 🔍 运行时实例路由查询

registry-discovery-center 支持已知目标的运行时实例路由查询，向 gateway 或 runtime 提供不暴露物理 endpoint 的路由引用和可用性投影：

- **已知目标路由查询**：面向已知 `agentId` / `serviceId` 查询当前活跃运行时实例路由。
- **agent-runtime 代理查询**：agent 不直接调用 registry-discovery-center，由 `agent-runtime` 代理路由查询。
- **路由引用**：`routeHandle` 是受治理引用，不暴露物理 endpoint。
- **路由可用性投影**：面向 agent/client 只显示简化健康状态（可用、可能不可用、有限可用、暂不可用、版本不匹配），不含物理地址。
- **中心短时不可用降级**：支持本地信息可用时返回最后一次已知路由，不可用时返回明确不可用状态。

### 📡 订阅消费总线事件消息

runtime 内嵌订阅并消费事件总线上的客户端调用事件消息与智能体服务 A2A 调用事件消息：

- **嵌入式事件订阅消费**：runtime 从 agent-bus event-bus 接收调用/查询/取消/流订阅控制事件，不新增 sidecar 或独立 worker。
- **客户端调用事件消费**（`CLIENT_INVOCATION_*` 类）：映射到标准 A2A Task 控制面。
- **服务间 A2A 请求事件消费**（`A2A_CALL_*` 类）：复用标准 A2A Task 语义。
- **状态投影发布**：发布接受、拒绝、失败、响应、流准备、等待输入、终态等投影事件。
- **ack 到接收边界**：不等 Agent 终态即 ack，Task 生命周期由 runtime 独立管理。
- **幂等与去重**：bus 投递去重键与 Task 创建幂等键分离，响应事件发布幂等。

### 🛡️ ReActAgent 认知能力补全（react-rails）

新增 `react-rails` 模块，为 agent-core-java 的 ReActAgent 补三条认知 rail，解决原生 ReActAgent 只有 reason+act 循环（无 verify、无 replan 意识、工具失败不降级）的 capability gap：

- **CriteriaVerificationRail（external-judge gate）**：`afterModelCall` 检测最终答案，调用 `CriteriaVerifier.verify()` 按成功标准验证，PASS 时 `forceFinish(verified=true)`，FAIL 时 `forceFinish(degraded=true, unmet=[...])`；默认提供基于规则的确定性验证器（零 LLM）。
- **ReplanRail（replan 计数/超限 escalate）**：`afterModelCall` 检测 `__replan__` tool_call 并计数，超过 maxReplan 时 `forceFinish(degraded)`，防止 LLM 反复换策略不收敛。
- **RootCauseRail（device-failure degrade）**：`onToolException` 标记 pendingDegrade，下一轮 `afterModelCall` 触发 `forceFinish(degraded)`，设备故障重试无效时诚实降级终止。
- **forceFinish gate 承重**：三条 rail 均通过 `requestForceFinish(Map)` 在 `afterModelCall` 钩子短路 ReActAgent 循环（字节码 offset 225/700 实证）。

纯 Java SDK，不依赖 Spring 或 runtime-ext；由应用显式注册 rail 与工具。

## 🧪 测试与质量

- 扩展模块与示例工程覆盖单元测试与集成测试。
- 测试覆盖范围包括：
  - **Versatile 适配器**：HTTP 请求映射、SSE 响应解析、中断检测和 URL 模板替换。
  - **自定义 REST 入口**：请求映射 SPI、同步/流式调用、A2A 语义归一和 Task 查询/取消。
  - **异构框架兼容**：AgentScope ReActAgent / HarnessAgent 的正常完成、失败终态、暂停恢复和取消边界。
  - **Skill Hub 订阅**：required / optional skill 下载、完整性校验、降级首次生效和凭据脱敏。
  - **客户端调用**：BLOCKING / STREAMING / ASYNC 三种调用模式、幂等重试、UNKNOWN 恢复和错误分类。
  - **本地工具**：SPI 注册、ToolExposurePolicy、ToolView 上报、Observation 自动执行、Action 授权审批和拒绝场景。
  - **端侧工具响应**：带 ToolView 调用、流式/非流式工具请求挂起、continuation 恢复、等待期间查询与取消。
  - **路由转发**：agentId 路由、认证鉴权、租户隔离、SSE 桥接、UNKNOWN 恢复和灰度回退。
  - **总线转发**：pub-sub 入队、事件投影、阻塞五类响应状态、流准备、大载荷引用。
  - **事件转发**：事件信封、A2A payload 兼容、流式调用事件分离、重复投递和租户隔离。
  - **Agent Card 注册发现**：主动抓取、多实例去重、周期对账、结构化查询。
  - **运行时路由查询**：已知目标路由、agent-runtime 代理查询、中心短时不可用降级。
  - **总线事件消费**：嵌入式订阅、调用事件消费、状态投影发布、ack 到接收边界和幂等去重。
  - **react-rails 认知 rail**：三条 rail 控制流硬断（mutation-RED）、真 ReActAgent + 真 LLM e2e 数据通道、forceFinish gate offset 真消费。

## 🐛 缺陷修复

无

## 📚 文档

- `common/README.md`：目录说明与正式 / 非正式版本的编译打包流程。
- 各 example README：打包、启动、请求脚本。

## ⚠️ 已知限制

- Gateway 不执行 Agent、不调用模型、不读写 runtime TaskStore，Task 权威状态仍由 runtime 拥有。
- Event Bus 不承载 token-by-token chunk 或 SSE frame，流式内容必须走 runtime A2A SSE。
- 客户端本地工具默认不暴露，需业务应用显式声明 ToolExposurePolicy。
- 当前版本一个 runtime 实例只承诺服务一个 Agent，多 Agent 部署应使用多个 runtime 实例或上层路由。
- runtime 不直接访问客户端本地工具、DOM、插件、文件、本地端口或业务 UI。

## 🧪 构建与验证

扩展依赖 `agent-runtime-java` 0.1.1 与 `agent-core-java` 0.1.14。

```bash
mvn -f common/agent-runtime-ext-java/pom.xml clean install
mvn -f common/example/versatile-a2a-adapter-demo/pom.xml clean install
mvn -f common/example/agentcore-ext-remote-a2a-tool-demo/pom.xml clean install
mvn -f common/example/agentcore-ext-deepagent-remote-a2a-demo/pom.xml clean install
mvn -f common/example/multi-deep-research-demo/pom.xml clean install
mvn -f common/agent-core-ext-java/pom.xml -pl :react-rails -am clean install
```

## 📦 Maven 坐标

```xml
<dependency>
    <groupId>com.openjiuwen</groupId>
    <artifactId>agent-service-adapters-agentcore-ext</artifactId>
    <version>0.1.1</version>
</dependency>
<dependency>
    <groupId>com.openjiuwen</groupId>
    <artifactId>agent-service-adapters-versatile</artifactId>
    <version>0.1.1</version>
</dependency>
```

依赖要求：`com.openjiuwen:agent-runtime-java:0.1.1`、`com.openjiuwen:agent-core-java:0.1.14`。

## 🙏 致谢

感谢所有为 v0.1.1 提交 Issue、Pull Request 与设计评审的贡献者！你们的反馈是 openJiuwen agent-solution 持续演进的动力。

特别感谢构建 Versatile HTTP/SSE 适配、AgentCore 远端 A2A 工具注入与双 Agent demo 的各位贡献者，让扩展更易上手、边界更清晰。

