# Adapter-Versatile-Agent 使用手册

## Versatile 工作流 A2A 独立代理适配器

---

### 快速入口

| 📖 文档 | 🔧 部署 | ❓ 排错 |
|---------|---------|---------|
| [产品介绍](./快速入门/产品介绍.md) | [Docker 部署指南](./运维指南/docker-deploy.md) | [常见问题](./support/faq.md) |
| [快速开始](./快速入门/部署快速入门.md) | [环境变量配置](./运维指南/env-vars.md) | [故障排查](./support/support.md) |
|  | [环境变量参考](./运维指南/env-vars.md) | [版本变更](./support/changelog.md) |

---

## 产品定位

Adapter-Versatile-Agent 是一个独立部署的 A2A（Agent-to-Agent）代理进程，作为 Versatile 工作流 HTTP/SSE API 的标准适配器。它架起 EDPAgent 等 A2A 客户端与 Versatile 业务工作流系统之间的桥梁，实现协议转换与智能节点分流。

> **本服务为纯配置驱动的轻量代理，无需二次开发，部署配置即可使用。**

---

## 核心能力

| 能力 | 说明 |
|------|------|
| **A2A 协议代理** | 标准 A2A Agent Card + `sendStreamingMessage` SSE 流式端点，完全兼容 A2A 协议规范 |
| **Versatile REST/SSE 适配** | URL 模板占位符替换、Header 白名单透传、超时控制，无缝对接 Versatile 工作流 API |
| **非结果节点流式透传** | 菜单（menu）、提示（notice）、表单（form）等交互节点实时流式推送给前端用户（Target.USER） |
| **用户输入中断** | 缺失 End 节点或需要用户输入时触发 `ToolInterruptException`，等待用户输入后续传 |
| **结果节点 LLM 反馈** | 结果节点（`GXZQAResponseNode`，可配置）作为工具结果返回给上游 LLM（Target.LLM） |
| **无状态轻量级** | 无 Redis、无数据库、无 Python 依赖，水平扩展只需增加副本 |

---

## 文档导航

| 章节 | 文档 | 说明 |
|------|------|------|
| 📖 快速入门 | [产品介绍](./快速入门/产品介绍.md) | 产品定位、核心能力、架构说明、适用场景 |
| 📖 快速入门 | [快速开始](./快速入门/部署快速入门.md) | 构建、配置、启动、验证的完整流程 |
| 🔧 运维指南 | [Docker 部署指南](./运维指南/docker-deploy.md) | Docker 单机/生产环境部署、Docker Compose 示例 |
| 🔧 运维指南 | [环境变量配置](./运维指南/env-vars.md) | 关键配置项详解、多环境配置方案 |
| 🔧 运维指南 | [环境变量参考](./运维指南/env-vars.md) | 所有环境变量完整参考表格 |
| 🔧 运维指南 | [日常运维指南](./运维指南/日常运维指南.md) | 启停操作、日志查看、版本升级、监控检查 |
| 🔧 运维指南 | [健康检查与日志](./运维指南/health-check.md) | 健康端点、日志配置、关键日志字段说明 |
| ❓ 支持排错 | [常见问题](./support/faq.md) | 部署/配置/运行常见问题与解答 |
| ❓ 支持排错 | [故障排查指南](./support/support.md) | 故障诊断流程、日志收集、技术支持渠道 |
| ❓ 支持排错 | [服务启动失败](./support/troubleshooting/startup-failure.md) | 启动失败问题排查决策树 |
| ❓ 支持排错 | [Versatile 调用异常](./support/troubleshooting/versatile-error.md) | Versatile 连通性/超时/响应格式异常排查 |
| ❓ 支持排错 | [版本变更](./support/changelog.md) | 版本迭代记录与变更说明 |

---

## 三步启动服务

### 1. 配置 Versatile 地址

通过环境变量指向你的 Versatile 工作流服务：

```bash
# 必填：Versatile 工作流 API 地址（含 URL 模板变量）
export VERSATILE_URL=http://your-versatile-host:port/v1/0/agent-manager/workflows/{workflow_id}/conversations/{conversation_id}
```

> **配置说明**：所有配置项通过 Spring Boot `${ENV_VAR:default_value}` 机制绑定环境变量，即环境变量优先级高于 application.yml 默认值。部署时只需设置环境变量，无需修改 jar 包内的 application.yml。

### 2. 启动服务

Docker 启动（推荐）：
```bash
docker run -d \
  --name versatile-adapter \
  -p 8191:8191 \
  -e VERSATILE_URL=http://host.docker.internal:30001/v1/0/agent-manager/workflows/{workflow_id}/conversations/{conversation_id} \
  adapter-versatile-agent:latest
```

本地 Jar 启动：
```bash
java -jar adapter-versatile-agent-java-0.1.0-SNAPSHOT.jar
```

服务默认监听端口 **8191**。

### 3. 验证服务

```bash
curl http://localhost:8191/.well-known/agent-card.json
```

返回标准 A2A Agent Card 即表示服务启动成功。

详细步骤请参考 [快速开始](./快速入门/部署快速入门.md)。

---

## 架构概览

```
上游 EDPAgent (A2A Client)
        │
        │ A2A JSON-RPC (sendStreamingMessage, SSE)
        ▼
┌─────────────────────────────────────────┐
│  Adapter-Versatile-Agent (本服务)       │
│  ┌─────────────────────────────────┐   │
│  │     A2A 端点 (SSE)              │   │  ← 端口 8191
│  └─────────────────────────────────┘   │
│                    │                    │
│                    ▼                    │
│  ┌─────────────────────────────────┐   │
│  │   VersatileAgentHandler         │   │
│  │  (agent-runtime-java 核心)      │   │
│  │  · 节点三分流逻辑                │   │
│  │  · 中断续传                     │   │
│  └─────────────────────────────────┘   │
│                    │                    │
│                    ▼                    │
│  ┌─────────────────────────────────┐   │
│  │     VersatileHttpClient         │   │
│  │  · URL 模板变量替换              │   │
│  │  · Header 白名单透传            │   │
│  │  · SSE 流式转发                 │   │
│  └─────────────────────────────────┘   │
└─────────────────────────────────────────┘
        │
        │ HTTP/SSE
        ▼
  Versatile 工作流 REST API
        │
        ├─► 非结果节点(menu/notice/form) ──► Target.USER (前端)
        │
        ├─► 需要用户输入 ────────────────► Interrupt (中断等待续传)
        │
        └─► 结果节点(GXZQAResponseNode) ──► Target.LLM (上游 Agent)
```

**三分流处理逻辑：**
- **Target.USER**：菜单、表单、确认提示等交互节点，流式透传给前端用户
- **Interrupt**：流程暂停等待用户补充输入，通过 A2A 中断机制通知上游
- **Target.LLM**：最终结果节点，作为工具调用结果返回给上游 LLM

---

## 依赖要求

| 依赖 | 版本要求 | 说明 |
|------|----------|------|
| JDK | 17+ / 21 | 推荐使用 JDK 17 或 JDK 21 LTS 版本（仅本地构建需要） |
| Maven | 3.6+ | 用于构建项目（仅本地构建需要） |
| Versatile 服务 | 可访问 | 需要有可访问的 Versatile 工作流服务（或 mock 服务） |
| Docker | 20.10+ | Docker 部署时需要 |

> **Docker 部署无需 JDK/Maven 环境**，只需 Docker 运行时即可。

---

## 关键代码参考

| 文件 | 链接 |
|------|------|
| 配置文件 | [application.yml](file:///D:/AgentTool/EDPAgent_java/agent-solution/common/agent/adapter-versatile-agent-java/src/main/resources/application.yml) |
| 启动类 | [VersatileAgentApplication.java](file:///D:/AgentTool/EDPAgent_java/agent-solution/common/agent/adapter-versatile-agent-java/src/main/java/com/huawei/ascend/versatile/VersatileAgentApplication.java) |
| 自动配置类 | [VersatileAgentConfiguration.java](file:///D:/AgentTool/EDPAgent_java/agent-solution/common/agent/adapter-versatile-agent-java/src/main/java/com/huawei/ascend/versatile/VersatileAgentConfiguration.java) |

---

> **默认端口**：8191  
> **默认 Agent ID**：versatile-agent  
> **默认结果节点**：GXZQAResponseNode  
> **默认超时**：600 秒
