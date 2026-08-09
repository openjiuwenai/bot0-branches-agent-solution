---
level: L2-LLD
module: agents/pev
feature_type: functional
feature_id: FEAT-023
status: active
authority: authoritative
dependency:
  - ../features/FEAT-023-pev-selfheal-loop.md
  - README.md
  - ../L1-High-Level-Design/overview.md
  - ../L1-High-Level-Design/logical.md
  - ../L1-High-Level-Design/process.md
---

# PEV 自愈执行闭环 — 设计文档（FEAT-023 L2）

> 目标模块：`agents/pev`（自包含 agent-service-app）
> 事实来源（authoritative）：`features/FEAT-023-pev-selfheal-loop.md`（本 L2 是其实现级细化，术语与语义以 FEAT-023 为准）
> 参照实现：`common/agents/pev/src/main/java/com/openjiuwen/agents/pev/`（origin/common 已实现代码，承重测试全绿 + 真 LLM e2e 软观察）
> 最后更新：2026-08-06
> **🎯 交付范围：** PEV 已全量交付 FEAT-023 当前版本能力（自包含闭环 + kernel 决策 + rail 缝合 + kernel-native trace）。真流式 / 多 agent 扇出 / HTTP-A2A 接入 / 增量 trace 订阅 / DAG 级 per-node 重执行是 **显式排除**（架构非目标，非"暂缓"），见 §2.2。
> **🔗 接入边界：** PEV 是 in-process agent 库（无 wire 协议、无独立进程）；被 `agent-runtime` 包装为 A2A Task/SSE 时，wire 语义由 runtime 特性承接，PEV 只暴露 `invoke`/`stream`/SPI/rail/sink。

---

## 1. 概述

### 1.1 特性定位

`agents/pev` 作为自包含 agent-service-app 模板，在单次 `invoke` 内跑完 `Plan → Execute → Verify → Diagnose → Dispatch` 自愈闭环，把"verify 失败 → 诊断根因 → 选择补救"从 LLM 口算下沉为 sealed types + 纯函数 dispatch，**不依赖** LLM 自我报告。

- **解决的问题**：裸 LLM agent 失败时无差别重试或静默降级，缺乏可解释、可测试、不假承重的自愈决策路径。
- **适用场景**：工具调用/多步推理失败需要确定性根因诊断与差异化补救的 agent-service-app；作为同级模式（EDPA 等）的承重模板。**不适用**：需要 HTTP/A2A 服务面的运行时（属 agent-runtime）、多 agent 编排、真流式。

### 1.2 当前事实边界

本文描述 FEAT-023 的 **authoritative L2 设计**——与 `common/agents/pev` 的已实现代码严格对应（origin/common）。改代码须同步改本文档。承重测试用"剥 token→RED"（IFF 范式）钉死 dispatch 契约；真 LLM e2e 只作数据通道软观察，不作为控制流不变量硬验收。

### 1.3 设计原则

1. **自包含控制流** — `invoke` 单方法跑完闭环；serving handler 只转发，控制流不拆到 handler override。
2. **诊断与 LLM 解耦** — 根因诊断（`diagnoseRootCause`）依据确定性信号（verifier 是否抛错/不可解析、node 结果图、failed 集合），不依赖 LLM 自报告；dispatch（`toReplanAction`）是纯函数 IFF 映射。
3. **不可恢复 vs 可恢复硬分离** — `DeviceFailure`/`PerceptionUnreliable`→`AcceptPartial` 永不重试；`PlanOrAnswerError`→`LocalReplan`/`GlobalReplan` 永不降级。
4. **诚实边界（铁律①）** — 不假承重（"有调用/非空"弱断言恒真）；不可达终态/能力诚实标 INCONCLUSIVE/N/A，不装。
5. **kernel 零框架耦合** — `PevKernel` + 3 个 sealed 接口（NodeResult/RootCause/ReplanAction）+ `VerifyResult`（record）不依赖 agent-core 框架抽象，可被同级模式独立复用（`PevTrace.Phase` 属 observability，非 kernel 复用单元）。
6. **显式 opt-in 可观测** — trace sink 默认 `noop()`，经构造器注入；实例 scope 非进程级；sink 故障 FutureTask 桥隔离。

### 1.4 子特性全景

| 子特性 | 职责 | 关键抽象 | 当前版本 |
|--------|------|---------|------|
| 自包含闭环 | invoke 跑完 Plan→Execute→Verify→Diagnose→Dispatch | `PEVAgent.invoke`, `runVerifyLoop` | ✅ 交付 |
| 三阶段 SPI | Planner/Executor/Verifier 注入式 | `PevComponents.{Planner,Executor,Verifier}` | ✅ 交付 |
| Kernel 决策核心 | 信号→根因→动作（纯函数 + sealed） | `PevKernel`, `RootCause`, `ReplanAction`, `VerifyResult` | ✅ 交付 |
| terminalGuard | verify 循环 maxRetries 截断 | `PevConfig.maxRetries` | ✅ 交付 |
| Verifier 容错 | throw/null 降级不击穿 invoke | `VerifyResult.hasThrown/hasParseFailure` | ✅ 交付 |
| Kernel-native trace | 终态相位投影 + sink 投递 | `PevTrace`, `PevTraceSink`, `emitTrace` | ✅ 交付 |
| Rail 缝合点 | 相位边界回调（认知/可观测扩展） | `BaseAgent.registerRail`, 四相位事件 | ✅ 交付 |
| 真 token 流式 | — | — | ⏸ 显式排除（§2.2） |
| 多 agent 扇出 | — | — | ⏸ 显式排除 |

> rail（`CriteriaVerificationRail`/`RootCauseRail`）是**示例横切认知 rail**，展示缝合点用法；PEV 闭环本身不依赖它们。

---

## 2. 特性规格

### 2.1 本版本交付能力

| 能力 | 状态 | 说明 |
|------|------|------|
| 自包含闭环 invoke | ✅ | 单方法跑完五相位；handler 只转发 |
| 三阶段注入 SPI | ✅ | Planner/Executor/Verifier 可独立 mock |
| Verify 通过即终止 | ✅ | `isPassed && !hasParseFailure` 短路 PASSED |
| 根因三态诊断 | ✅ | `diagnoseRootCause` 确定性信号（非 LLM 自报告） |
| Dispatch 三态分派 | ✅ | `toReplanAction` IFF 映射，显式 dispatcher |
| 不可恢复不重试 | ✅ | DeviceFailure/PerceptionUnreliable→AcceptPartial |
| 可恢复不降级 | ✅ | PlanOrAnswerError→LocalReplan/GlobalReplan |
| terminalGuard | ✅ | maxRetries 截断（默认 2，verify 至多 3 次） |
| LocalReplan feedback 注入 | ✅ | 重做节点 description 注入 correction |
| GlobalReplan feedback 注入 | ✅ | userInput + correction 重 Plan |
| Verifier 容错降级 | ✅ | throw→hasThrown / null→hasParseFailure，不击穿 |
| Phase rail 缝合点 | ✅ | 四相位事件 + AFTER_INVOKE 双键(payload+trace) |
| Kernel-native trace | ✅ | 终态 PevTrace，4 of 5 Phase 包装 kernel sealed 类型；Planned 用 NodeSnapshot 投影 |
| Trace sink 显式 opt-in | ✅ | 构造器注入，默认 noop，FutureTask 隔离 |
| 终态真源永不 null | ✅ | terminalReason 四态枚举 |
| 流式（降级） | ✅ | stream 单 chunk（真流式见 §2.2） |

### 2.2 显式排除（架构非目标，非"暂缓"）

| 排除项 | 原因 | 替代/演进路径 |
|--------|------|--------------|
| 真 token-by-token 流式 | 需 Executor 暴露 Flux | stream 暂降级为单 chunk；真流式属未来版本 |
| DAG 级 per-node 重执行 | 需 DAG/多 agent runtime | LocalReplan 是同 agent 内整步重试 |
| 增量 trace 订阅 | trace 是终态副产品 | 单次终态 emit；未来异步 Executor 会显现缺口 |
| 多 agent 扇出 | 核心 PEV 不内置编排 | 由 runtime/上层模式承接 |
| HTTP/A2A 服务面 | northbound 接入非 PEV 职责 | 由 agent-runtime 承接（PEV 只暴露 invoke） |
| LLM SDK 绑定 | 三阶段是 SPI | 由宿主接线 LLM/工具/verifier |

### 2.3 接入契约（Logical View）

PEV 作为 `BaseAgent` 子类的公共接入面（无 wire；被 runtime 包装时由 runtime 映射到 A2A）：

```java
/** 自包含闭环入口。serving handler 只转发到 invoke，不 override 控制流。 */
public class PEVAgent extends BaseAgent {
    public PEVAgent(AgentCard card, PevComponents.Planner p, PevComponents.Executor e, PevComponents.Verifier v);
    public PEVAgent(AgentCard card, PevComponents.Planner p, PevComponents.Executor e, PevComponents.Verifier v, PevTraceSink sink);
    @Override public Object invoke(Object input, Session session);
    @Override public Iterator<Object> stream(Object input, Session session, List<StreamMode> modes);
    @Override public BaseAgent configure(Object cfg);
    public static final class PevConfig { public final int maxRetries; }
}
/** 三阶段 SPI（宿主实现，可独立 mock）。 */
public final class PevComponents {
    public interface Planner  { Plan plan(String userInput); }
    public interface Executor { Map<String,NodeResult> execute(List<PlanNode> nodes); }
    public interface Verifier { PevKernel.VerifyResult verify(String userInput, Map<String,NodeResult> completed); }
}
```

#### 数据类型

| 类型 | 关键字段/变体 | 含义 | 约束 |
|------|--------------|------|------|
| `PevComponents.Plan` | goal, List<PlanNode> | Plan 产物 | 节点带稳定 id（verify/replan 引用键） |
| `NodeResult`（sealed） | Success / DeviceFailure / VerifierFailure | 节点终态 | 三态闭集 |
| `PevKernel.VerifyResult` | isPassed, failedNodes, feedback, hasParseFailure, hasThrown | verifier 判定 | 两独立"感知不可信"信号分离 |
| `RootCause`（sealed） | DeviceFailure / PerceptionUnreliable / PlanOrAnswerError | 根因（why） | 优先级：perception>device>plan |
| `ReplanAction`（sealed） | LocalReplan / GlobalReplan / AcceptPartial | 动作（what） | 携带动作数据 |
| `PevTrace` | phases, terminalReason, verifyIterations | 终态投影 | terminalReason 永不 null |
| `PevTrace.Phase`（sealed） | Planned/Executed/Verified/Diagnosed/Dispatched | 相位 | 4 of 5 包装 kernel sealed 类型；Planned 用 NodeSnapshot 投影 |
| `TerminalReason`（enum） | PASSED/ACCEPT_PARTIAL/MAX_RETRIES_EXCEEDED/INCONCLUSIVE | 终态原因 | 四态闭集 |

### 2.4 流式如何处理（回应"invoke 与 stream 关系"）

`stream` 当前降级为 `List.of(invoke(...)).iterator()`——同步一次 invoke 后以单元素返回。三种 StreamMode 都走同一降级路径。真 token 流式需要 Executor 暴露 `Flux<QueryChunk>` 并由 stream 透传，属 §2.2 显式排除；当前不静默承诺。

---

## 3. 核心实现（参照代码）

### 3.1 分层与依赖隔离

```
宿主 / agent-runtime
  │  只依赖 PEVAgent.invoke / 三阶段 SPI / rail / sink
  ▼
PEVAgent (agent 包)  ──►  runVerifyLoop / dispatchReplanAction / handleLocalReplan / emitTrace
                              │
                              ▼  只依赖 kernel 纯函数（零 LLM、零框架耦合）
                        PevKernel (kernel 包)
                         ├── diagnoseRootCause(VerifyResult, execFailed, nodeResults) → RootCause
                         └── toReplanAction(RootCause, feedback, failedNodes) → ReplanAction
                        + sealed types: NodeResult / RootCause / ReplanAction / VerifyResult
```

kernel 包不反向依赖 agent 包——这是同级模式（EDPA）可独立复用 kernel 的承重保证。

### 3.2 invoke 闭环（调用创建与相位推进）

```
宿主: agent.invoke(input, session)
  │  ① toUserInput(input) → userInput
  │  ② fire(BEFORE_INVOKE, input)
  ▼
planner.plan(userInput) → Plan
  │  fire(AFTER_MODEL_CALL, plan)
  ▼
runVerifyLoop(plan, VerifyLoopState):
  ├─ executor.execute(plan.nodes) → stepResults
  │     fire(AFTER_TOOL_CALL, stepResults); completed.putAll(stepResults)
  ├─ verifier.verify(userInput, completed) → VerifyResult   [CompletableFuture 包裹，容错见 §5.2]
  ├─ 若 isPassed && !hasParseFailure → terminalReason=PASSED; return（短路）
  ├─ diagnoseRootCause(verify, execFailedNodes, completed) → RootCause
  └─ toReplanAction(cause, feedback, failedNodes) → ReplanAction → dispatchReplanAction(...)
```

### 3.3 相位事件归一化

PEVAgent 把闭环内部相位 fire 为 `AgentCallbackContext` 事件，rail 据此观测（业务/rail 永不接触 invoke 局部变量）：

| 闭环内部 | 归一化相位事件 | extra.payload |
|---------|---------------|---------------|
| 入口 | `BEFORE_INVOKE` | 原始 input |
| Plan 后 | `AFTER_MODEL_CALL` | Plan |
| 每个 Execute 超步后 | `AFTER_TOOL_CALL` | Map<nodeId,NodeResult> stepResults |
| 终态 | `AFTER_INVOKE` | 装配输出 String + typed `trace` 键(PevTrace) |

> `AFTER_INVOKE` 投递双键：`payload`（输出）+ `trace`（PevTrace）。只读 payload 的 rail 不受影响；trace 键是 trace 消费 rail 的缝合点。

### 3.4 dispatch 分支（IFF 契约的运行时落地）

`dispatchReplanAction` 按 sealed ReplanAction 分支（受 terminalGuard 约束）：

| ReplanAction | 运行时行为 | 终态/循环 |
|---|---|---|
| `AcceptPartial` | terminalReason=ACCEPT_PARTIAL; 不重试；executor 至此只调一次 | 终态 |
| `LocalReplan` | redo=failed 节点，description 注入 " [correction: feedback]"；completed 移除旧结果；runVerifyLoop(redo, nextRetry) | 再循环 |
| `GlobalReplan` | newPlan=plan(userInput + " [correction: feedback]")；completed.clear()；runVerifyLoop(newPlan, nextRetry) | 再循环 |
| （redo 空） | terminalReason=INCONCLUSIVE（契约错配诚实标签） | 终态 |
| （retryCount≥maxRetries 且非 AcceptPartial） | terminalReason=MAX_RETRIES_EXCEEDED | 终态 |

#### 3.4.1 feedback 注入（回应"重试为何不死循环"）

LocalReplan 与 GlobalReplan 都把 feedback 注入重做输入——这是打破"同 prompt 同输出"重试死循环的必要条件：

- LocalReplan：`redo node description + " [correction: " + feedback + "]"`（feedback null/blank 回退原 description）。
- GlobalReplan：`userInput + " [correction: " + feedback + "]"`。
- 二者对称；executor 重试时收到不同输入，避免恒等重跑。

### 3.5 接入契约（无 wire；invoke/SPI/rail/sink 形态）

PEV 是 in-process 库，无 wire 协议。其"接入契约"是方法/SPI 形态（非报文）：

| 接入面 | 形态 | 输入 → 输出 | 约束 |
|---|---|---|---|
| invoke | 方法 | input(Object) → 装配输出 String | 单方法跑完闭环 |
| stream | 方法 | input+modes → 单元素 Iterator | 降级（真流式 OUT） |
| configure | 方法 | PevConfig → this | 只读 maxRetries |
| Planner/Executor/Verifier | SPI | 见 §2.3 | 注入式，可 mock |
| rail 注册 | BaseAgent.registerRail | AgentRail | 相位事件观测 |
| sink | 构造器注入 | PevTraceSink（默认 noop） | 实例 scope，FutureTask 隔离 |

被 runtime 包装为 A2A Task/SSE 时，runtime 把 `invoke` 输出映射为 Task artifact、把 `stream` 映射为 SSE——wire 语义由 runtime 特性承接，PEV 不感知 HTTP/A2A。

---

## 4. 代码结构（参照代码包）

```
com.openjiuwen.agents.pev
├── agent/
│   ├── PEVAgent.java              # 闭环主体（invoke/runVerifyLoop/dispatch/handleLocalReplan/emitTrace）+ PevConfig
│   └── PevComponents.java         # Plan/PlanNode + Planner/Executor/Verifier SPI
├── kernel/
│   ├── PevKernel.java             # diagnoseRootCause + toReplanAction 纯函数 + VerifyResult
│   ├── NodeResult.java            # sealed: Success/DeviceFailure/VerifierFailure
│   ├── RootCause.java             # sealed: DeviceFailure/PerceptionUnreliable/PlanOrAnswerError
│   └── ReplanAction.java          # sealed: LocalReplan/GlobalReplan/AcceptPartial
├── observability/
│   ├── PevTrace.java              # phases + terminalReason + Phase sealed
│   └── PevTraceSink.java          # 函数式接口 + noop()
└── rail/
    ├── CriteriaVerificationRail.java   # 示例：afterInvoke 成功关键词校验
    └── RootCauseRail.java              # 示例：afterToolCall 累积 DeviceFailure 遥测
```

包间依赖单向：`agent`→`kernel`→（仅 agent-core-java）；`observability`/`rail`→`kernel`。kernel 不依赖 agent。

---

## 5. 运行流程

### 5.1 主闭环流程（直通 + 重做循环）

```
invoke
 ├─ Plan → Execute → Verify
 │    ├─ isPassed && !hasParseFailure ──▶ PASSED（短路，无 Diagnose/Dispatch）
 │    └─ FAIL ──▶ Diagnose → Dispatch
 │                  ├─ AcceptPartial ──▶ 终态 ACCEPT_PARTIAL（不重试）
 │                  ├─ LocalReplan ──▶ redo failed 节点(+feedback) → 重跑 Verify（循环，受 maxRetries）
 │                  ├─ GlobalReplan ──▶ 重 Plan(+feedback) → 重跑（循环，受 maxRetries）
 │                  └─ (redo 空 / retry≥max) ──▶ 终态 INCONCLUSIVE / MAX_RETRIES_EXCEEDED
 ├─ assembleOutput(completed) → 输出
 └─ emitTrace(PevTrace) → sink + AFTER_INVOKE(payload+trace)
```

- verify 至多 `maxRetries+1` 次（默认 3）；AcceptPartial 不计入 maxRetries（终态非重试）。
- PASSED 时 trace 相位只有 Planned/Executed/Verified（无 Diagnosed/Dispatched）——相位序列如实反映短路。

### 5.2 Verifier 容错与降级

verifier 经 `CompletableFuture.supplyAsync(..., Runnable::run)` 包裹，使抛错可分类捕获而不击穿 invoke：

| verifier 行为 | 处理 | 对外结果 |
|---|---|---|
| 抛 RuntimeException | VerifyResult(hasThrown=true) → PerceptionUnreliable → AcceptPartial | 不击穿 invoke；不重试不可信判定 |
| 返回 null | VerifyResult(hasParseFailure=true) → PerceptionUnreliable → AcceptPartial | 同上 |
| 抛 Error | 原样抛出 | 不吞 JVM 级错误 |
| 其他（checked 包装） | IllegalStateException | verifier 违反 unchecked-exception 契约 |
| 正常 | 原样 VerifyResult | 按 §3.4 dispatch |

### 5.3 错误与终态表面

| 错误/终态场景 | 触发 | 行为 |
|---|---|---|
| verifier 抛错/null | 容错降级 | ACCEPT_PARTIAL，不重试 |
| 未知 ReplanAction variant | sealed dispatcher | IllegalArgumentException（运行时不可达除非反射破坏） |
| sink 抛错 | FutureTask 桥 | 隔离，invoke 输出与终态不受影响 |
| redo 集合空 | verifier/executor 契约错配 | INCONCLUSIVE terminalReason |
| verify 持续失败 | retryCount≥maxRetries | MAX_RETRIES_EXCEEDED（不死循环） |

---

## 6. 配置与使用

### 6.1 构造与调用（参照代码）

```java
PEVAgent agent = new PEVAgent(
        AgentCard.builder().build(),
        planner,                       // 宿主实现：NL 目标 → Plan
        executor,                      // 宿主实现：PlanNode → NodeResult
        verifier,                      // 宿主实现：(userInput, completed) → VerifyResult
        sink);                         // 可选；默认 noop()。宿主接 logger/OTel/Micrometer
agent.configure(new PEVAgent.PevConfig(2));   // maxRetries=2（默认）

Object output = agent.invoke("...", null);    // 跑完闭环
```

### 6.2 关键构造项

| 构造项 | 类型 | 默认 | 说明 |
|-------|------|------|------|
| card | AgentCard | 必填 | agent 身份 |
| planner/executor/verifier | PevComponents.* SPI | 必填 | 三阶段实现 |
| sink | PevTraceSink | noop() | trace 消费；实例 scope |
| maxRetries | int（PevConfig） | 2 | verify 循环截断；AcceptPartial 不受约束 |
| rail | BaseAgent.registerRail | 无 | 横切认知 rail，运行时注册 |

> 能力不靠配置开关声明——sealed 穷举 + IFF 契约是代码事实，不存在"配 true 即启用真流式/多 agent"的开关。

---

## 7. 当前限制

| 限制 | 影响范围 | 演进路径 |
|------|---------|---------|
| stream 单 chunk降级 | 非真 token 流式 | 需 Executor 暴露 Flux |
| LocalReplan 整步重试 | 非 DAG per-node 重执行 | 需 DAG/多 agent runtime |
| trace 单次终态 emit | sink 不能循环中途反应 | 未来异步 Executor 补增量 |
| 同步单线程 loop | 闭环在调用线程同步跑 | 异步 Executor 会引入并发模型 |
| 非文本输入次要 | 以文本/Map 为主路径 | — |

---

## 8. 对 agent-runtime / 集成方 的要求（转述给 runtime/集成方）

> PEV 是 in-process agent 库，无独立服务面。被 runtime 包装为 A2A 服务、被同级模式继承时，下列是 PEV 对接侧的要求。

| 编号 | 要求 | 说明 / 理由 |
|------|------|------------|
| R-1 invoke 透传 | runtime handler 只转发请求到 `agent.invoke`，不 override 控制流 | PEV 自包含闭环；handler 拆控制流破坏自包含性 |
| R-2 输出映射 | runtime 把 invoke 装配输出映射为 A2A Task artifact / SSE 事件 | PEV 不感知 HTTP/A2A |
| R-3 不引入第二套 TaskStore | 服务端 Task lifecycle 由 runtime 拥有；PEV 不持久化 | PEV 跨 invoke 无状态 |
| R-4 rail 注册幂等 | 注册的 rail 在相位事件被动触发，不改 PEV 控制流 | rail 是 defense-in-depth，非控制流分支 |
| R-5 sink 注入显式 | runtime 若需 trace，经构造器注入 sink；不强制安装 | 显式 opt-in，避静默安装 footgun |
| R-6 kernel 复用边界 | 同级模式复用 kernel 须保持其零框架耦合 | 任何让 kernel 依赖框架/LLM 的改动破坏复用 |

---

## 9. 一致性

本文 §2.1 各能力与 FEAT-023 §2 事实要求逐条对应，**均已交付（✅），无"本迭代不交付"收敛**——PEV 是已实现代码非 skeleton。L1 五视图对本文的覆盖见各章节"承接 L1"导语；术语以 FEAT-023 为准。
