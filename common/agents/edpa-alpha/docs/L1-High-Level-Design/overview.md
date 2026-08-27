---
level: L1-HLD
module: agents/edpa-alpha
TAG:
  - overview
  - architecture-fact
  - module-boundary
status: active
updated: 2026-08-15
dependency:
  - ../features/FEAT-025-edpa-cognitive-loop.md
  - ../features/FEAT-026-edpa-capability-extensions.md
  - logical.md
  - process.md
  - development.md
  - physical.md
  - scenarios.md
---

# EDPA-alpha L1 架构概览

## 术语

- **承重（load-bearing）**：答错代价高、必须可验证的核心路径场景（区别于可试错的通用 tool-use）。剥→RED = 删掉被测实现后单测应变红（证明断言非恒真）；IFF 契约 = RootCause→ReplanAction 当且仅当映射；铁律① = 不可达能力诚实标 N/A 不装能。详见 logical.md §2.1。
- **覆盖率（coverage）**：success criteria 中已被满足的比例 = `1 - violations.size / criteria.size`。ProactiveConvergenceRail 追踪它。
- **停滞（stall）**：覆盖率在滑动窗口内 delta ≈ 0（flatlined）**且**低于 coverageCritical 阈值。意味着 agent 不再进步。
- **收敛 fire（convergence fire）**：停滞入口 edge-triggered 推 convergence steering，引导 agent 调整方向。不是每轮 fire。
- **确定性兜底（deterministic backstop）**：涉数值/逻辑/合规的判断用 DeterministicChecker 纯计算（零 LLM），不命中的才 keyword 验证。铁律：**规则 > LLM judge**。
- **DeepAgent**：本仓库对 agent-core-java `ReActAgent` 的业务称呼（上层 agent 概念），非独立类——EDPA 挂载对象就是 ReActAgent bean。

- **steering（转向注入）**：经 `ctx.pushSteering(feedback)` 写入待注入队列，由 agent-core-java 在下一轮 LLM 推理前注入 messages——agent 在下一轮 reason 时看到方向调整建议。这是 EDPA 唯一能影响 agent 决策的通道。
- **rail（认知 rail）**：钩入 ReActAgent 相位回调（beforeModelCall / afterModelCall / afterToolCall）的插件，被动观测 + 主动 steering，不改 ReAct 控制流本体。EDPA 的认知能力全部以 rail 形式叠加。

- **认知 overlay**：EDPA 不是自包含 loop（PEV 是）；它在 ReActAgent 的 reason+act 循环上**叠** rail + 验证 + 探索，不改 ReAct 控制流本体。

## 目的

本文档给出 `agents/edpa-alpha` 模块的 L1 高阶心智模型。EDPA 是 DeepAgent（ReAct）的**认知增强 overlay**——不替换 ReAct 循环，而是在其上叠加主动收敛、确定性验证、探索和数据流观测。

## 架构概览

```text
EDPA-alpha = ReActAgent + 认知 overlay + 能力扩展

  ┌─ 能力扩展（FEAT-026）─────────────────────────────────────────┐
  │  MCP 工具（StdioMcpClient → McpToolAdapter → Tool）            │
  │  SubAgent 派发（SubAgentDispatcher → SubAgentTool → Tool）     │
  │  Explore Tool（ExploreToolRegistrar → ExploreTool → Tool）     │
  └───────────────────────────────────────────────────────────────┘
                              │ 注册为 Tool
                              ▼
  ┌─ ReActAgent（agent-core-java）────────────────────────────────┐
  │  reason + act 循环（LLM 决策 → tool call → 观察 → 再循环）     │
  └───────────────────────────────────────────────────────────────┘
              ▲ beforeModelCall / afterModelCall / afterToolCall
              │
  ┌─ 认知 overlay（FEAT-025）─────────────────────────────────────┐
  │                                                                │
  │  EDPA 新建 rail（EdpaRails.registerOnto 显式装配）：           │
  │  ├ SteeringProvisionRail ── 绑定 steering 队列（issue-#13，    │
  │  │    唯一 beforeInvoke 覆写者，hook 隔离先于全部消费者）       │
  │  ├ ProactiveConvergenceRail ── afterModelCall: coverage →     │
  │  │    stall 检测 → edge-triggered convergence steering         │
  │  ├ ExploreRail（rail）/ ExploreTool（tool）── 探索              │
  │  └ UserInputCaptureRail ── 缓存首轮输入（tool 模式）            │
  │                                                                │
  │  复用 react-rails（同一装配门面注册）：                          │
  │  ├ CriteriaReplanBridgeRail ── criteria 验证 + replan 桥接     │
  │  ├ ReplanRail + ReplanTool ── 重试上限 + 工具                  │
  │  └ RootCauseRail ── DeviceFailure 降级门                         │
  │                                                                │
  │  GroundTruthVerifier + DeterministicChecker ── 验证层:         │
  │    数值/规则 → 纯计算（零 LLM）; 不命中 → keyword 兜底          │
  │                                                                │
  │  ✗ DataFlowObserverRail ── 已移除（MR !77），EDPA 不自带        │
  │    OTel/DataFlow 层（可观测性继承 react-rails RailTelemetry）   │
  │                                                                │
  │  EdpaKernel ── RootCause→ReplanAction IFF 映射（见 §术语）  │
  └───────────────────────────────────────────────────────────────┘
              │
              ▼  Spring Boot @AutoConfiguration
  EdpaRails.registerOnto（宿主显式装配，单一真源）
  EdpaAutoConfiguration（基础设施 Bean + 零命中 WARN 探测）
  EdpaProperties（enabled / exploreMode / criteria / maxReplan / convergence...）
```

## 模块目标

`agents/edpa-alpha` 是建在 agent-core-java 的 ReActAgent + react-rails 之上的**认知增强 agent-service-app**。它不是自包含 loop（那是 PEV），而是增强 ReAct 的循环。

- **主动收敛**：agent 知道"什么时候够好了"——ProactiveConvergenceRail 追踪覆盖率、检测停滞、主动推 steering 引导收敛。
- **确定性验证**：涉数值/逻辑/合规的判断用规则计算（DeterministicChecker），不走 LLM-as-judge。
- **探索能力**：agent 在行动前可选调研（ExploreRail/Tool），预算受限。
- **MCP 集成**：运行时连接外部工具服务器（MCP），扩展工具集。
- **SubAgent 派发**：父 agent 可委派子任务给子 agent（作为可调用 Tool）。
- **数据流观测**：⚠ OTel span 层已移除（MR !77）——EDPA 不自带 OTel/DataFlow 层，可观测性继承 react-rails 的 RailTelemetry（SteeringEvent `EXPLORE_FINDINGS` / `CONVERGENCE_STALL`）；独立工具调用数据流 span 追踪 deferred。

EDPA 不替换 ReAct 控制流、不定义 HTTP/A2A 服务面（runtime 承接）。

## 受众边界

| 受众 | 主要需求 |
|---|---|
| Agent 开发者 | 理解 EDPA 如何在 ReActAgent 上挂 rail、如何写 DeterministicChecker、如何配 convergence。 |
| 工具提供者 | 理解 MCP 工具如何被适配为 agent Tool、SubAgent 如何注册。 |
| 同级模式作者 | 理解 EdpaKernel的复用边界。 |
| 平台集成方 | 理解 EdpaRails.registerOnto 显式装配与 autoconfig 的基础设施 Bean 边界（config-gated）。 |
| 架构评审者 | 判断 EDPA 是否保持 overlay 定位（不改 ReAct 本体）、确定性兜底是否守住。 |

## 问题领域

1. **ReAct 不知道何时收敛**——要么过早终止，要么死循环到 max retries。
2. **LLM-as-judge 对数值/合规不可靠**——85% 共担比例、≥50000 阈值该算不该猜。
3. **工具集编译期固定**——无法运行时连接外部工具（如 SEC EDGAR 检索）。
4. **复杂任务无法分解**——父 agent 无法委派子任务给专门的子 agent。
5. **工具调用数据流不可见**——缺乏 OTel 级追踪（⚠ EDPA 自带 OTel 层已移除 MR !77，独立数据流 span 追踪 deferred 至宿主 ext 层）。

## 与同级模式对比

| 维度 | EDPA-alpha | PEV | PDCA（经典） | 裸 ReAct | Multi-agent |
|---|---|---|---|---|---|
| 控制流 | ReAct overlay（不改本体） | 自包含 PEV loop | 人工驱动 4 阶段 | reason+act 循环 | 多 agent 协调 |
| Plan | ReAct LLM 规划（reason） | ✅ Plan 阶段 | ✅ Plan（人工） | ✅ reason | ✅ |
| Do | ReAct tool call（act） | ✅ Execute 阶段 | ✅ Do（执行） | ✅ act | ✅ |
| Check | ✅ coverage 追踪 + stall 检测 | ✅ verify（内嵌） | ✅ Check（人工/指标） | ❌ 无 | ◑ |
| Act | ✅ steering 推收敛 | ✅ dispatch IFF | ✅ Act（改进行动） | ❌ 无 | ◑ |
| 确定性验证 | ✅ DeterministicChecker 纯计算 | ✅ sealed dispatch | ◑ 指标 | ❌ | ◑ |
| 外部工具 | ✅ MCP 运行时扩展 | ◑ SPI 注入 | N/A | 固定工具集 | ◑ |
| 任务分解 | ✅ SubAgent 派发 | ❌（单 agent） | ❌ | ❌ | ✅ |
| 自动化 | ✅ rail 自动 Check+Act | ✅ invoke 内自动 | ❌ 人工驱动 | ❌ | ◑ |
| 开销 | ◑（overlay rail） | ◑（verify + 诊断） | 低（人工） | 低 | 高 |
| 适用 | 承重 + 外部工具/任务分解 | 承重 + 自包含闭环 | 持续改进/质量管理 | 通用 tool-use | 并行/分工 |

> 图例：✅ 原生支持 / ◑ 部分·需配置或复用 / ❌ 不支持

### EDPA vs PEV

PEV 是自包含 Plan→Execute→Verify→Diagnose→Dispatch 闭环（**替换** ReAct）；EDPA 是 ReAct 上的**被动 overlay**（不改 reason+act 本体，只叠 rail + 验证）。两者 kernel 是同逻辑独立拷贝（不互依赖）。

### EDPA vs 裸 ReAct

裸 ReAct 无 Check（不知道何时够好）、无 Act（失败无自愈）。EDPA 叠 coverage 追踪 + stall 检测（自动 Check）+ steering 推收敛（自动 Act）+ 确定性验证（数值/规则零 LLM）。

### EDPA vs PDCA

EDPA 把 PDCA 的 **Check-Act 自动化**嵌入 ReAct 循环：ProactiveConvergenceRail 是自动 Check（coverage 追踪 + stall 检测），steering 推 convergence 是自动 Act。**优势**：全自动 + 实时 + 确定性验证（零 LLM）。**劣势**：Check 受限于预定义 criteria、Act 不能战略级 reframe。适合 criteria 可预定义 + 答错代价高的承重场景。

## 模块边界形态

| 边界项 | EDPA 负责 | EDPA 不负责 |
|---|---|---|
| 认知 overlay | ProactiveConvergenceRail + GroundTruthVerifier + ExploreRail（DataFlowObserverRail 已移除 MR !77） | 不改 ReAct 控制流本体 |
| 确定性验证 | DeterministicChecker SPI + GroundTruthVerifier | 不用 LLM-as-judge 判数值/合规 |
| MCP 集成 | StdioMcpClient + McpToolAdapter | 不承诺 SSE/HTTP MCP 传输 |
| SubAgent 派发 | SubAgentDispatcher + SubAgentTool | 不承诺远程 A2A delegate |
| 探索 | Explorer SPI + ExploreTool/Rail | 不承诺离线 GEPA/DSPy 优化 |
| kernel | EdpaKernel | 不依赖 PEV 模块 |

跨模块依赖：EDPA 依赖 `agent-core-java`（ReActAgent/BaseAgent/rail）+ `react-rails`（CriteriaVerifier/Violation）；不依赖 PEV 模块（kernel 是独立拷贝），不自带 OTel 依赖（MR !77 已移除 `opentelemetry-api`）。

## 当前状态与后续 L1 展开

| 文件 | 作用 |
|---|---|
| `overview.md` | 本文件——模块定位、架构图、对比、边界。 |
| `logical.md` | 领域对象（EdpaKernel/Verification/Explorer/MCP/SubAgent）+ 责任面 + 状态模型。 |
| `process.md` | ReAct 循环 + rail 钩入 + convergence 流程 + MCP 调用 + SubAgent 派发。 |
| `development.md` | module/包/依赖红线（react-rails + Spring autoconfig；OTel 依赖已移除 MR !77）/SPI/测试。 |
| `physical.md` | 部署（Spring Boot app + MCP stdio subprocess；OTel 层已移除 MR !77，经 RailTelemetry）/凭据/国产化。 |
| `scenarios.md` | convergence fire / 确定性验证 / MCP 调用 / SubAgent 派发 / 探索 / rail 组合。 |
