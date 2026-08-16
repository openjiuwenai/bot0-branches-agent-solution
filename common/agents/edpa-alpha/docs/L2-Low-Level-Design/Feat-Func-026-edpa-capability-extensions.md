---
level: L2-LLD
module: agents/edpa-alpha
feature_type: functional
feature_id: FEAT-026
status: active
authority: authoritative
updated: 2026-08-15
dependency:
  - ../features/FEAT-026-edpa-capability-extensions.md
  - README.md
  - ../L1-High-Level-Design/overview.md
  - ../L1-High-Level-Design/process.md
---

# EDPA 能力扩展 — 设计文档（FEAT-026 L2）

> 目标模块：`agents/edpa-alpha`（MCP + SubAgent + Explore）
> 事实来源：`features/FEAT-026-edpa-capability-extensions.md`
> 参照代码：`common/agents/edpa-alpha/src/main/java/com/openjiuwen/agents/edpa/{mcp,subagent,explore}/`

## 1. 概述

### 1.1 特性定位
MCP 工具集成（运行时连接外部工具服务器）、SubAgent 派发（父 agent 委派子任务）、Explore Tool 注册（tool 模式探索）——三种能力扩展 ReAct agent 的工具集和任务分解。

### 1.2 设计原则
1. **Tool 透明适配** — MCP/SubAgent 经 Tool 适配，agent 不感知远程性。
2. **SPI 扩展** — McpClient / SubAgentExecutor 是 SPI，宿主可替换实现。
3. **生命周期宿主管** — EDPA autoconfig 不管理 MCP subprocess / SubAgent 生命周期。

## 2. 特性规格

### 2.1 接入契约

```java
// MCP 连接 + 工具注册（start/registerOnto 声明 throws Exception，须包 try-with-resources）
try (StdioMcpClient client = new StdioMcpClient(
        List.of("python", "mcp-server.py"), Map.of())) {
    client.start();                                // initialize 握手（throws IOException）
    McpToolRegistrar.registerOnto(agent, client);  // throws Exception
    // ... agent 使用 MCP 工具 ...
} // try-with-resources 自动 close（StdioMcpClient implements AutoCloseable）

// SubAgent 注册
SubAgentDispatcher.registerOnto(agent, "research-agent", "调研子任务",
    (userInput, subGoal) -> subAgent.invoke(subGoal, null));

// Explore Tool 注册（tool 模式）
ExploreToolRegistrar.registerOnto(agent, explorer, budget,
    () -> userInputRef.get());
```

### 2.2 数据类型

| 类型 | 关键字段 | 说明 |
|---|---|---|
| `McpTool` | name, description, inputSchema | MCP 工具元数据（record） |
| `McpClient` | start/listTools/callTool/close | MCP 客户端 SPI（AutoCloseable） |
| `StdioMcpClient` | command, env | stdio JSON-RPC subprocess 实现 |
| `McpToolAdapter` | client, tool | Tool 适配（invoke → callTool） |
| `ExploreBudget` | maxRounds, maxSubAgents, timeoutMillis | 探索预算 |
| `ExplorationResult` | findings, candidateApproaches | 探索返回 |
| `SubAgentTool` | executor | 子 agent 作为 Tool |

## 3. 核心实现

### 3.1 MCP stdio 通信

```text
StdioMcpClient
 ├─ start(): subprocess = ProcessBuilder.start()
 │   └─ runInitializeHandshake(): JSON-RPC initialize → capabilities 协商
 ├─ listTools(): JSON-RPC tools/list → List<McpTool>
 ├─ callTool(name, args): JSON-RPC tools/call → content 文本
 └─ close(): subprocess.destroy()
```

protocol version: `2024-11-05`。JSON-RPC over subprocess stdin/stdout（行分隔 JSON）。

### 3.2 McpToolAdapter 适配

```java
// 继承 agent-core-java Tool
public class McpToolAdapter extends Tool {
    public ToolCard getCard() { return new ToolCard(tool.name(), tool.description(), ...); }
    public Object invoke(args, kwargs) { return client.callTool(tool.name(), args); }
}
```

agent LLM 看到的是标准 ToolCard——不感知 MCP 协议。

### 3.3 SubAgentDispatcher

```java
SubAgentDispatcher.registerOnto(agent, toolName, description, executor)
  → SubAgentTool tool = new SubAgentTool(toolName, description, executor);
  → Runner.resourceMgr().addTool(tool, agentId);
```

父 agent LLM 看到 SubAgentTool 的 ToolCard，可像调普通工具一样委派。

## 4. 代码结构

见 `development.md` §2（mcp/subagent/explore 包）。

## 5. 运行流程

MCP 调用 / SubAgent 派发 / 探索流程见 `process.md` §4-6。

## 6. 配置与使用

接入示例见 §2.1（MCP / SubAgent / Explore 三段式）。补充完整 MCP 部署示例（含 checked exception 处理）：

```java
// 连接 MCP 服务器（try-with-resources 管理 subprocess 生命周期）
try (StdioMcpClient secClient = new StdioMcpClient(
        List.of("python", "-m", "mcp_sec_server"),
        Map.of("API_KEY", System.getenv("SEC_API_KEY")))) {
    secClient.start();                              // throws IOException
    McpToolRegistrar.registerOnto(agent, secClient); // throws Exception
} // 自动 close

// 注册 SubAgent（in-process 子 agent）
SubAgentDispatcher.registerOnto(agent, "deep-review", "深度复核子任务",
    (userInput, subGoal) -> reviewAgent.invoke(subGoal, null));
```

## 7. 当前限制

边界与显式排除见 FEAT-026 §5.2（MCP 仅 stdio / SubAgent 仅 in-process / 不继承父会话 / 非文本 content）。

## 8. 对 runtime / 集成方 要求

| 编号 | 要求 |
|---|---|
| R-1 | MCP subprocess 生命周期由宿主管理（start/close），EDPA autoconfig 不代管。 |
| R-2 | SubAgentExecutor 是宿主提供的闭包/bean，执行后端由宿主决定。 |
| R-3 | MCP 服务器的可移植性取决于其实现语言（Python/Node 需目标环境有运行时）。 |

## 9. 一致性

本文 §2-3 与 FEAT-026 事实要求逐条对应；MCP 协议遵从 spec 2024-11-05。
