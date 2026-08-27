# v0.1.0 Release Note

发布日期：2026 年 7 月 30 日

---

欢迎体验 OpenJiuwen 智能体解决方案 v0.1.0！本版本围绕智能体服务的接入、运行与自主演进，交付智能体运行时、核心框架能力扩展、智能体自进化引擎与通用智能体四部分的端到端能力基座：运行时支持 Versatile 意图工作流路由、自定义 REST 服务入口、AgentScope 等异构框架智能体兼容接入与 SkillHub 订阅，核心框架为 ReActAgent 补全评估验证、重计划控制与故障降级等认知护栏能力，自进化引擎以「数据回流 → 轨迹评估 → 优化引擎」闭环基于智能体真实运行轨迹持续改进 Prompt 与 Skill，并面向金融等垂直行业 Java 技术栈交付涵盖推理、管控、协同、规划与治理完整能力的企业级通用智能体 EDPAgent，让智能体服务的接入更灵活、执行更可靠、进化更自主、落地更安心。

---

## 🚀 新特性

### ⚙️ 智能体运行时

本次发布为运行时带来四项新能力：Versatile 意图工作流路由、自定义 REST API 服务入口、AgentScope 等异构框架智能体兼容接入与 SkillHub 订阅，覆盖智能体服务接入与运行的常见场景：

- **Versatile 意图工作流路由**：按意图自动选择工作流服务地址实现路由分发，流式响应中的最终结果按结果节点自动抽取，无需关注节点细节。
- **自定义 REST API 服务入口**：既有 REST API 形态无需改造即可映射为标准智能体服务调用，同步 JSON 与 SSE 流式响应均可承载；当前版本单个运行时实例承载一个智能体、支持一个路径匹配规则。
- **异构智能体框架兼容接入**：AgentScope 等异构框架构建的智能体可直接包装接入运行时，统一获得查询、流式、失败与暂停的标准语义；消息中止、人工确认与外部工具等待三类暂停场景均可恢复续跑。
- **SkillHub 订阅**：智能体声明的 Skill 包在启动阶段自动下载，完整性校验保障包可信；关键 Skill 缺失时阻断就绪避免带病上线，可选 Skill 失败可降级启动，凭据信息脱敏不外泄。

### 🧩 核心框架能力扩展

本次发布为执行内核补全认知护栏能力，让 ReActAgent 的推理执行具备质量校验与风险防护：

- **ReActAgent 认知能力补全**：为 ReActAgent 新增三条认知护栏——按成功标准验证最终答案、限制重规划次数防止发散、设备故障时降级终止；纯 Java SDK 交付，不依赖 Spring 与运行时扩展。

### 🧬 智能体自进化引擎

本次发布交付自进化引擎「数据回流 → 轨迹评估 → 优化引擎」完整闭环与自进化 Agent，基于智能体真实运行轨迹完成质量判定，持续改进 Prompt 与 Skill，实现智能体自主演进：

- **数据回流**：从运行日志或 OpenTelemetry 链路数据中回流结构化轨迹，支持日志与标准链路两种模式，自动清洗归一为标准对话格式供评估使用。
- **轨迹评估**：指标评估与 LLM 评估双通道，覆盖精确率、关键词匹配、语义相似度与任务完成度、轨迹质量、安全性多维度，自动定位 Skill 与 Prompt 的优化点并输出可执行建议。
- **优化引擎**：基于评估结果执行 Skill 优化与 Prompt 优化，优化结果经业务智能体热更新验证后回写目标智能体生效。
- **自进化 Agent**：以智能体原生能力串联数据集导入、轨迹评估、策略优化与沙箱验证全流程，业务智能体自进化闭环开箱即用。

### 🤖 通用智能体

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

## 📚 相关文档

- `common/README.md`：目录说明与正式 / 非正式版本的编译打包流程。

### ⚙️ 智能体运行时与 🧩 核心框架能力扩展

- `common/example/versatile-a2a-adapter-demo/README.md`：Versatile 意图工作流路由示例的打包、启动与请求脚本。
- `common/example/agentcore-ext-remote-a2a-tool-demo/README.md`：DeepAgent 远端 A2A 工具注入示例的打包、启动与请求脚本。
- `common/example/agentcore-ext-deepagent-remote-a2a-demo/README.md`：DeepAgent 远程 A2A 委托与中断恢复示例的打包、启动与请求脚本。
- `common/example/multi-deep-research-demo/README.md`：多智能体深度调研示例的打包、启动与请求脚本。

### 🧬 智能体自进化引擎

- `common/agent-evolve/evoagent/docs/README.md`：自进化引擎项目总览与文档导航。
- `common/agent-evolve/evoagent/docs/02-部署指南/evoagent部署指南.md`：环境安装与双容器部署。
- `common/agent-evolve/evoagent/docs/03-API文档/api-evoagent.md`：API 接口说明。
- `common/agent-evolve/evoagent/docs/04-特性使用指南/`：数据回流、轨迹评估、优化引擎与自进化 Agent 使用指南。

### 🤖 通用智能体

- `common/agents/edp-agent-java/docs/快速入门/`：涵盖核心特性、产品介绍、开发与运维快速入门。
- `common/agents/edp-agent-java/docs/开发指南/`：包含 Redis 集成、内置工具、外部集成、开发方式、开发环境准备、技能开发与配置指南。
- `common/agents/edp-agent-java/docs/运维指南/`：提供 Docker 部署、健康检查与日志、日常运维及环境配置指南。
- `common/agents/edp-agent-java/docs/参考指南/`：工具 API 与环境变量参考。
- `common/agents/edp-agent-java/docs/支持与排错/`：故障排查、常见问题、技术支持与版本变更。

---

## 🧪 构建与验证

扩展依赖 `agent-runtime-java` 0.1.1.post1 与 `agent-core-java` 0.1.14.post1。

```bash
# 运行时扩展与核心框架扩展
mvn -f common/agent-runtime-ext-java/pom.xml clean install
mvn -f common/agent-core-ext-java/pom.xml -pl :agent-core-ext-react-rails -am clean install

# 代表性示例验证
mvn -f common/example/versatile-a2a-adapter-demo/pom.xml clean install
mvn -f common/example/agentcore-ext-remote-a2a-tool-demo/pom.xml clean install
mvn -f common/example/agentcore-ext-deepagent-remote-a2a-demo/pom.xml clean install
mvn -f common/example/multi-deep-research-demo/pom.xml clean install
```

### Maven 坐标

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

依赖要求：`com.openjiuwen:agent-runtime-java:0.1.1.post1`、`com.openjiuwen:agent-core-java:0.1.14.post1`。

---

## 🙏 致谢

感谢所有为 OpenJiuwen 智能体解决方案 v0.1.0 提交需求、Issue、Pull Request、设计评审、代码开发与测试验证的贡献者！
