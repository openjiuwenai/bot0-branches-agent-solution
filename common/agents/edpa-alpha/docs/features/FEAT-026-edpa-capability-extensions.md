---
scope: v0.1.0 → v0.2.0（装配显式化）
module: agents/edpa-alpha
feature_type: functional
feature_id: FEAT-026
status: active
updated: 2026-08-15
---

# EDPA 能力扩展（MCP 工具集成 + SubAgent 派发 + Explore Tool）

## 1. 特性定位

FEAT-026 定义 `agents/edpa-alpha` 的**外部能力扩展层**：MCP（Model Context Protocol）工具集成、SubAgent 派发、Explore Tool 注册。这些能力使 EDPA agent 能调用外部工具服务器、委派子任务给子 agent、在行动前主动探索调研——扩展 ReAct agent 的工具集和任务分解能力。

本特性解决的问题是：ReAct agent 的工具集通常是编译期固定的；MCP 允许运行时连接外部工具服务器（如 SEC EDGAR 财报检索），SubAgent 允许把复杂子任务委派给专门的子 agent，Explore 允许 agent 在决策前调研。这三种能力让 EDPA 从"固定工具集的 ReAct"进化为"可扩展工具 + 可分解任务 + 可探索"的增强 agent。

本特性只定义 MCP 集成、SubAgent 派发和 Explore Tool 的能力扩展行为。认知增强核心（ProactiveConvergenceRail、GroundTruthVerifier、ExploreRail）由 FEAT-025 承接。

本特性面向以下角色：

- **Agent 开发者**：理解如何连接 MCP 服务器、注册 SubAgent、配置 Explore Tool。
- **工具提供者**：理解 MCP 工具如何被适配为 agent 可调用的 Tool。
- **测试与验收团队**：按 MCP 调用/SubAgent 派发/Explore 返回设计验证场景。

## 2. 当前版本能力要求

| 能力 | 要求级别 | 事实要求 |
|---|---|---|
| MCP 客户端 | MUST | 必须提供 McpClient SPI + StdioMcpClient 实现（stdio JSON-RPC over subprocess，protocol version 2024-11-05）。 |
| MCP 工具适配 | MUST | McpToolAdapter 必须把 MCP 工具适配为 agent-core-java 的 Tool（getCard + invoke + stream），使 ReActAgent 可像调用内置工具一样调用 MCP 工具。 |
| MCP 工具注册 | MUST | McpToolRegistrar 必须批量注册 MCP 服务器的工具到 agent（listTools → 逐个 McpToolAdapter → Runner.resourceMgr().addTool）。 |
| MCP 生命周期 | MUST | StdioMcpClient 必须 implement AutoCloseable；close 销毁 subprocess。start 完成 initialize 握手。 |
| SubAgent 派发 | MUST | SubAgentDispatcher 必须把子 agent 注册为父 agent 可调用的 Tool（SubAgentTool）；父 agent 经 tool call 委派子任务。 |
| SubAgentExecutor SPI | MUST | 必须提供 SubAgentExecutor 接口，允许宿主定义子 agent 的执行后端（in-process 或远端）。 |
| Explore Tool 注册 | MUST | ExploreToolRegistrar 必须把 Explorer 注册为 agent 可调用的 ExploreTool（tool 模式探索，配合 UserInputCaptureRail 提供上下文）。 |
| 错误处理 | MUST | MCP 调用失败 → McpRpcException；SubAgent 执行失败 → Tool invoke 抛异常。 |
| MCP 传输安全 | OUT | 当前版本只承诺 stdio 传输；SSE/HTTP MCP 传输属未来版本。 |
| SubAgent 远程派发 | OUT | SubAgentExecutor 当前只承诺 in-process 执行；远程派发属未来。 |

## 3. 外部接口与入口要求

| 接入面 | 类型 | 输入要求 | 输出要求 | 约束 |
|---|---|---|---|---|
| McpClient | SPI | `listTools()` / `callTool(name, args)` / `close()`（start/stop 由具体实现管理，非 SPI 方法） | `List<McpTool>` / `String` | AutoCloseable；stdio 实现。 |
| StdioMcpClient | 实现类 | `List<String> command, Map<String,String> env` | — | 启动 subprocess + JSON-RPC 握手。 |
| McpTool | record | `name, description, inputSchema` | — | MCP 工具元数据。 |
| McpToolAdapter | Tool 适配 | `McpClient client, McpTool tool` | ToolCard + invoke → callTool | 适配为 agent-core Tool。 |
| McpToolRegistrar | 注册器 | `ReActAgent agent, McpClient client` | 批量注册工具 | listTools → 逐个适配。 |
| SubAgentDispatcher | 注册器 | `ReActAgent agent, toolName, description, SubAgentExecutor` | SubAgentTool 注册到 agent | 子 agent 作为可调用工具。 |
| SubAgentExecutor | SPI | `execute(userInput, subGoal)` | `String` | 宿主定义执行后端。 |
| ExploreToolRegistrar | 注册器 | `ReActAgent agent, Explorer, ExploreBudget, Supplier<String> userInput` | ExploreTool 注册 | tool 模式探索。 |

## 4. 场景

技术场景见 `scenarios.md` TS-03/04/05（MCP 调用 / SubAgent 派发 / 探索）。



## 5. 行为语义与边界

### 5.1 核心行为语义

#### 5.1.1 MCP 工具适配语义

- McpToolAdapter 继承 agent-core-java 的 `Tool`，实现 `getCard()`（从 McpTool 元数据生成 ToolCard）和 `invoke()`（委托 McpClient.callTool）。
- agent（ReActAgent）不感知工具是 MCP 远程的还是内置的——McpToolAdapter 透明适配。
- callTool 返回 MCP `content` 文本；非文本 content 当前不支持（OUT）。

#### 5.1.2 SubAgent 派发语义

- SubAgentDispatcher.registerOnto 把子 agent 包装为 SubAgentTool（继承 Tool），注册到父 agent 的 resourceMgr。
- 父 agent 的 LLM 看到 SubAgentTool 的 ToolCard，可以像调用普通工具一样委派子任务。
- SubAgentExecutor 是执行后端 SPI：in-process（直接调子 agent invoke）或未来远端（经 A2A delegate）。
- **诚实边界**：当前 SubAgent 派发的上下文传递是最简路径——context 经 tool call args 传入，不自动继承父 agent 的完整会话状态。

#### 5.1.3 Explore Tool 语义

- ExploreTool 包装 Explorer SPI；agent 调用时传入 topic，Explorer 调研后返回 ExplorationResult。
- UserInputCaptureRail 缓存首轮用户输入，ExploreToolRegistrar 把它作为 Supplier 传入，使 ExploreTool 能以原始用户意图为探索上下文。
- 预算受限（ExploreBudget：maxRounds/maxSubAgents/timeoutMillis）。

### 5.2 显式边界与不承诺项

| 边界 | 当前版本不承诺 |
|---|---|
| MCP 非 stdio 传输 | 只承诺 stdio JSON-RPC；SSE/HTTP 属未来。 |
| SubAgent 远程派发 | 只承诺 in-process；远程 A2A delegate 属未来。 |
| SubAgent 上下文自动继承 | 当前是最简路径（args 手动传）；自动继承父会话状态属未来。 |
| MCP 非文本 content | 当前只提取 MCP `content` 的文本部分。 |

## 6. 对下游设计与实现的约束

- MCP 工具适配不得泄漏 MCP SDK 类型到 agent 的公共 API（McpToolAdapter 在边界转换）。
- SubAgent 派发必须保证父 agent 不阻塞等待子 agent（in-process 执行在 tool invoke 线程同步返回）。
- ExploreTool 的预算约束：自定义 Explorer 实现应消费 maxRounds；maxSubAgents/timeout 当前为 planned 字段（无生产消费者，宿主不应依赖其行为——诚实边界见 EdpaProperties javadoc）。
- MCP subprocess 生命周期必须由宿主管理（close 释放）；EdpaAutoConfiguration 不自动管理 MCP 连接生命周期。

## 7. 关联文档

- `L1-High-Level-Design/{overview,logical,process,development,physical,scenarios}.md`（L1 4+1 视图）
- `L2-Low-Level-Design/{README,Feat-Func-026-edpa-capability-extensions}.md`（L2 详细设计）
- FEAT-025（EDPA 认知增强闭环核心）
