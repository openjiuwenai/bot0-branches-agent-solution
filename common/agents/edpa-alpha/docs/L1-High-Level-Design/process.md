---
level: L1-HLD
module: agents/edpa-alpha
TAG: [process-view, control-flow, architecture-fact]
status: active
updated: 2026-08-08
dependency: [overview.md, logical.md, ../features/FEAT-025-edpa-cognitive-loop.md]
---

# EDPA-alpha L1 架构进程视图

## 1. 进程视图定位

进程视图描述 EDPA 的认知 rail 如何钩入 ReActAgent 的 reason+act 循环、convergence 检测流程、MCP 调用流程和 SubAgent 派发流程。

## 2. ReAct 循环 + rail 钩入点

```text
ReActAgent.invoke
 │
 ├─ beforeModelCall ◀── UserInputCaptureRail（首轮：缓存用户输入）
 │                      ExploreRail（rail 模式：记录探索轮次到 extra）
 ├─ LLM 推理（reason）──▶ tool call 决策
 ├─ afterModelCall ◀─── ProactiveConvergenceRail（convergence 检测，见 §3）
 │                      ExploreRail（rail 模式：探索 + pushSteering 注入 findings）
 ├─ tool call ─────────▶ McpToolAdapter.invoke / SubAgentTool.invoke / ExploreTool / 内置 Tool
 ├─ afterToolCall ◀──── RootCauseRail（DeviceFailure 降级门）
 └─ 循环（回到 beforeModelCall）或终止（forceFinish / maxRetries）
```

关键：EDPA 的 rail 是**被动观测 + 主动 steering**，不改 ReAct 控制流本体。rail 经 `registerRail` 注册，由 agent-core-java 在相位边界回调。

> 注：DataFlowObserverRail（OTel span 记录数据流）**已移除（MR !77）**——agent-core-ext-react-rails 结论甲实证 ext 层 OTel-as-source 错层。EDPA 可观测性继承 agent-core-ext-react-rails 的 RailTelemetry（SteeringEvent `EXPLORE_FINDINGS` / `CONVERGENCE_STALL`），**不自带 OTel / DataFlow 层**。

## 3. Convergence 检测流程

```text
afterModelCall（每个 tool round 后）
 ├─ 提取 accumulatedToolResults（从消息历史累积工具返回）
 ├─ GroundTruthVerifier.verify(criteria, "", toolResults) → List<Violation>
 ├─ coverage = 1 - violations.size / criteria.size
 ├─ coverageHistory.addLast(coverage)（滑动窗口 stallWindow+1）
 ├─ isFlatlined?（最近 stallWindow 轮 delta ≈ 0）
 │   └─ YES && coverage < coverageCritical → stalled
 │       └─ edge-triggered（首次停滞入口，非每轮）:
 │           ├─ EdpaKernel.toReplanAction(PlanOrAnswerError, feedback) → GlobalReplan
 │           └─ ctx.pushSteering(convergenceFeedback)
 │               └─ feedback = "【主动收敛】覆盖率 X%，阈值 Y%，连续 N 轮无进展" → 引导 agent 调整方向
 └─ 更新 wasStalled
```

- **edge-triggered**：停滞入口 fire 一次（避免每轮干扰）。pushSteering 把 feedback 写入 steering 队列，由 agent-core-java 的 injectPendingSteering 在下一轮 LLM 推理前注入 messages——agent 在下一轮 reason 时看到方向调整建议。
- **coverageCritical = 0.34**：覆盖率低于此值 + flatlined 才算停滞。高覆盖率时即使 flat 也不 fire（agent 可能已经做完了）。

## 4. MCP 调用流程

```text
agent LLM 决定调 MCP 工具
 └─ McpToolAdapter.invoke(args)
     └─ StdioMcpClient.callTool(toolName, args)
         └─ stdio JSON-RPC → subprocess → MCP 服务器
             └─ 返回 content 文本
     └─ 返回文本给 agent（作为 tool call 结果）
```

MCP subprocess 生命周期由宿主管理（`McpClient.close()`）；EDPA autoconfig 不自动管理。

## 5. SubAgent 派发流程

```text
agent LLM 决定委派子任务
 └─ SubAgentTool.invoke(args)
     └─ SubAgentExecutor.execute(userInput, subGoal)
         └─ in-process：子 agent.invoke(input) → 结果
         └─ 未来：A2A delegate → 远端 agent（OUT）
     └─ 返回子 agent 结果给父 agent
```

诚实边界：当前是最简路径——context 经 tool args 手动传入，不自动继承父 agent 会话状态。

## 6. 探索流程（两种模式）

### tool 模式（默认）
```text
agent LLM 决定探索
 └─ ExploreTool.invoke(topic)
     └─ Explorer.explore(topic, budget)
         └─ LlmExplorer：LLM 调研 → findings + candidateApproaches
     └─ 返回 ExplorationResult 给 agent
```

### rail 模式
```text
afterModelCall（每个 reason 后自动）
 └─ ExploreRail：检查 exploreRound < budget.maxRounds
     └─ YES → Explorer.explore(userInput, budget) → 注入 findings 到上下文
     └─ NO → 跳过（预算耗尽）
```

## 7. 并发与线程模型

| 项 | 约束 |
|---|---|
| rail 回调 | 在 ReActAgent.invoke 的调用线程同步执行（agent-core-java 保证）。 |
| MCP subprocess | StdioMcpClient 管理 subprocess 的 stdin/stdout；JSON-RPC 同步（callTool 阻塞等响应）。 |
| SubAgent | in-process 同步执行（在 tool invoke 线程）。 |
| RailInvocationState | per-invocation 隔离（经 ctx 隔离，不跨 invoke 共享）。 |

## 8. 关键运行时不变量（非直觉）

- **convergence 是 edge-triggered**——停滞入口 fire 一次，不是每轮都推 steering（容易误以为每轮推）。
- **DeterministicChecker 必须零 LLM**——在 checker 内调 LLM 破坏确定性兜底承诺。
- **MCP subprocess 生命周期不由 autoconfig 管理**——宿主必须 close，否则泄漏 subprocess。

## 9. 与其他视图的衔接

- 领域对象、责任面、状态模型：`logical.md`。
- 包/依赖红线/SPI/测试：`development.md`。
- 部署/MCP subprocess/OTel/国产化：`physical.md`。
- 技术场景：`scenarios.md`。
- 详细设计：L2 `Feat-Func-025` + `Feat-Func-026`。
