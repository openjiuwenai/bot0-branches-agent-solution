---
scope: v0.1.0
module: agents/pev
feature_type: functional
feature_id: FEAT-023
status: active
updated: 2026-08-06
---

# PEV 自愈执行闭环（Plan→Execute→Verify→Diagnose→Dispatch）

## 1. 特性定位

FEAT-023 定义 `agents/pev` 当前版本作为 **PEV agent-service-app** 的事实模板：一个建在 `agent-core-java:0.1.13` 的 `BaseAgent` 之上、在单次 `invoke` 内自包含运行 `Plan → Execute → Verify → Diagnose → Dispatch` 闭环的智能体实现。PEV 的承重事实是：verify 不通过时，agent 先用确定性信号诊断**为什么失败**（根因三态），再据此决定**做什么**（dispatch 三态），而不是无差别重试或静默降级。

本特性解决的问题是：裸 LLM agent 在工具调用 / 多步推理失败时，缺乏一个可解释、可测试、不依赖 LLM 自我报告的自愈决策路径。PEV 把"诊断根因 → 选择动作"从 LLM 口算下沉为 sealed types + 纯函数 dispatch，使自愈逻辑可被 mock 单测穷举、可被独立审计。

对总体设计而言，本特性是 agent-service-app 的自包含控制流核心。PEV 以 `BaseAgent` 子类在 `invoke` 内跑完闭环；serving handler（接线时）只转发请求到 `agent.invoke`，控制流完全在 agent 内部，不拆到 handler override。PEV 不定义 HTTP/A2A 接入、会话编排、远端 card 发现与通信——这些由 `agent-runtime-java` 承接；PEV 也不是 ext adapter，它本身就是 agent-service-app，是同级模式（如 EDPA）的兄弟模板。

本特性面向以下角色：

- **Agent 开发者**：实现三个阶段 SPI（Planner/Executor/Verifier）接入自己的 LLM/工具/verifier，复用 PEV 的自愈闭环。
- **同级模式作者**：以 PEV 为模板派生新 agent-service-app，或直接复用 kernel 的 sealed types 与纯函数。
- **平台集成方**：把 PEV agent 注册到 runtime，通过 rail 缝合点挂载认知/可观测扩展。
- **测试与验收团队**：按本特性定义的控制流分支、dispatch IFF 契约和诚实边界设计黑盒/白盒场景。

本特性只定义 PEV 作为 `BaseAgent` 子类在 `invoke` 内的自包含闭环行为、驱动该闭环的 kernel 决策契约、以及作为横切观测/认知扩展的 rail 缝合点。多 agent 编排、HTTP/A2A 接入、真流式、增量 trace 订阅由 runtime 或未来版本承接。

## 2. 当前版本能力要求

| 能力 | 要求级别 | 事实要求 |
|---|---|---|
| 自包含闭环 | MUST | `PEVAgent.invoke` 必须在单方法内跑完 Plan→Execute→Verify→Diagnose→Dispatch；控制流不拆到 handler override。 |
| 三阶段注入 SPI | MUST | 必须以 `PevComponents.Planner/Executor/Verifier` 三个接口注入阶段实现；每阶段可独立 mock，控制流测试不必接 LLM。 |
| Verify 通过即终止 | MUST | `isPassed && !hasParseFailure` 时必须立即进入 `PASSED` 终态，短路 Diagnose/Dispatch（真实分支，非恒 fire）。 |
| 根因三态诊断 | MUST | verify 不通过时必须由 `PevKernel.diagnoseRootCause` 产出 sealed `RootCause` 三态之一（`PerceptionUnreliable`/`DeviceFailure`/`PlanOrAnswerError`）；诊断依据是确定性信号，不得依赖 LLM 自我报告。 |
| Dispatch 三态分派 | MUST | 必须由 `PevKernel.toReplanAction` 把 `RootCause` 映射到 sealed `ReplanAction` 三态之一（`AcceptPartial`/`LocalReplan`/`GlobalReplan`）；显式 dispatcher 处理每个 variant，未知 variant 抛 `IllegalArgumentException`。 |
| 不可恢复根因不重试 | MUST | `DeviceFailure`/`PerceptionUnreliable` 必须映射到 `AcceptPartial`，永不重试。 |
| 可恢复根因不降级 | MUST | `PlanOrAnswerError` 必须映射到 `LocalReplan`（failed 节点 ≤2 且非空）或 `GlobalReplan`（>2 或空），永不 `AcceptPartial`。 |
| terminalGuard | MUST | verify 循环必须被 `maxRetries` 截断；`retryCount >= maxRetries` 且非 `AcceptPartial` 时进入 `MAX_RETRIES_EXCEEDED` 终态，不得死循环。默认 `maxRetries=2`。 |
| LocalReplan 重做 + feedback 注入 | MUST | `LocalReplan` 必须只重做 failed 节点，并把 feedback 注入重做节点 description；redo 集合为空时进入 `INCONCLUSIVE` 终态。 |
| GlobalReplan + feedback 注入 | MUST | `GlobalReplan` 必须以 `userInput + " [correction: feedback]"` 重新调用 Planner，清空已完成结果后重跑闭环。 |
| Verifier 容错降级 | MUST | verifier 抛 `RuntimeException` 必须降级为 `VerifyResult(hasThrown=true)`，不得击穿 invoke；返回 null 降级为 `hasParseFailure=true`；`Error` 原样抛出。两种降级都经 `PerceptionUnreliable` 走 `AcceptPartial`，不重试。 |
| Phase rail 缝合点 | MUST | 必须在相位边界 fire 回调：`BEFORE_INVOKE`/`AFTER_MODEL_CALL`(Plan)/`AFTER_TOOL_CALL`(Execute)/`AFTER_INVOKE`(终态)。 |
| Kernel-native 可观测 trace | MUST | 每次 invoke 终态必须产出 `PevTrace`（sealed `Phase` 序列 + `TerminalReason` + verify 迭代数），4 of 5 `Phase` 直接包装 kernel sealed 类型；`Planned` 用 `NodeSnapshot` 本地投影（零 agent import，ArchUnit `observabilityMustNotDependOnAgent` 守卫）。 |
| Trace sink 显式 opt-in | MUST | trace 消费必须经构造器注入的 `PevTraceSink`；默认 `noop()`；sink 抛错必须被 FutureTask 桥隔离，不得击穿 invoke。 |
| 终态真源永不 null | MUST | `PevTrace.terminalReason` 必须取值于 `PASSED`/`ACCEPT_PARTIAL`/`MAX_RETRIES_EXCEEDED`/`INCONCLUSIVE` 四态之一，永不 null。 |
| 流式（降级） | MUST | `stream` 必须接受与 `invoke` 一致的输入；当前版本降级为单 chunk。 |
| 真流式 | OUT | 当前版本不承诺 token-by-token 流式；需 Executor 暴露 `Flux`，属未来版本。 |
| 精确按节点重执行 | OUT | 不承诺 DAG 级 per-node 重执行；`LocalReplan` 是同 agent 内整步重试。 |
| 增量 trace 订阅 | OUT | 不承诺 sink 在循环中途反应；trace 单次终态 emit。 |
| 多 agent 扇出 | OUT | 不承诺核心 PEV 内置多 agent 编排。 |
| HTTP/A2A 接入 | OUT | 不承诺 PEV 自带服务面；由 `agent-runtime-java` 承接。 |

## 3. 外部接口与入口要求

本节定义 PEV 作为 `BaseAgent` 子类对外暴露的接入面，不承诺具体部署形态（被 runtime 包装为 A2A Task / SSE 时由 runtime 特性承接）。

| 接入面 | 类型 | 输入要求 | 输出要求 | 约束 |
|---|---|---|---|---|
| `invoke(Object, Session)` | 方法 | `String`/`Map`(取 `userInput`)/其他（`String.valueOf`）；null 归一为空串。 | 装配输出字符串。 | 单方法跑完闭环；handler 只转发。 |
| `stream(Object, Session, List<StreamMode>)` | 方法 | 与 invoke 一致 + modes。 | 单元素 `Iterator`（降级）。 | 真流式属未来版本。 |
| `configure(Object)`/`getConfig()` | 方法 | `PevConfig` 更新 `maxRetries`；非 `PevConfig` 忽略。 | 返回 `this` / `PevConfig`。 | — |
| `Planner` SPI | SPI | `String userInput` | `Plan(goal, List<PlanNode>)` | 阶段可独立 mock。 |
| `Executor` SPI | SPI | `List<PlanNode>` | `Map<String,NodeResult>` | 按 nodeId 索引。 |
| `Verifier` SPI | SPI | `(userInput, completed)` | `PevKernel.VerifyResult` | 结构化判定。 |
| Rail 注册 | `BaseAgent.registerRail` | `AgentRail` | — | 经 `AgentCallbackContext` 在相位边界投递 `payload`；终态额外投递 typed `trace`。 |
| Trace sink | 构造器注入 | `PevTraceSink`（默认 `noop()`） | — | 实例 scope，非进程级 static。 |

PEV 不向业务应用暴露 `taskId`、A2A JSON-RPC、SSE 或服务端 TaskStore——这些由 runtime 承接。PEV 只提供可被 runtime 调用的 `invoke`/`stream`。

## 4. 场景与用户旅程

| 场景 | 前置条件 | 用户/系统动作 | 期望行为 |
|---|---|---|---|
| 一次性直通 | verifier 首次判 PASS | 宿主调 `invoke` | Plan→Execute→Verify 后短路 `PASSED`，不触发 Diagnose/Dispatch。 |
| 内容错误局部重做 | verifier FAIL、根因 `PlanOrAnswerError`、failed ≤2 | 宿主调 `invoke` | `LocalReplan`：只重做 failed 节点（description 注入 correction feedback），重跑 verify；通过即 `PASSED`。 |
| 内容错误全局重规划 | failed >2 或空 | 宿主调 `invoke` | `GlobalReplan`：清空 completed，以带 correction 的 goal 重 Plan，重跑闭环。 |
| 设备故障诚实降级 | 节点返回 `DeviceFailure` 且与 verify failed 相交 | 宿主调 `invoke` | `AcceptPartial`；executor 只调一次（不重试）；`ACCEPT_PARTIAL`；输出含 `[DeviceFailure]`。 |
| Verifier 不可信降级 | verifier 抛错或返回 null | 宿主调 `invoke` | 降级为 `hasThrown`/`hasParseFailure`→`PerceptionUnreliable`→`AcceptPartial`；不重试。 |
| 重试上限截断 | `PlanOrAnswerError` 持续不通过至 `maxRetries` | 宿主调 `invoke`（`maxRetries=2`） | `MAX_RETRIES_EXCEEDED`；verify 至多 3 次；不死循环。 |
| 横切认知 rail | 宿主注册 `CriteriaVerificationRail`/`RootCauseRail` | 宿主调 `invoke` | rail 在对应相位观测（afterInvoke 校验成功关键词 / afterToolCall 累积 DeviceFailure 遥测），与 PEV 内部 verify/diagnose 独立。 |
| 可观测 trace 采集 | 注入非 noop `PevTraceSink` | 宿主调 `invoke` | 终态 `emitTrace` 投递完整 `PevTrace`（FutureTask 隔离）；`AFTER_INVOKE` 同时携带 `payload`+`trace`。 |

## 5. 行为语义与边界

### 5.1 核心行为语义

#### 5.1.1 闭环相位语义

- `invoke` 相位推进：`BEFORE_INVOKE` → Plan(`AFTER_MODEL_CALL`) → `runVerifyLoop` 内 Execute(`AFTER_TOOL_CALL`)+Verify →（不通过时）Diagnose → Dispatch → 再循环 → 终态 `AFTER_INVOKE`。
- Execute 把本超步结果并入 `completed`（LinkedHashMap 保序）；Verify 评估累积 `completed`，非仅本步。
- Verify 通过条件 `isPassed && !hasParseFailure`；`hasParseFailure=true` 时即使 `isPassed=true` 也不短路（不可解析的 PASS 不可信）。

#### 5.1.2 根因诊断优先级语义

诊断优先级由信号确定性决定（非 LLM 自报告）：perception-unreliable > device-failure > plan/answer-error。

- `hasThrown` → `PerceptionUnreliable(true)`；`hasParseFailure` → `PerceptionUnreliable(false)`（抛错压倒不可解析）。
- `DeviceFailure` 要求"执行侧 DeviceFailure 节点"与"verifier failed 集合"相交非空——交叉校验，防丢失侧信道。
- 三态都不命中落 `PlanOrAnswerError(verify.failedNodes)`。

#### 5.1.3 Dispatch IFF 契约（承重核心）

| RootCause | → ReplanAction | 重试？ | 理由 |
|---|---|---|---|
| `DeviceFailure` | `AcceptPartial` | 否 | 坏设备不会因重试自愈。 |
| `PerceptionUnreliable` | `AcceptPartial` | 否 | 不可信 verifier 的 FAILED 不可据以行动。 |
| `PlanOrAnswerError`（≤2 节点非空） | `LocalReplan` | 是 | 内容错误可由重做 failed 节点修复。 |
| `PlanOrAnswerError`（>2 或空） | `GlobalReplan` | 是 | 大范围错误需全量重规划。 |

该映射是 IFF（当且仅当）契约，由 mutation-RED 承重测试逐条锁定。`LocalReplan`/`GlobalReplan` 受 `terminalGuard` 约束；`AcceptPartial` 不受 `maxRetries` 约束（终态非重试）。

#### 5.1.4 重做与反馈语义

- `GlobalReplan`：`userInput + " [correction: feedback]"` 重调 Planner，feedback 注入重规划 goal。
- `LocalReplan`：重做 failed 节点时把 feedback 注入节点 description（`desc + " [correction: feedback]"`），与 GlobalReplan 对称；feedback null/blank 回退原 description。注入是打破"同 prompt 同输出"重试死循环的必要条件。
- redo 集合为空（verifier 报告的 failed 节点不在 plan 中，契约错配）→ `INCONCLUSIVE` 终态，只如实标记，不改变 `invoke` 输出。

#### 5.1.5 Verifier 容错语义

verifier 经 `CompletableFuture` 包裹；`CompletionException` 解包按 cause 分类：`RuntimeException`→`hasThrown=true`；null→`hasParseFailure=true`；`Error` 原样抛；其他→`IllegalStateException`。两种降级都走 `PerceptionUnreliable`→`AcceptPartial`，不重试。

#### 5.1.6 可观测 trace 语义

trace 是闭环终态副产品（非 enforcer 转移的并行埋点）：每个承重值（plan/stepResults/VerifyResult/RootCause/ReplanAction）都是 invoke 局部变量，trace 在返回时一次性 emit。4 of 5 `Phase` 直接包装 kernel sealed 类型（零新 schema）；`Planned` 用 `NodeSnapshot` 本地投影。sink 为实例 scope（PEVAgent 字段），非进程级 static；`emitTrace` 用 FutureTask 桥隔离 sink。

#### 5.1.7 输出装配语义

`assembleOutput` 按 `completed` 入序拼 `nodeId: value`；`Success` 取 `String.valueOf(value)`，非 Success 取 `[ClassName]`（如 `[DeviceFailure]`）；空 `completed` 返回空串。

### 5.2 显式边界与不承诺项

| 边界 | 当前版本不承诺 |
|---|---|
| 真流式 | 不承诺 token-by-token；`stream` 降级为单 chunk，真流式需 Executor 暴露 `Flux`。 |
| 精确按节点重执行 | 不承诺 DAG 级 per-node 重执行；`LocalReplan` 是同 agent 内整步重试。 |
| 增量 trace | 不承诺 sink 在循环中途反应；trace 单次终态 emit。 |
| 多 agent 扇出 | 不承诺核心 PEV 内置多 agent 编排。 |
| HTTP/A2A 服务面 | 不承诺 PEV 自带 northbound 接入；由 `agent-runtime-java` 承接。 |
| LLM SDK 绑定 | 不绑定特定 LLM SDK；三阶段为 SPI，kernel 决策核心零 LLM、零框架耦合。 |
| 非文本输入 | 当前版本以文本/Map 为 agent 输入主路径。 |

## 6. 对下游设计与实现的约束

- L1/L2 设计必须把本特性作为 PEV agent-service-app 的事实来源，不得把闭环行为、dispatch IFF 契约或诚实边界降级为实现细节。
- `PEVAgent`、`PevKernel`、3 个 kernel sealed 接口（`NodeResult`/`RootCause`/`ReplanAction`）+ `VerifyResult`（record）+ observability 的 `PevTrace.Phase`（sealed）+ `TerminalReason`（enum）及两个纯函数必须共同满足第 2-5 节事实要求。
- 开发指南只能解释如何使用这些事实要求，不得引入与本特性冲突的新终态语义、新 dispatch 分支或夸大能力声明。
- 测试必须覆盖：直通 PASSED、LocalReplan 重做到通过、GlobalReplan、DeviceFailure→AcceptPartial 不重试、verifier 抛错/返回 null 降级、`maxRetries` 截断、redo 空集合 INCONCLUSIVE、trace 四终态、sink 隔离。承重断言必须用"剥 token→RED"（IFF 范式），不得用"有调用/非空"弱断言。
- 任何对 sealed 类型新增第 4 态、新增 dispatch 分支、把 AcceptPartial 改为可重试、把 PlanOrAnswerError 改为可降级、引入真流式/增量 trace/多 agent 扇出的新承诺，都必须先回到本特性文档更新事实要求，再进入 L2 和实现。

## 7. 关联文档

- `L1-High-Level-Design/{overview,logical,process,development,physical,scenarios.md`（L1 4+1 视图，同目录下）
- `L2-Low-Level-Design/Feat-Func-023-pev-selfheal-loop.md`（L2 详细设计）
- `common/agents/pev/README.md`（开发指南）
- `agent-runtime-java` 标准化智能体服务入口特性（PEV 被 runtime 以 A2A JSON-RPC/SSE 暴露时的 northbound 事实来源）
