# EDPAgent - 企业级通用动态规划智能体

<div align="center">

**基于 DeepAgent 引擎 + ReAct 循环范式的企业级通用动态规划智能体**

[产品介绍](getting-started/产品介绍.md) | [运维快速入门](getting-started/运维快速入门.md) | [开发快速入门](getting-started/开发快速入门.md) | [API 参考](reference/工具API.md)

</div>

---

## 欢迎使用 EDPAgent

EDPAgent（Enterprise Dynamic Planning Agent）是一个面向企业场景的通用动态规划智能体。基于 OpenJiuwen DeepAgent 引擎，遵循 ReAct（Reasoning + Acting）循环范式，通过三层 Governance 配置体系（角色定义/行为约束/脚本话术）声明式定义 Agent 行为，配合 Rail 拦截器链实现执行管控，支持通过 A2A 协议与外部系统交互，开箱即用。

## 文档导航

请根据你的角色选择对应的入门路径：

### � 我是运维人员

负责部署、配置和日常运维 EDPAgent：

| 文档 | 说明 |
|------|------|
| [运维快速入门](getting-started/运维快速入门.md) | 30分钟快速完成Docker部署并发送第一个任务 |
| [产品介绍](getting-started/产品介绍.md) | 了解产品定位、核心能力和架构 |
| [Docker 部署指南](operations/Docker部署指南.md) | 生产环境Docker部署详解 |
| [环境配置指南](operations/环境配置指南.md) | 环境变量配置详解 |
| [健康检查与日志](operations/健康检查与日志.md) | 健康检查、日志级别和诊断方法 |
| [日常运维](operations/daily-ops.md) | 启停、日志、Redis维护、版本升级 |
| [环境变量参考](reference/环境变量参考.md) | 所有环境变量完整列表 |
| [故障排查](support/troubleshooting/服务启动失败.md) | 常见问题排查手册 |

### 💻 我是开发人员

负责配置场景、开发 Skill、对接外部系统：

| 文档 | 说明 |
|------|------|
| [开发快速入门](getting-started/开发快速入门.md) | Governance配置+Skill开发快速上手 |
| [开发方式指南](developer-guide/开发方式指南.md) | 两种开发方式：配置驱动 vs Maven集成+Java定制 |
| [产品介绍](getting-started/产品介绍.md) | 了解产品定位、核心能力和架构 |
| [核心特性](getting-started/features/任务规划特性.md) | 深入了解每个核心特性 |
| [Governance 配置指南](developer-guide/配置指南.md) | 三层配置体系完整说明 |
| [内置工具介绍](developer-guide/内置工具介绍.md) | 了解内置业务工具的使用方式 |
| [Skill 开发指南](developer-guide/技能开发指南.md) | YAML+Python Skill开发全流程 |
| [外部集成指南](developer-guide/外部集成指南.md) | Versatile、MCP、Redis、LLM对接 |
| [Redis 集成指南](developer-guide/Redis集成指南.md) | Redis配置、三模式部署、Key规范、降级容错 |
| [工具 API 参考](reference/工具API.md) | 工具调用接口和参数规范 |

### 📚 核心特性一览

- [任务规划（DeepAgent + Todo状态机）](getting-started/features/任务规划特性.md) - 自动规划、动态路径调整、任务状态追踪
- [业务规则管控（Governance三层配置）](getting-started/features/业务规则管控特性.md) - planrule/actrule/scriptconfig声明式管控
- [话术管理](getting-started/features/话术管理特性.md) - 模板化话术、变量替换、场景隔离
- [思维链（Thought Stream）](getting-started/features/思维链特性.md) - 逐字渲染/固定脚本双模式思考过程展示
- [日志特性](getting-started/features/日志特性.md) - LogRail观测日志、EdpaEventRail事件流、token统计、异常分类、端到端穿刺证据
- [工具间数据直通（ToolDataChannel）](getting-started/features/工具之间数据直通特性.md) - 工具间key-value数据直通，避免LLM转述丢失
- [Versatile工作流调用](getting-started/features/versatile工作流调用特性.md) - 委托外部业务工作流执行，支持中断续传
- [ask_user用户追问](getting-started/features/ask_user工具调用特性.md) - 缺失信息追问、敏感操作确认、人机协同
- [cancel_task任务取消](getting-started/features/cancel_task工具调用特性.md) - 取消任务、Todo清理、状态回滚
- [call_mcp脚本调用](getting-started/features/call_mcp工具特性.md) - MCP沙箱执行Python脚本，主备切换
- [Redis状态持久化](getting-started/features/Redis状态持久化特性.md) - Todo持久化+Checkpoint中断恢复，分布式无状态部署

### 🔧 支持与排错

| 文档 | 说明 |
|------|------|
| [常见问题 FAQ](support/常见问题.md) | 部署/配置/使用/运维/开发常见问题解答 |
| [服务启动失败](support/troubleshooting/服务启动失败.md) | 启动失败排查 |
| [Redis 连接问题](support/troubleshooting/Redis连接问题.md) | Redis连接失败排查 |
| [模型 API 错误](support/troubleshooting/模型API错误.md) | LLM调用失败排查 |
| [Versatile 调用异常](support/troubleshooting/Versatile调用异常.md) | Versatile调用问题排查 |
| [MCP 调用失败](support/troubleshooting/MCP调用失败.md) | MCP脚本执行问题排查 |
| [版本变更记录](support/版本变更.md) | 版本更新日志 |
| [技术支持渠道](support/技术支持.md) | 获取技术支持 |

---

## 快速体验

### 三步启动 EDPAgent

> **配置说明**：EDPAgent 使用 Spring Boot 标准配置机制。[application.yml](file:///D:/AgentTool/EDPAgent_java/branch_edpa_java/agent-store/edp-agent-java/engine/src/main/resources/application.yml) 中所有配置项均通过 `${ENV_VAR:default_value}` 语法绑定环境变量，即：环境变量 > application.yml 默认值。部署时只需通过环境变量覆盖需要修改的配置项，不需要修改 application.yml 文件本身。

**第一步**：准备环境变量配置

本地开发可复制环境变量模板：
```bash
cp .env.example .env
# 编辑 .env，至少配置 EDP_AGENT_MODEL_API_KEY
```

Docker 部署时关键配置项：
- `EDP_AGENT_MODEL_API_KEY`：**必填**，大模型 API Key
- `EDPA_REDIS_HOST`：Redis 地址（Docker 连宿主机用 `host.docker.internal`）
- `EDP_AGENT_SCENARIO_HOME`：场景目录（默认 `/app/scenarios/wealth-demo`）
- `EDP_AGENT_VERSATILE_A2A_URL`：Versatile A2A 适配器地址（如已部署 adapter-versatile-agent）

> ⚠️ application.yml 中 `EDP_AGENT_MODEL_API_KEY` 的默认值为开发测试 Key，生产环境**必须**通过环境变量覆盖。

**第二步**：启动服务

Docker Compose 启动：
```bash
docker-compose up -d
```

本地 Java 启动（需 JDK 21+ 和本地 Redis）：
```bash
# 设置环境变量后启动
export EDP_AGENT_MODEL_API_KEY=sk-your-key
./mvnw spring-boot:run -pl engine
```

**第三步**：验证并发送第一个任务

验证服务健康：
```bash
curl http://localhost:8190/.well-known/agent-card.json
```

发送第一个任务：
```bash
curl -N -X POST http://localhost:8190/a2a \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc":"2.0","id":"1","method":"sendStreamingMessage",
    "params":{"message":{"messageId":"msg1","role":"user",
      "parts":[{"kind":"text","text":"帮我推荐一款稳健型理财产品"}]}}
  }'
```

详细步骤请参考 [运维快速入门](getting-started/运维快速入门.md)。

---

## 技术架构概览

```
┌──────────────────────────────────────────────────────────────────┐
│                         业务接入层                                 │
│  A2A协议（JSON-RPC/SSE）  │  REST API  │  前端对话界面             │
└─────────────┬────────────────────────────────────────────────────┘
              │
┌─────────────▼────────────────────────────────────────────────────┐
│                       DeepAgent 执行引擎                              │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌───────────────┐   │
│  │ Planner  │→ │ ToolCall │→ │ Observer │→ │ Reflection    │   │
│  └──────────┘  └──────────┘  └──────────┘  └───────┬───────┘   │
│         ↑                                          │            │
│         └──────────── Todo状态机 ──────────────────┘            │
└─────────────┬────────────────────────────────────────────────────┘
              │
┌─────────────▼────────────────────────────────────────────────────┐
│                     Rail 拦截器链（9个）                          │
│  Cancel → Todo → ExecLimit → Mcp → Versatile → AskUser          │
│                              → Log → Event → Scripts            │
└─────────────┬────────────────────────────────────────────────────┘
              │
┌─────────────▼────────────────────────────────────────────────────┐
│                      业务工具层                                    │
│  call_versatile  │  call_mcp  │  ask_user  │  cancel_task        │
└─────────────┬─────────────┬──────────────┬───────────────────────┘
              │             │              │
┌─────────────▼──┐  ┌───────▼──────┐  ┌───▼───────┐  ┌───────────┐
│  Versatile     │  │  MCP SSE     │  │  Redis     │  │  LLM API  │
│  工作流引擎    │  │  脚本沙箱    │  │  状态存储  │  │  推理服务 │
└────────────────┘  └──────────────┘  └───────────┘  └───────────┘
```

EDPAgent 核心组件：
- **Governance 配置系统**：三层YAML配置定义Agent角色、行为边界和业务脚本
- **Rail 拦截器链**：9个拦截器管控执行全流程，包括取消、Todo管理、执行限制、中断处理、日志、事件、话术
- **Skill 技能系统**：声明式YAML+Python脚本扩展业务能力，支持触发词自动路由
- **ToolDataChannel**：工具间key-value数据直通通道
- **A2A协议**：Agent间通信标准协议，JSON-RPC over SSE
- **Redis**：Todo状态和Checkpoint持久化存储

---

## 相关资源

- 设计文档目录：[docs/design/](design/)
- 内置场景：[scenarios/](../scenarios/)（wealth-demo理财演示、hz-zhidaitong智贷通）
- Dockerfile：[deploy/Dockerfile](../deploy/Dockerfile)
- 环境变量模板：[.env.example](../.env.example)
- 依赖要求：JDK 21+、Redis 5.0+、Python 3.8+（MCP脚本）、Docker 20.10+（沙箱容器运行环境）
- 沙箱服务：[JiuwenBox](https://gitcode.com/openJiuwen/jiuwenswarm/tree/develop/jiuwenbox) — MCP 脚本沙箱执行环境，安装指南请参考项目 README
