---
level: L1-HLD
module: agents/edpa-alpha
TAG: [scenarios, technical-scenario, architecture-fact]
status: active
updated: 2026-08-15
dependency: [overview.md, logical.md, process.md, ../features/FEAT-025-edpa-cognitive-loop.md, ../features/FEAT-026-edpa-capability-extensions.md]
---

# EDPA-alpha L1 架构场景视图

## 目的

本文档从 EDPA-alpha 的 L1 定位反推技术场景，把 4+1 视图连接到可验证的运行路径。

## 场景边界

- EDPA overlay 在 ReActAgent 上，不改 ReAct 控制流本体。
- rail 是被动观测 + 主动 steering，不是控制流分支。
- MCP/SubAgent 经 Tool 适配接入，对 agent 透明。
- `enabled=false` 默认关闭；需显式开启。

## TS-01 主动收敛 fire（stall → steering）

### 场景目标
agent 多轮工具调用后覆盖率停滞，ProactiveConvergenceRail 检测并推 convergence steering。

### 参与组件
| 组件 | 角色 |
|---|---|
| ProactiveConvergenceRail | afterModelCall 计算 coverage + 检测 stall |
| GroundTruthVerifier | verify → 计算 coverage |
| EdpaKernel | toReplanAction → GlobalReplan |
| ReActAgent | 接收 pushSteering → 调整方向 |

### 基本路径
1. agent 经多轮 tool call，覆盖率停在低值（< coverageCritical）。
2. ProactiveConvergenceRail 检测 flatlined（stallWindow 轮 delta ≈ 0）。
3. edge-triggered：EdpaKernel → GlobalReplan → `ctx.pushSteering("覆盖率 X%，未满足：...")`。
4. agent 下一轮 reason 时看到 steering，调整方向。
5. 覆盖率回升（或 maxRetries 截断）。

### 验证关注点
- edge-triggered：停滞入口 fire 一次，不每轮 fire。
- coverageCritical 以下 + flatlined 才 fire；高覆盖率不 fire。
- steering 内容含覆盖率与方向调整建议 列表。

## TS-02 确定性验证命中（checker 纯计算）

### 场景目标
涉数值/逻辑的 criterion 被 DeterministicChecker 纯计算判定，不走 LLM。

### 参与组件
| 组件 | 角色 |
|---|---|
| GroundTruthVerifier | 匹配 checker |
| DeterministicChecker | matches + check 纯计算 |
| ClaimDeterministicTools | 示例：85% 共担 / 阈值校验 |

### 基本路径
1. agent 输出含数值判断（如"理赔 8500 元"）。
2. GroundTruthVerifier 逐条 criterion 匹配 checker。
3. "理赔金额符合85%共担" → ClaimDeductibleChecker.matches()=true（宿主侧示例实现，见 L2 §6；模块内不含该类）。
4. check() 纯计算（10000 × 85% = 8500？是 → null 通过 / 否 → Violation）。

### 验证关注点
- checker 零 LLM（纯函数：同输入同输出）。
- 不匹配的 criterion fall through 到 keyword。

## TS-03 MCP 工具调用

### 场景目标
agent 经 McpToolAdapter 调用外部 MCP 工具服务器。

### 参与组件
| 组件 | 角色 |
|---|---|
| StdioMcpClient | stdio JSON-RPC → subprocess |
| McpToolAdapter | Tool 适配（invoke → callTool） |
| MCP 服务器 subprocess | 执行工具（如 SEC EDGAR 检索） |

### 基本路径
1. agent LLM 决定调 MCP 工具。
2. McpToolAdapter.invoke(args) → StdioMcpClient.callTool(name, args)。
3. subprocess 执行 → 返回 content 文本。
4. agent 得到结果继续推理。

### 验证关注点
- agent 不感知工具是 MCP 远程的（McpToolAdapter 透明）。
- subprocess 崩溃 → McpRpcException → tool invoke 抛异常。
- close() 销毁 subprocess。

## TS-04 SubAgent 派发

### 场景目标
父 agent 经 SubAgentTool 委派子任务给子 agent。

### 参与组件
| 组件 | 角色 |
|---|---|
| SubAgentDispatcher | 注册 SubAgentTool 到父 agent |
| SubAgentTool | Tool 适配（invoke → SubAgentExecutor） |
| SubAgentExecutor | 执行后端（in-process 子 agent.invoke） |

### 基本路径
1. 父 agent LLM 决定委派子任务。
2. SubAgentTool.invoke(args) → SubAgentExecutor.execute(userInput, subGoal)。
3. in-process：子 agent.invoke(input) → 结果。
4. 父 agent 得到子 agent 结果继续推理。

### 验证关注点
- 子 agent context 经 args 手动传入（当前不自动继承父会话）。
- in-process 同步执行（在 tool invoke 线程）。

## TS-05 探索（tool 模式）

### 场景目标
agent 调 ExploreTool 调研后决策。

### 参与组件
| 组件 | 角色 |
|---|---|
| ExploreTool | 注册为可调用 Tool |
| Explorer/LlmExplorer | LLM 调研 → findings + 候选方案 |
| UserInputCaptureRail | 缓存首轮输入作为探索上下文 |
| ExploreBudget | 预算约束（maxRounds/timeout） |

### 基本路径
1. agent LLM 决定探索。
2. ExploreTool.invoke(topic) → Explorer.explore(topic, budget)。
3. LlmExplorer 用 LLM 调研 → ExplorationResult(findings, candidateApproaches)。
4. agent 得到 findings + 候选方案继续推理。

### 验证关注点
- 预算受限（maxRounds/maxSubAgents/timeout）。
- tool 模式 vs rail 模式互斥（exploreMode 配置）。

## TS-06 DataFlow OTel 追踪（已移除，MR !77）

### 场景状态
**N/A —— DataFlowObserverRail / OTel span 层已移除（MR !77）。** agent-core-ext-react-rails 结论甲实证 ext 层 OTel-as-source 错层；当前 EDPA 可观测性继承 agent-core-ext-react-rails 的 RailTelemetry（SteeringEvent "EXPLORE_FINDINGS" / "CONVERGENCE_STALL"），不自带 OTel / DataFlow 层。独立的工具调用数据流 span 追踪 deferred（宿主需观测时在 ext 层自行接入）。

## TS-07 多 rail 组合

### 场景目标
多个认知 rail 同时挂载，各司其职互不干扰。

### 参与组件
| 组件 | 角色 |
|---|---|
| ProactiveConvergenceRail | convergence 检测（afterModelCall, priority=70） |
| UserInputCaptureRail | 缓存输入（beforeModelCall） |
| RootCauseRail | DeviceFailure 遥测（onToolException + afterModelCall） |

### 基本路径
1. 宿主经 EdpaRails.registerOnto 显式装配认知 rail（autoconfig 只提供基础设施 Bean）。
2. agent invoke 时各 rail 在各自相位被动触发。
3. 每个 rail 独立工作，不改 ReAct 控制流。

### 验证关注点
- rail 间不互相依赖（除 ProactiveConvergenceRail 依赖 CriteriaVerifier）。
- priority 决定回调顺序（ProactiveConvergence=70）。
- rail 是 defense-in-depth，不是控制流分支。

## 场景覆盖

- TS-01～TS-07 覆盖 EDPA 认知 overlay（convergence/验证/rail 组合；TS-06 数据流 OTel 已移除 MR !77）+ 能力扩展（MCP/SubAgent/Explore）。
- 显式排除场景（远程 SubAgent/MCP SSE 传输/离线 GEPA）见 FEAT-025/026 §2。
