---
level: L1-HLD
module: agents/pev
TAG:
  - overview
  - architecture-fact
  - module-boundary
status: active
updated: 2026-08-06
dependency:
  - ../features/FEAT-023-pev-selfheal-loop.md
  - logical.md
  - process.md
  - development.md
  - physical.md
  - scenarios.md
---

# PEV L1 架构概览

## 术语

本文档使用以下团队方法论术语（贯穿 9 个文档）：

- **承重（load-bearing）**：真正承载设计意图、且经反向验证为**非恒真**的断言/测试/契约。与"有调用/非空"式恒真弱断言（**假承重**）对立。PEV 的承重测试用"剥→RED"保证非恒真。
- **剥 token→RED / mutation-RED**：从断言中剥去唯一能区分期望与实际的关键 token（或剥一段实现）；若测试仍 GREEN 则断言恒真（假承重）；剥后应转 **RED** 才证其承载真实契约。
- **IFF（当且仅当）契约**：dispatch 的 RootCause→ReplanAction 映射是双向唯一的——不可恢复根因**只**→AcceptPartial，可恢复根因**只**→Local/GlobalReplan。
- **铁律①（诚实边界）**：不可达终态/能力诚实标 INCONCLUSIVE/N/A，不装能；不假承重。

## 目的

本文档给出 `agents/pev` 模块的 L1 高阶心智模型，概述模块目标、受众边界、问题领域和模块边界形态。它承接 FEAT-023 特性事实，并参照 `agent-runtime` / `agent-client` 的 L1 文档体系章节粒度展开。

本文不展开类级清单、接口签名、配置项、测试矩阵或错误处理表——这些进入 `logical.md`、`development.md`、`process.md`、`physical.md`、`scenarios.md` 或 L2 详细设计。本文事实来源为 `common/agents/pev` 的当前代码（origin/common），不引用任何外部同名实现。

## 模块目标

`agents/pev` 是建在 `agent-core-java:0.1.13` 的 `BaseAgent` 之上的**自包含 agent-service-app 模板**。它不是 runtime、不是 ext adapter，而是 agent-service-app 本身——一个可在单次 `invoke` 内跑完 `Plan → Execute → Verify → Diagnose → Dispatch` 自愈闭环的智能体实现。

当前模块目标包括：

- 作为 agent-service-app 的承重模板，把"verify 失败 → 诊断根因 → 选择补救"从 LLM 口算下沉为 sealed types + 纯函数 dispatch，使自愈逻辑可被 mock 单测穷举、可被独立审计。
- 以三个注入式阶段 SPI（Planner/Executor/Verifier）承载 LLM/工具/verifier 接入；kernel 决策核心（`PevKernel` 两个纯函数 + 3 个 sealed 接口（NodeResult/RootCause/ReplanAction）+ VerifyResult（record））零 LLM、零框架耦合，可被同级模式复用。
- 在相位边界（Plan/Execute/终态）提供 rail 缝合点，使认知 rail（Beta）与可观测扩展可组合挂载。
- 以 kernel-native trace（`PevTrace` + `PevTraceSink`）提供闭环终态的确定性投影，作为 sink/OTel/Micrometer 消费的缝合点。
- 刻意收窄配置（只 `maxRetries`），不为 speculative 灵活性加配置开关；能力由代码事实（sealed 穷举 + IFF 契约）保证，而非配置声明。

PEV 不拥有服务端 Task 生命周期、不暴露 HTTP/A2A 服务面、不做会话编排——这些由 `agent-runtime-java` 承接。PEV 只提供可被 runtime 调用的 `invoke`/`stream`。

## 架构概览

```text
PEV 自愈执行闭环（invoke 内同步跑完）

宿主注入 SPI              PEVAgent.invoke（同步闭环）                    出口

Planner  ─────▶  Plan ──▶ Execute ──▶ Verify
Executor ─────▶                          │
Verifier ─────▶                     isPassed?
                                      ├ YES ──▶ PASSED ──────────────▶ 装配输出
                                      └ NO
                                           │
                                     ┌─────▼─────┐
                                     │  kernel   │   纯函数 · 零 LLM
                                     │ diagnose  │   信号 → RootCause
                                     │ dispatch  │   RootCause → Action
                                     └─────┬─────┘
                                           │
                           ┌───────────────┼───────────────┐
                           ▼               ▼               ▼
                      AcceptPartial    LocalReplan    GlobalReplan
                      (不重试 · 终态)  (重做 failed)   (全量重 plan)
                           │               │               │
                           │         回到 Execute ◀────────┘
                           │
                  terminalGuard: maxRetries 截断（不死循环）

相位事件 ──▶ rail 缝合点（afterToolCall / afterInvoke）
终态投影 ──▶ PevTrace ──▶ PevTraceSink（logger / OTel / Micrometer）
```

三个出口：装配输出（invoke 返回值）、PevTrace（可观测）、相位事件（rail 横切）。kernel 决策核心（diagnose + dispatch）是纯函数、零 LLM——可被同级模式独立复用。

## 受众边界

| 受众 | 主要需求 |
|---|---|
| Agent 开发者 | 理解 PEV 闭环相位、三阶段 SPI 如何接线、何时短路/重试/降级、rail 如何挂载。 |
| 同级模式作者 | 理解 kernel（sealed types + 纯函数）的复用边界，派生新 agent-service-app 时不重复发明决策核心。 |
| 平台集成方 | 理解 PEV 如何被 runtime 包装为 A2A Task/SSE、rail 缝合点的相位事件形状、trace 投递语义。 |
| 架构评审者 | 判断 PEV 是否保持自包含控制流、是否守住 dispatch IFF 契约与诚实边界、是否避免夸大能力。 |
| 测试与验收团队 | 以 L1 建立心智模型，再进入 L2 定位承重测试矩阵（dispatch 各分支剥→RED、trace 四终态、sink 隔离）。 |

## 问题领域

`agents/pev` 解决的是裸 LLM agent 在工具调用/多步推理失败时**如何诚实、可解释地恢复**的问题。它不是 runtime，不是通用编排层。

1. **无差别重试把不可恢复当可恢复**

   坏设备（工具超时/网络/异常）不会因重试自愈；不可信 verifier（抛错/返回不可解析）的 FAILED 判定不可据以行动。把一切失败都重试，既浪费预算又掩盖根因。

2. **静默降级放弃可恢复工作**

   内容错误（plan/答案错了）本可由重做或重规划修复，提前降级等于放弃。

3. **LLM 自我报告不可信**

   "为什么失败"若由 LLM 口算，不可测试、不可审计、易自我合理化。

4. **假承重（弱断言恒真）**

   "有调用/非空"型弱断言恒真，掩盖未真正工作（reward hacking 的静态对应物）。

5. **失败循环不死循环**

   verify 重试必须有界，否则死循环。

6. **自愈机制本身不应成为新的不可观测面**

   诊断/dispatch 若是 invoke 局部变量消费的内部转移，外部观察者不可见。

> PEV 对每个问题的解法见 §模块目标、`logical.md` kernel 决策模型、FEAT-023 §5 行为语义。

## 与同级模式对比

| 维度 | PEV | ReAct | 纯 CoT / 对话 | Multi-agent |
|---|---|---|---|---|
| 失败处理 | verify → 诊断根因 → 差异化补救（重试/降级/重规划） | 无 verify，失败即结束或盲重试 | 无 | 部分（agent 间协调） |
| 可测试性 | ✅ sealed dispatch 可 mock 穷举 + 剥→RED | ◑ loop 难 mock（状态隐蔽） | ✅ 单步易测 | ❌ 交互复杂 |
| 开销 | ◑ verify + 诊断 = 额外调用 | 低（reason + act 循环） | 最低（单次） | 高（多 agent） |
| 诚实降级 | ✅ AcceptPartial + INCONCLUSIVE | ❌ 无降级语义 | ❌ | ◑ |
| 控制流可见 | ✅ PevTrace 五相位投影 | ❌ loop 内部不可见 | N/A | ◑ |
| 适用 | 承重决策、工具链、需可审计的失败恢复 | 通用 tool-use、对话式探索 | 知识问答、推理 | 并行/分工任务 |
| 不适用 | 简单问答（开销不值）、真流式 | 需失败恢复的承重场景 | 需要工具/多步 | 简单 + 低延迟 |

**核心取舍**：PEV 用额外 verify + 诊断开销，换取失败恢复的**可解释性 + 可测试性 + 可审计性**。适合"答错代价高"的承重场景（金融/合规/工程决策）；不适合"快比准重要"的轻量对话。

## 模块边界形态

`agents/pev` 是 L1 逻辑模块，也是当前代码仓中的 Maven module `common/agents/pev`。它可以被 runtime 包装为 A2A Task/SSE、被同级模式继承、被测试直接实例化，但这些形态不改变 L1 逻辑边界。

| 边界项 | PEV 负责 | PEV 不负责 | 事实下沉位置 |
|---|---|---|---|
| 自包含闭环 | `invoke` 单方法跑完 Plan→Execute→Verify→Diagnose→Dispatch。 | 不拆控制流到 handler override；不拥有服务端 Task 生命周期。 | `logical.md`, `process.md` |
| Kernel 决策核心 | `diagnoseRootCause`/`toReplanAction` 两纯函数 + 3 个 sealed 接口（NodeResult/RootCause/ReplanAction）+ VerifyResult（record）。 | 不调 LLM、不绑框架；不为同级模式加耦合。 | `logical.md` |
| 三阶段 SPI | Planner/Executor/Verifier 注入式接口。 | 不绑定特定 LLM SDK/工具/verifier 实现。 | `logical.md`, `development.md` |
| Rail 缝合点 | 四相位边界 fire 回调，`AFTER_INVOKE` 投递 `payload`+`trace`。 | 不定义 rail 业务语义（CriteriaVerificationRail/RootCauseRail 是示例 rail）。 | `process.md`, `logical.md` |
| Kernel-native trace | 终态产出 `PevTrace`，经 `PevTraceSink` 投递。 | 不做增量 trace/实时 span/早退告警；不拥有平台审计写入。 | `process.md`, `physical.md` |
| 配置 | 只 `PevConfig.maxRetries`。 | 不为真流式/多 agent/HTTP 接入提供"配 true 即可用"开关。 | `development.md` |
| 流式 | `stream` 降级为单 chunk。 | 不承诺 token-by-token 真流式。 | `process.md` |

跨模块依赖方向保持单向：PEV 只依赖公共 `agent-core-java:0.1.13` jar（`BaseAgent`/`Session`/`singleagent.rail.*`/`AgentCard`）；不依赖 runtime、engine adapter 或任何 LLM SDK。runtime 调用 PEV 的 `invoke`，PEV 不反向依赖 runtime。

## 当前状态与后续 L1 展开

当前 `agents/pev` 的 active 事实来自代码（origin/common）+ FEAT-023 特性 + 承重测试。L1 按与 `agent-runtime` 一致的 4+1 视图展开：

| 文件 | 作用 |
|---|---|
| `overview.md` | 本文件——模块定位、目标、受众、问题领域、边界形态。 |
| `logical.md` | 逻辑视图：领域对象（Plan/NodeResult/VerifyResult/RootCause/ReplanAction/PevTrace）、状态归属、kernel 决策模型、sealed 层次。 |
| `process.md` | 进程视图：invoke 相位执行流、Execute+Verify 循环、dispatch 分支、verifier 容错、trace emit、并发/隔离。 |
| `development.md` | 开发视图：Maven module 结构、包划分（agent/kernel/observability/rail）、依赖边界、SPI 实现/测试组织。 |
| `physical.md` | 物理视图：PEV 作为 runtime 内嵌 agent 的部署形态、sink 消费拓扑、无独立进程/网络边界。 |
| `scenarios.md` | 场景视图：直通/局部重做/全局重规划/设备降级/verifier 不可信/重试截断/rail/trace 九个关键旅程（TS-01～TS-09）。 |

L1 不展开类级签名与函数体——这些在 L2 `Feat-Func-023-pev-selfheal-loop.md`。
