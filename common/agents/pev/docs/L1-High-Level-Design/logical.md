---
level: L1-HLD
module: agents/pev
TAG:
  - logical-view
  - domain-model
  - architecture-fact
  - sealed-types
status: active
updated: 2026-08-08
dependency:
  - overview.md
  - process.md
  - development.md
  - ../features/FEAT-023-pev-selfheal-loop.md
---

# PEV L1 架构逻辑视图

## 1. 逻辑视图定位

`agents/pev` 是自包含 agent-service-app。逻辑视图描述该闭环内部的领域对象、逻辑责任面、状态模型、kernel 决策模型、sealed 类型层次和逻辑依赖方向。

本视图回答：

- 用户输入进入 PEV 后被抽象成哪些领域对象（Plan/NodeResult/VerifyResult/RootCause/ReplanAction/PevTrace）。
- PEV 内部如何按逻辑责任面分层（闭环主体 / 决策核心 / 可观测 / 横切 rail）。
- 闭环运行态与终态投影的状态模型与状态归属。
- 根因诊断与 dispatch 如何由 sealed 类型 + 纯函数承载，与 LLM 自报告解耦。
- PEV 与 `agent-core-java`、runtime 的逻辑依赖方向如何保持隔离。

## 2. 领域对象模型

### 2.1 Plan 与执行节点

`Plan` 是 Planner 阶段产物，`PlanNode` 是其组成单元。PEV 不规定节点粒度（一步推理 / 一个工具调用 / 一个子目标由实现决定），只要求节点带稳定 `id` 供 verify/diagnose/replan 引用。

```text
PevComponents.Plan
├── goal              NL 目标
└── nodes             List<PlanNode>

PevComponents.PlanNode
├── id                节点稳定标识（verify/diagnose/replan 引用键）
└── description       节点描述（Executor 输入；LocalReplan 注入 correction feedback）
```

### 2.2 执行结果：NodeResult（sealed 三态）

`NodeResult` 是单个节点在一个 Execute 超步内的终态。sealed 三态使 verifier 和 diagnoser 看到的结果变体是封闭、显式的。

```text
NodeResult (sealed)
├── Success(Object value)                     节点完成，携带返回值
├── DeviceFailure(nodeId, error, isTimeout)   工具/基础设施失败
└── VerifierFailure(nodeId, reason)           Verifier 判定未达标
```

`DeviceFailure` 是 `diagnoseRootCause` 识别不可恢复根因的关键信号：节点结果图里出现 `DeviceFailure` 且与 verifier failed 集合相交 → `RootCause.DeviceFailure`。

### 2.3 Verify 判定：VerifyResult

`VerifyResult` 是 verifier 的结构化判定，是 diagnose 的主要输入。它携带两个独立的"感知不可信"信号（分开保留，让 diagnose 能区分 verifier **为什么**不可信）：

```text
PevKernel.VerifyResult
├── isPassed           是否通过
├── failedNodes        失败节点集合
├── feedback           反馈（注入 LocalReplan/GlobalReplan 的 correction）
├── hasParseFailure    verifier 返回了不可解析输出（returned but unparseable）
└── hasThrown          verifier 抛了 RuntimeException（verify threw）
```

`hasThrown` 与 `hasParseFailure` 分离：`hasThrown` 由 PEVAgent 的 catch 块置位，`hasParseFailure` 由返回值不可解析置位；`hasThrown` 压倒 `hasParseFailure`（抛错意味着无返回值可解析）。

### 2.4 根因：RootCause（sealed 三态）

`RootCause` 是 `diagnoseRootCause` 的产物，回答 verify **为什么**失败。三态按信号确定性优先级排列（非 LLM 自报告）：

```text
RootCause (sealed)
├── PerceptionUnreliable(isVerifierThrown)   verifier 自身不可信
├── DeviceFailure(nodes)                      工具/基础设施节点坏了
└── PlanOrAnswerError(nodes)                  设备与感知都正常，计划/答案内容错了
```

### 2.5 动作：ReplanAction（sealed 三态）

`ReplanAction` 是 `toReplanAction` 的产物，回答**做什么**。与 `RootCause` 刻意分两层（why 与 what 分离），携带动作所需数据：

```text
ReplanAction (sealed)
├── LocalReplan(failedNodes, feedback)    只重做失败节点 + 注入 feedback
├── GlobalReplan(feedback)                丢弃 plan，全量重规划
└── AcceptPartial(reason)                 停止循环，诚实返回 partial/degraded
```

### 2.6 终态投影：PevTrace

`PevTrace` 是一次 invoke 的确定性投影，是闭环的**终态副产品**（非 enforcer 转移的并行埋点）。4 of 5 `Phase` 直接包装 kernel sealed 类型；`Planned` 用 `NodeSnapshot` 本地投影（见 §7.1）。

```text
PevTrace
├── phases            List<Phase>（Planned/Executed/Verified/Diagnosed/Dispatched）
├── terminalReason    PASSED | ACCEPT_PARTIAL | MAX_RETRIES_EXCEEDED | INCONCLUSIVE
└── verifyIterations  verify 评估次数

Phase (sealed)
├── Planned(goal, nodes) / Executed(stepResults) / Verified(verdict)
├── Diagnosed(cause)
└── Dispatched(action)
NodeSnapshot (observability-local: id + description, eager-copied from PlanNode)
```

## 3. 逻辑责任面

PEV 内部按四个逻辑责任面分层，每面有单一职责、经明确边界通信、可独立理解。

### 3.1 分层总览

```text
┌─────────────────────────────────────────────────┐
│  rail（横切认知 / Beta）                          │  相位事件观测；不改控制流
├─────────────────────────────────────────────────┤
│  agent（闭环主体）                                │  invoke/runVerifyLoop/dispatch/emitTrace
├─────────────────────────────────────────────────┤
│  observability（trace 投影）            │  PevTrace 投影 + PevTraceSink 投递
├─────────────────────────────────────────────────┤
│  kernel（决策核心）                               │  diagnoseRootCause + toReplanAction（纯函数）
│  + sealed types（NodeResult/RootCause/...）       │  零 LLM、零框架耦合
└─────────────────────────────────────────────────┘
        │ 只依赖 agent-core-java:0.1.13
        ▼
  BaseAgent / Session / rail.* / AgentCard
```

### 3.2 agent：闭环主体

`PEVAgent` 是闭环的唯一编排者。职责：invoke 相位推进、Execute+Verify 循环、sealed dispatch 分支、feedback 注入、trace emit。它消费 kernel 纯函数但不持有决策逻辑——决策是 kernel 的事，agent 只执行。三阶段 SPI（Planner/Executor/Verifier）是 agent 责任面的注入点，承载 LLM/工具/verifier 接入。

### 3.3 kernel：决策核心

`PevKernel` 是两个纯函数（`diagnoseRootCause`/`toReplanAction`）+ 3 个 sealed 接口（NodeResult/RootCause/ReplanAction）+ `VerifyResult`（record）。职责：把确定性信号映射为根因，把根因映射为动作。（`PevTrace.Phase` 是 observability 层的 sealed 接口，见 §3.4/§6，非 kernel。）零 LLM、零框架耦合——可被同级模式独立复用。kernel 不反向依赖 agent（agent 调 kernel，kernel 不调 agent）。

### 3.4 observability：kernel-native trace

`PevTrace` + `PevTraceSink`。职责：把闭环五相位投影为终态副产品，经 sink 投递给宿主消费。trace 不进 invoke 输出、不反馈控制流——它是只读投影。sink 实例 scope、FutureTask 桥隔离。

### 3.5 rail：横切认知（Beta）

`CriteriaVerificationRail`/`RootCauseRail` 是示例 rail。职责：在相位事件被动触发，做 defense-in-depth 校验或遥测。rail 与 PEV 内部 verify/diagnose 独立，可被同级模式复用，不改控制流。

## 4. 状态模型

### 4.1 VerifyResult 信号态

verifier 输出携带两个独立的"感知不可信"信号，状态如下：

```text
verify 调用
 ├─ 正常返回         → isPassed + failedNodes + feedback
 ├─ 返回 null        → hasParseFailure=true（降级）
 ├─ 返回不可解析     → hasParseFailure=true（降级）
 └─ 抛 RuntimeException → hasThrown=true（降级，压倒 hasParseFailure）
```

两种降级态都触发 `PerceptionUnreliable`，不再走正常 diagnose 路径。

### 4.2 RootCause 状态转移（diagnoseRootCause）

```text
VerifyResult + node 结果图 + execFailedNodes
 │
 ├─ hasThrown?               → PerceptionUnreliable(isVerifierThrown=true)   [优先级 1]
 ├─ hasParseFailure?         → PerceptionUnreliable(false)                   [优先级 2]
 ├─ device ∩ failedNodes?    → DeviceFailure(hit)                            [优先级 3]
 └─ else                     → PlanOrAnswerError(verify.failedNodes)         [优先级 4]
```

`device` = node 结果图里值为 `DeviceFailure` 的 nodeId ∪ execFailedNodes。与 verify.failedNodes 取交——交叉校验，防丢失侧信道。

### 4.3 ReplanAction 状态转移（toReplanAction，IFF）

```text
RootCause
 ├─ DeviceFailure           → AcceptPartial          [不重试]
 ├─ PerceptionUnreliable    → AcceptPartial          [不重试]
 ├─ PlanOrAnswerError(≤2)   → LocalReplan            [重试·局部]
 └─ PlanOrAnswerError(>2|空)→ GlobalReplan           [重试·全局]
```

该映射是 IFF（当且仅当）契约——不可恢复**只**→AcceptPartial，可恢复**只**→Local/GlobalReplan。由 mutation-RED 承重测试逐条锁定。

### 4.4 TerminalReason 终态状态机

```text
                 ┌─ isPassed && !hasParseFailure ──────────▶ PASSED
                 │
  runVerifyLoop ─┼─ AcceptPartial ─────────────────────────▶ ACCEPT_PARTIAL
                 │
                 ├─ retryCount >= maxRetries（非 AcceptPartial）▶ MAX_RETRIES_EXCEEDED
                 │
                 └─ LocalReplan redo 集合为空 ─────────────▶ INCONCLUSIVE
```

`terminalReason` 永不 null（PevTrace 契约）；四态闭集。

### 4.5 闭环运行态归属

| 状态对象 | 归属 | 职责 |
|---|---|---|
| `completed` (Map<nodeId,NodeResult>) | PEV invoke 局部 | 累积各超步结果，verify/diagnose 读它，assembleOutput 从它装配。 |
| `terminal` / `retryCount` | PEV invoke 局部（VerifyLoopState） | 控制循环终止与重试计数。 |
| `PevTrace` | PEV（终态产出） | 闭环终态副产品，经 sink 投递；不进 invoke 输出。 |
| `PevConfig.maxRetries` | PEV（构造/配置时） | 唯一配置，截断 verify 循环。 |
| 三阶段实现（Planner/Executor/Verifier） | 宿主注入 | PEV 不持有它们的内部状态。 |
| 服务端 Task lifecycle | `agent-runtime-java` | PEV 不拥有；被 runtime 包装时由 runtime 映射。 |

`completed` 是 invoke 局部变量（LinkedHashMap 保序），不是持久化状态——PEV 单次 invoke 自包含，跨调用无状态。

## 5. Kernel 决策模型

`PevKernel` 是 PEV 的决策核心——两个纯函数，零 LLM、零框架耦合。它是同级模式可复用的承重资产。状态转移逻辑见 §4.2/§4.3；本节补充设计约束。

### 5.1 两层分离的设计约束

`RootCause`（why）与 `ReplanAction`（what）刻意分层，不合并：诊断与动作的关注点不同，动作需携带数据（哪些节点、什么 feedback），诊断需携带证据（哪些节点失败、verifier 是否抛错）。合并会使 sealed 层次既含诊断证据又含动作参数，职责膨胀。

### 5.2 交叉校验防丢失侧信道

`diagnoseRootCause` 不只读 `verify.failedNodes`，还交叉校验 node 结果图（`DeviceFailure` 节点）。即便 failed 侧信道丢失（verifier 未报告某 DeviceFailure 节点），node 结果图里的 `DeviceFailure` 仍能被识别为 `RootCause.DeviceFailure`——这是"感知先于降级"的多通道防御。

## 6. Sealed 类型层次（穷举约束）

PEV 用4 个 sealed 接口约束支持的变体，Java 17 dispatcher 显式处理每个 permitted variant，未知 variant 抛 `IllegalArgumentException`。

| sealed 类型 | permitted variants | 消费者 |
|---|---|---|
| `NodeResult` | Success / DeviceFailure / VerifierFailure | verifier, diagnoser |
| `RootCause` | DeviceFailure / PlanOrAnswerError / PerceptionUnreliable | toReplanAction |
| `ReplanAction` | LocalReplan / GlobalReplan / AcceptPartial | PEVAgent.dispatchReplanAction |
| `PevTrace.Phase` | Planned / Executed / Verified / Diagnosed / Dispatched | trace 消费者 |
| `TerminalReason`（enum） | PASSED / ACCEPT_PARTIAL / MAX_RETRIES_EXCEEDED / INCONCLUSIVE | trace 消费者 |

sealed 穷举使"新增第 4 态"必须同时改 dispatcher + 测试，编译器强制穷尽——避免运行时落到未处理分支。

## 7. 逻辑依赖方向

### 7.1 包内隔离方向

```text
agent ──▶ kernel ──▶ （仅 agent-core-java）
observability ──▶ kernel（Phase 包装 kernel 类型 + Planned 用 NodeSnapshot 本地投影）
rail ──▶ kernel（读 NodeResult）
```

> **✅ RESOLVED（xuefanfan gap 已修复）**：`PevTrace.Planned` 原包装 `PevComponents.Plan`（agent 包），违背「observability → kernel」单向承诺。现已改为 `Planned(String goal, List<NodeSnapshot> nodes)`——NodeSnapshot 是 observability 本地投影 record，eager 拷贝 Plan 的完整数据（goal + 每个 node 的 id + description），零 agent import。ArchUnit 规则 `observabilityMustNotDependOnAgent` CI 强制（见 `PackageDependencyArchTest`）。Plan/PlanNode 留在 `agent.PevComponents`（PEV 独有，kernel 不消费）。

### 7.2 跨模块依赖方向

```text
agent-runtime-java  ──调用──▶  agents/pev (PEVAgent.invoke)
                                     │
                                     └──依赖──▶ agent-core-java:0.1.13

agent-core-ext-java (react-rails 等) ──同级──  agents/pev（兄弟模式，不互相 import）
```

依赖单向：PEV 只向 `agent-core-java` 取依赖；runtime 调用 PEV，PEV 不反向依赖 runtime。

### 7.3 kernel 复用边界

kernel 包（`PevKernel` + 三个 sealed 类型）可在不引入任何 agent 框架的前提下被同级模式（如 EDPA）复用——这是 kernel 零框架耦合的承重保证。任何让 kernel 依赖 LLM 或框架抽象的改动都破坏复用边界。

## 8. 与其他视图的衔接

- 运行时相位流、dispatch 分支运行时行为、verifier 容错、trace emit、并发模型：`process.md`。
- 代码分层、依赖红线（CI 强制）、构建基线、SPI 布局：`development.md`。
- 部署形态、网络/持久化边界、sink 消费拓扑：`physical.md`。
- 技术场景路径（直通/重做/降级/截断/rail/trace）：`scenarios.md` TS-01 ～ TS-09。
- 接入契约（invoke/SPI/rail/sink 形态）、类级签名、状态映射详表：L2 `Feat-Func-023-pev-selfheal-loop.md`。

## 9. 逻辑视图边界

- PEV 的领域对象都是 invoke 内或 PEV 实例级的局部状态，不构成跨调用持久化。
- sealed 类型穷举的是**当前版本**支持的变体；新增变体（如第 4 态 RootCause）需先回 FEAT-023 更新事实要求，再改 dispatcher + 测试。
- kernel 决策核心的"零 LLM、零框架耦合"是复用承诺——任何让 kernel 依赖 LLM 或框架的改动都破坏同级模式复用边界。
