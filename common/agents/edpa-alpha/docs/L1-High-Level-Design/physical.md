---
level: L1-HLD
module: agents/edpa-alpha
TAG: [physical-view, deployment, architecture-fact]
status: active
updated: 2026-08-15
dependency: [overview.md, process.md, development.md]
---

# EDPA-alpha L1 架构物理视图

## 1. 物理视图定位

EDPA-alpha 是 Spring Boot autoconfig + agent 库（无独立进程），但 MCP 集成引入了 **stdio subprocess**——这是 EDPA 独有的物理边界。

## 2. 部署形态

| 形态 | 物理位置 | 说明 |
|---|---|---|
| Spring Boot app | 宿主应用进程 | 宿主在 @Bean AgentHandler 里显式调 EdpaRails.registerOnto；EdpaAutoConfiguration 只提供基础设施 Bean。 |
| MCP subprocess | 宿主进程 fork 的子进程 | StdioMcpClient 启动 MCP 服务器 subprocess，经 stdin/stdout JSON-RPC 通信。 |

## 3. 进程与网络边界

| 边界项 | EDPA | 说明 |
|---|---|---|
| 独立进程 | 无（autoconfig jar） | EDPA 不开端口。 |
| MCP subprocess | 有（stdio） | 每个 MCP 服务器是一个 subprocess；宿主管理生命周期（close）。 |
| 网络调用 | MCP subprocess 内部 | EDPA 自身不发起网络调用；MCP 服务器可能（如 SEC EDGAR API）。 |
| 监听端口 | 无 | HTTP/A2A 由 runtime 承接。 |

## 4. MCP subprocess 拓扑

```text
宿主应用进程（Spring Boot）
 ├─ ReActAgent（EDPA overlay）
 │   └─ McpToolAdapter.invoke(args)
 │       └─ StdioMcpClient.callTool(name, args)
 │           ├─ stdin ──▶ JSON-RPC request ──▶ MCP subprocess（如 python mcp-server.py）
 │           └─ stdout ◀── JSON-RPC response ◀─ MCP subprocess
 │               └─ MCP 服务器可能连接外部 API（如 SEC EDGAR）
 └─ close() → subprocess.destroy()
```

## 5. 凭据与敏感数据边界

EDPA **不持有凭据**：LLM 凭据由宿主/agent-core-java 管理；MCP 服务器的凭据（如 API key）在 MCP subprocess 内部，不经过 EDPA。**PEV 层 N/A**（同 PEV physical §6）。

## 6. 可观测数据出口

**DataFlowObserverRail / OTel span 层已移除（MR !77）**：agent-core-ext-react-rails 结论甲实证 ext 层 OTel-as-source 错层。当前 EDPA 可观测性继承 agent-core-ext-react-rails 的 RailTelemetry（SteeringEvent "EXPLORE_FINDINGS" / "CONVERGENCE_STALL"），**不自带 OTel / DataFlow 层**。独立的工具调用数据流 span 拓扑 deferred（宿主若需工具调用数据流追踪，在 ext 层自行接入，不在 EDPA 物理边界内）。

## 7. 国产化硬件适配（鲲鹏 / 昇腾）

EDPA 是纯 JVM 库，不直接依赖昇腾算子；模型推理在 LLM 后端。要求能在鲲鹏（aarch64）JVM 上构建与运行。MCP subprocess 的可移植性取决于 MCP 服务器实现（如 Python 脚本需目标环境有 Python）。

## 8. 部署边界不变量

- EDPA 无独立进程/端口（autoconfig jar）。
- MCP subprocess 生命周期由宿主管理（必须 close）。
- 可观测性经 agent-core-ext-react-rails RailTelemetry（EDPA 不自带 OTel 层，MR !77 已移除）。
- aarch64 JVM 可移植（EDPA 自身纯 Java）。

## 9. 与其他视图的衔接

- 领域对象/状态归属：`logical.md`。
- ReAct 循环/rail 钩入/MCP 调用流程：`process.md`。
- 包/依赖红线/SPI：`development.md`。
- 技术场景：`scenarios.md`。
