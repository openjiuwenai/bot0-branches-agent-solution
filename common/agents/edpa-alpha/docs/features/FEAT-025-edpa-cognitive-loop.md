---
scope: v0.1.0
module: agents/edpa-alpha
feature_type: functional
feature_id: FEAT-025
status: active
updated: 2026-08-08
---

# EDPA 认知增强闭环（DeepAgent + Proactive Convergence + 确定性验证）

## 1. 特性定位

FEAT-025 定义 `agents/edpa-alpha` 作为 **DeepAgent（ReAct）的认知增强 overlay** 的事实模板：在 agent-core-java 的 ReActAgent reason+act 循环上，叠加主动收敛检测（ProactiveConvergenceRail）、确定性验证层（GroundTruthVerifier + DeterministicChecker）、探索能力（ExploreRail/Tool）（数据流观测层已于 MR !77 移除），使 ReAct agent 具备"知道什么时候够好了"和"用规则而非 LLM 判断数值/合规"的能力。

本特性解决的问题是：裸 ReAct agent 在多步工具调用中缺乏**收敛感知**（不知道什么时候该停，要么过早终止要么死循环到 max retries）和**确定性验证**（数值/逻辑/合规判断交给 LLM-as-judge 不可靠）。EDPA 用覆盖率追踪 + 停滞检测 + 确定性 checker SPI 解决这两个问题。

本特性只定义 EDPA 作为 ReActAgent 认知 overlay 的核心行为。MCP 工具集成、SubAgent 派发、Explore tool 注册等能力扩展由 FEAT-026 承接。EDPA 不是自包含 loop（那是 PEV），它增强的是 ReAct 的循环。

本特性面向以下角色：

- **Agent 开发者**：理解 EDPA 如何在 ReActAgent 上挂载认知 rail、如何写 DeterministicChecker、如何配置 convergence。
- **同级模式作者**：理解 EDPA kernel的复用边界。
- **平台集成方**：理解 EdpaAutoConfiguration 如何经 BeanPostProcessor 自动给 ReActAgent 挂 rail。
- **测试与验收团队**：按 convergence fire / 确定性验证命中 / stall 检测设计验证场景。

## 2. 当前版本能力要求

| 能力 | 要求级别 | 事实要求 |
|---|---|---|
| ReActAgent 认知 overlay | MUST | EDPA 必须经 BeanPostProcessor 给每个 ReActAgent bean 自动注册认知 rail（config-gated，`enabled=false` 默认关闭）。 |
| 主动收敛检测 | MUST | ProactiveConvergenceRail 必须在每个 tool round 后计算 success criteria 覆盖率，追踪滑动窗口覆盖率历史，检测停滞（flatlined stallWindow 轮且 coverage < coverageCritical），在停滞入口（edge-triggered）推 convergence steering。 |
| 确定性验证层 | MUST | GroundTruthVerifier 必须先匹配 DeterministicChecker（零 LLM，纯计算），不命中的 criteria 才 fall through 到 keyword 验证。 |
| DeterministicChecker SPI | MUST | 必须提供 SPI 接口，允许宿主注入领域特定 checker（如理赔 85% 共担、医疗≥50000 阈值），checker 声明它 own 哪些 criteria（`matches`）并确定性校验（`check`）。 |
| 探索能力 | MUST（tool 模式注册见 FEAT-026） | 必须支持两种探索模式：tool 模式（ExploreTool 注册为可调用工具）和 rail 模式（ExploreRail 钩 afterModelCall（探索注入） 注入探索结果）。预算受限（maxRounds/maxSubAgents/timeout）。 |
| EdpaKernel 决策核心 | MUST | 必须提供 EdpaKernel.toReplanAction（RootCause→ReplanAction IFF 映射），是 PEV kernel 的独立拷贝。 |
| 数据流观测 | —（已移除） | DataFlowObserverRail（MR !77 移除：ext 层 OTel-as-source 错层；EDPA 无自带 OTel/DataFlow 层，deferred）。 |
| 用户输入捕获 | MUST | UserInputCaptureRail 必须缓存首轮用户输入，供 ExploreTool 作为探索上下文。 |
| 配置 | MUST | EdpaProperties 必须暴露：enabled / exploreMode / exploreRounds / maxSubagents / exploreTimeout / criteria / maxReplan / proactiveConvergenceEnabled / proactiveConvergenceStallWindow。 |
| LLM 响应提取 | SPI（内部工具） | LlmResponseExtractor 必须跨 provider 提取 LLM 响应内容（兼容不同 SDK 的 content 嵌套差异）。 |
| 多 agent 编排 | OUT | 不承诺核心 EDPA 内置多 agent fan-out（SubAgent 派发属 FEAT-025）。 |
| 自包含 PEV 闭环 | OUT | EDPA 不替换 ReAct 为 PEV loop；它增强 ReAct。 |

## 3. 外部接口与入口要求

| 接入面 | 类型 | 输入要求 | 输出要求 | 约束 |
|---|---|---|---|---|
| EdpaAutoConfiguration | Spring @AutoConfiguration | EdpaProperties 配置 | BeanPostProcessor 自动给 ReActAgent 挂 rail | `enabled=false` 默认关闭。 |
| DeterministicChecker | SPI | `matches(criterion)` + `check(criterion, output, history)` | `Violation` 或 null（通过） | 零 LLM，纯函数；同输入同输出。 |
| Explorer | SPI | `explore(topic, budget)` | `ExplorationResult(findings, candidateApproaches)` | 预算受限。 |
| CriteriaVerifier | SPI | `verify(criteria, output, decisionHistory)` | `List<Violation>` | GroundTruthVerifier 是默认实现。 |
| EdpaProperties | 配置 | YAML/properties | — | 见 §2 配置项。 |

## 4. 场景

技术场景见 `scenarios.md` TS-01/02/05/06（convergence fire / 确定性验证 / 探索 / 数据流）。



## 5. 行为语义与边界

### 5.1 核心行为语义

#### 5.1.1 主动收敛语义

- ProactiveConvergenceRail 钩 `afterModelCall`（每个 tool round 后）。
- 计算：`coverage = 1 - violations.size / criteria.size`（覆盖率 = 已满足的 criteria 比例）。
- 追踪：滑动窗口 `stallWindow+1` 轮的 coverage 历史。
- 停滞判定：`isFlatlined`（最近 stallWindow 轮的 coverage delta ≈ 0）**且** `coverage < coverageCritical`（默认 0.34）。
- 停滞入口（edge-triggered，非每轮 fire）：诊断 PlanOrAnswerError → EdpaKernel.toReplanAction → GlobalReplan → `ctx.pushSteering(convergenceFeedback)`。
- convergenceFeedback 内容：当前覆盖率% + 阈值% + 连续无进展轮数 → 引导 agent 调整方向。

#### 5.1.2 确定性验证语义

- GroundTruthVerifier 对每条 criterion 先匹配 DeterministicChecker（`matches` 方法）。
- 匹配到 → 纯计算校验（`check` 方法），零 LLM。
- 未匹配 → 收集后 fall through 到 keyword 验证。
- 这是"**规则 > LLM judge**"铁律的落地：涉及数值/逻辑/合规的判断优先用确定性计算。

#### 5.1.3 探索语义

- tool 模式：ExploreTool 注册为 agent 可调用的工具；agent 主动决定何时探索；UserInputCaptureRail 缓存首轮输入作为探索上下文。
- rail 模式：ExploreRail 在 afterModelCall 探索 + pushSteering 注入 findings（agent 下一轮 reason 时看到）。
- 两种模式互斥，由 exploreMode 配置选择。

### 5.2 显式边界与不承诺项

| 边界 | 当前版本不承诺 |
|---|---|
| 多 agent 编排 | SubAgent 派发属 FEAT-025。 |
| MCP 工具集成 | 属 FEAT-025。 |
| 自包含 PEV 闭环 | EDPA 不替换 ReAct；它增强。 |
| LLM-as-judge | 涉数值/逻辑/合规的判断**不用** LLM（铁律：规则 > LLM judge）。 |

## 6. 对下游设计与实现的约束

- L1/L2 设计必须把 convergence 检测 + 确定性验证作为 EDPA 核心事实，不得降级为实现细节。
- DeterministicChecker 必须是零 LLM 纯函数；违反（在 checker 内调 LLM）破坏确定性兜底承诺。
- ProactiveConvergenceRail 的 stall 检测是 edge-triggered（停滞入口 fire 一次），不是每轮 fire——避免每轮推 steering 干扰 agent。
- EdpaKernel（独立拷贝，见 development.md §3.1）。
- 任何对 convergence 算法、stall 窗口语义、checker SPI 的新增承诺，必须先回本特性文档更新事实要求。

## 7. 关联文档

- `L1-High-Level-Design/{overview,logical,process,development,physical,scenarios}.md`（L1 4+1 视图）
- `L2-Low-Level-Design/{README,Feat-Func-025-edpa-cognitive-loop}.md`（L2 详细设计）
- FEAT-025（EDPA 能力扩展：MCP + SubAgent）
- FEAT-023（PEV 自愈执行闭环——EDPA kernel 的逻辑来源）
