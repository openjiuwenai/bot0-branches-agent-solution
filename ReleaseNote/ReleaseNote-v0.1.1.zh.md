# v0.1.1 Release Note

发布日期：2026 年 8 月 30 日

---

欢迎体验 OpenJiuwen 智能体解决方案 v0.1.1！本版本围绕智能体服务调用链路，交付智能体客户端、智能体总线、智能体运行时、核心框架能力扩展与智能体自进化引擎五部分的端到端标准化能力，完成交付物体积与依赖版本两项工程优化，并增强通用智能体 EDPAgent 的并行执行编排，让智能体服务的接入更简单、协作更顺畅、进化更自主、交付更顺滑。

---

## 🚀 新特性

### 🔌 智能体客户端

应用与智能体服务之间的调用入口，统一封装服务调用、本地工具协同与流式展示，屏蔽协议适配、断线重连等底层细节：

- **标准化服务调用**：统一 API 完成智能体调用的创建、查询与取消；链路中断后支持断点重连，基础设施故障自动熔断保护，长任务结果不丢失。
- **本地工具协同**：本地工具注册后可交由远端智能体驱动调用，默认不向服务端暴露；观察类操作自动执行、动作类操作经授权后执行，数据安全可控。
- **多路流式分流**：多个智能体协作时，交织到达的流式输出自动按来源分流渲染，每路内容都能对应到具体智能体，断线后可恢复现场继续展示。

### 🚌 智能体总线

承接客户端与智能体服务之间的调用与事件流转，网关、事件总线、注册发现中心三类组件均可独立部署、按需替换：

- **调用路由转发**：按智能体 ID 将客户端调用路由至目标运行时，统一承担认证鉴权与租户识别；阻塞、流式、查询与取消全类型调用均可转发，断线后可跨实例恢复任务。
- **总线事件流转**：调用与响应可经事件总线异步流转，客户端与智能体服务解耦；智能体之间的协作调用事件同样支持总线转发。
- **实例路由查询**：按智能体查询可用运行时实例，支持多实例候选与版本匹配；注册中心暂不可用时自动降级调用，业务不中断。

### ⚙️ 智能体运行时

本次发布为运行时带来六项新能力：对接 Versatile 控制器的意图转调、用户交互中断恢复、端侧工具调用响应、总线事件订阅、调用链追踪与任务并发限流，覆盖智能体服务从接入、交互到生产运行的全链路：

- **意图转调**：对接 Versatile 控制器，自动识别意图报文并调用目标智能体；控制器异常与退回信号自动区分处理，全程会话连续。
- **交互中断恢复**：智能体等待用户补充信息时任务挂起，客户端提交输入后从断点恢复；本地与远端智能体体验一致。
- **端侧工具响应**：智能体需要使用客户端本地工具时暂停并下发请求，客户端提交结果后自动恢复执行。
- **总线事件订阅**：运行时嵌入即可订阅消费总线事件，无需额外 sidecar 组件。
- **调用链追踪**：跨平台调用自动携带统一追踪标识，调用链路端到端可查；轨迹数据支持 OpenTelemetry 标准上报，配置即用、默认关闭。
- **并发与限流**：支持配置最大并发任务数，超载自动拒绝新任务，保障已运行任务的服务质量。

### 🧩 核心框架能力扩展

本次发布为执行内核新增两项协同能力：智能体感知与任务匹配让智能体之间互相发现、按任务委托协作，端侧工具动态装配让智能体按需调用客户端本地工具：

- **智能体感知与任务匹配**：智能体可自动感知平台上的其他智能体，按任务语义精准匹配并发起委托调用；复杂请求先拆解再逐任务执行。
- **端侧工具动态装配**：按任务动态装配工具可见面，智能体选定工具后移交运行时执行；工具不缓存、任务间不共享。

### 🧬 智能体自进化引擎

本次发布为自进化引擎带来四项新能力：轨迹增强、Agent 评估器、GEPA 优化算法适配与北向 SkillHub 对接，在「数据回流 → 轨迹评估 → 优化引擎」闭环基础上，进一步覆盖动态规划场景下的轨迹归因与 Skill 版本离线迭代：

- **轨迹增强**：动态规划场景下，支持轨迹 span 节点与 skill / agent.md 内联映射，实现轨迹与 Skill / AgentRule 的细粒度对应，为归因与优化提供更精确的数据。
- **Agent 评估器**：支持 Agent as a Judge 评估器，具备路径识别与链路归因能力，可对轨迹的执行路径进行判定与归因分析。
- **Prompt 优化器**：支持基于评估反馈自动迭代优化提示词，支持 SkillOpt 算法；本版本新增适配 GEPA 优化算法。
- **北向对接 SkillHub**：支持通过 SkillHub 对接实现 Skill 版本的离线更新迭代，便于完成 Skill 版本管理与迭代。

### 🛠️ 工程与兼容性优化

面向企业级交付环境的两项工程优化，交付物体积与依赖版本全面适配企业流水线：

- **交付物体积优化**：EDPAgent 的交付物Jar包体积优化至企业流水线 200MB 限额以内，部署不再受阻。
- **开源依赖版本统一**：agent-core 与 agent-runtime 开源依赖版本对齐，消除模块间依赖不一致。

### 🤖 通用智能体

面向企业场景的通用动态规划智能体 EDPAgent Java，本版本聚焦执行编排，复杂任务由多个子智能体并行完成：

- **规划工作流与子智能体并行执行**：主智能体一次规划、同轮并行发起多个子智能体执行，结果全部返回后一次性汇总推理，多子任务场景告别串行等待。

---

## 📚 相关文档

- `common/README.md`：目录说明与正式 / 非正式版本的编译打包流程。

### 🔌 智能体客户端

- `common/agent-client/README.md`：agent-client SDK 模块说明与交付边界。
- `common/agent-client/docs/getting-started.md`：客户端 SDK 快速上手。
- `common/agent-client/docs/proposals/agent-client-v1-design.md`：客户端 SDK V1 设计方案（含标准流式响应数据协议与多跳流式解析）。
- `common/example/agent-client-demo/README.md`：客户端 SDK 验证工程的打包、启动与请求脚本。

### 🚌 智能体总线

- `common/agent-bus/README.md`：智能体总线三组件职责与 DIRECT / BUS 转发关系。
- `common/agent-bus/agent-gateway/README.md`：网关的客户端调用路由转发与总线转发说明。
- `common/agent-bus/event-bus/README.md`：事件总线的调用事件转发说明。
- `common/agent-bus/registry-discovery-center/README.md`：注册发现中心与运行时实例路由查询说明。
- `common/example/agent-gateway-demo/README.md`：网关直连与总线转发冒烟验证示例。

### ⚙️ 智能体运行时

- `common/agent-runtime-ext-java/doc/features/`：运行时扩展特性说明（总线事件订阅消费、端侧工具调用响应、Versatile 意图转调、AgentScope 中断恢复等）。
- `common/agent-runtime-ext-java/doc/guides/`：运行时扩展集成指南（总线消费集成、AgentCore 扩展工具、外部运行时集成等）。
- `common/agent-runtime-ext-java/doc/configuration.md`：运行时扩展配置参考（含并发限流与 OTel 轨迹上报配置）。
- `common/example/agent-bus-consumer-demo/README.md`：总线事件订阅消费 caller / callee 示例。
- `common/example/agentscope-a2a-interrupt-demo/README.md`：用户交互中断与续传示例。

### 🧩 核心框架能力扩展

- `common/agent-core-ext-java/README.md`：核心框架扩展总览。
- `common/agent-core-ext-java/agent-core-ext-intent-suite/README.md`：意图套件（智能体感知与下游任务匹配调用）说明。
- `common/example/bank-intent-routing-a2a-demo/README.md`：多智能体意图匹配路由与 A2A 委找示例。

### 🧬 智能体自进化引擎

- `common/agent-evolve/evoagent/docs/README.md`：项目总览与文档导航。
- `common/agent-evolve/evoagent/docs/02-部署指南/evoagent部署指南.md`：环境安装与双容器部署。
- `common/agent-evolve/evoagent/docs/03-API文档/api-evoagent.md`：API 接口说明。
- `common/agent-evolve/evoagent/docs/04-特性使用指南/`：数据回流、轨迹评估、优化引擎与自进化 Agent 等特性使用指南。

### 🤖 通用智能体

- `common/agents/edp-agent-java/docs/快速入门/`：产品介绍与开发、运维快速入门。
- `common/agents/edp-agent-java/docs/开发指南/`：内置工具、外部集成、开发方式、技能开发与配置指南。
- `common/agents/edp-agent-java/docs/运维指南/`：Docker 部署、健康检查与日志、日常运维及环境配置指南。
- `common/agents/edp-agent-java/docs/参考指南/`：工具 API 与环境变量参考。
- `common/agents/edp-agent-java/docs/支持与排错/`：故障排查、常见问题、技术支持与版本变更。
- `common/agents/edp-agent-java/deploy/README.md`：部署脚本与配置说明。

---

## 🧪 构建与验证

扩展依赖 `agent-runtime-java` 0.1.2 与 `agent-core-java` 0.1.15。

```bash
# 客户端 SDK 与验证工程（含 agent-client-sdk-for-jvm 交付物）
mvn -f common/example/agent-client-demo/pom.xml clean install

# 智能体总线（按依赖顺序：注册发现中心 → 事件总线 → 网关）
mvn -f common/agent-bus/registry-discovery-center/pom.xml clean install
mvn -f common/agent-bus/event-bus/pom.xml clean install
mvn -f common/agent-bus/agent-gateway/pom.xml clean install

# 运行时扩展与核心框架扩展
mvn -f common/agent-runtime-ext-java/pom.xml clean install
mvn -f common/agent-core-ext-java/pom.xml clean install

# 代表性示例验证
mvn -f common/example/agent-bus-consumer-demo/pom.xml clean install
mvn -f common/example/bank-intent-routing-a2a-demo/pom.xml clean install

# 通用智能体 EDPAgent Java
mvn -f common/agents/edp-agent-java/pom.xml clean install
```

智能体自进化引擎为 Python 交付物，通过 Docker 双容器部署，不依赖 Maven 构建，构建与启动方式参见 `common/agent-evolve/evoagent/docs/02-部署指南/evoagent部署指南.md`。

### Maven 坐标

```xml
<dependency>
    <groupId>com.openjiuwen</groupId>
    <artifactId>agent-client-sdk-for-jvm</artifactId>
    <version>0.1.1</version>
</dependency>
<dependency>
    <groupId>com.openjiuwen</groupId>
    <artifactId>event-bus-sdk</artifactId>
    <version>0.1.1</version>
</dependency>
<dependency>
    <groupId>com.openjiuwen</groupId>
    <artifactId>agent-service-bus-consumer</artifactId>
    <version>0.1.1</version>
</dependency>
<dependency>
    <groupId>com.openjiuwen</groupId>
    <artifactId>agent-core-ext-intent-suite</artifactId>
    <version>0.1.1</version>
</dependency>
<dependency>
    <groupId>com.openjiuwen</groupId>
    <artifactId>edp-agent-engine</artifactId>
    <version>0.1.1</version>
</dependency>
```

依赖要求：`com.openjiuwen:agent-runtime-java:0.1.2`、`com.openjiuwen:agent-core-java:0.1.15`。

---

## 🙏 致谢

感谢所有为 OpenJiuwen 智能体解决方案 v0.1.1 提交需求、Issue、Pull Request、设计评审、代码开发与测试验证的贡献者！
