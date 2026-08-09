---
level: L1-HLD
module: agents/pev
TAG:
  - process-view
  - control-flow
  - architecture-fact
status: active
updated: 2026-08-06
dependency:
  - overview.md
  - logical.md
  - ../features/FEAT-023-pev-selfheal-loop.md
---

# PEV L1 架构进程视图

## 1. 进程视图定位

进程视图描述 `PEVAgent.invoke` 内部的相位执行流、Execute+Verify 循环、dispatch 各分支的运行时行为、verifier 容错、trace emit，以及并发与隔离约束。它回答"一次 invoke 在时间上怎么推进、在哪里分支、在哪里落终态"。

类级签名与函数体见 L2；本文只给运行时行为事实。

## 2. invoke 相位执行流

一次 `invoke(input, session)` 的相位推进：

```text
invoke
 ├─ toUserInput(input) ─────────────── 归一为 String
 ├─ fire(BEFORE_INVOKE, input) ─────── 入口相位
 ├─ planner.plan(userInput) ────────── Plan 阶段
 │   └─ fire(AFTER_MODEL_CALL, plan)   Plan 相位
 ├─ runVerifyLoop(plan, state) ──────── Execute+Verify(+Diagnose+Dispatch) 循环
 │    ├─ executor.execute(plan.nodes) ─ Execute 超步
 │    │   └─ fire(AFTER_TOOL_CALL, stepResults)
 │    │   └─ state.completed.putAll(stepResults)
 │    ├─ verifier.verify(userInput, completed) ── Verify
 │    ├─ 若 isPassed && !hasParseFailure ── 终态 PASSED（短路）
 │    ├─ diagnoseRootCause(...) ─────── Diagnose
 │    ├─ toReplanAction(...) ────────── Dispatch
 │    └─ dispatchReplanAction(...) ──── 分支（见 §3）
 ├─ assembleOutput(completed) ──────── 装配输出
 ├─ build PevTrace(phases, terminalReason, verifyIterations)
 ├─ emitTrace(trace, output) ────────── 终态相位
 │   ├─ sink.onTrace(trace) [FutureTask 桥]
 │   └─ fire(AFTER_INVOKE, {payload: output, trace})
 └─ return output
```

关键相位承诺：
- **Verify 通过即短路**：`isPassed && !hasParseFailure` 时进 `PASSED`，**不**触发 Diagnose/Dispatch（真实分支，非恒 fire 全部相位）。
- **Execute 累积**：每超步结果并入 `completed`；Verify 评估的是累积 `completed`，非仅本步。
- **Phase 投影忠实**：`PASSED` 时 trace 只有 Planned/Executed/Verified（无 Diagnosed/Dispatched）——相位序列如实反映短路。

## 3. Dispatch 分支运行时行为

`dispatchReplanAction` 按 sealed `ReplanAction` 分支：

### 3.1 AcceptPartial（不可恢复，终态）

```text
dispatchReplanAction
 └─ action ∈ {DeviceFailure, PerceptionUnreliable} → AcceptPartial
     └─ terminalReason = ACCEPT_PARTIAL
     └─ terminal = true ── 不重试，executor 至此只被调过一次
     └─ 循环返回 → assembleOutput 输出 [DeviceFailure] 等占位
```

### 3.2 LocalReplan（可恢复·局部，受 terminalGuard）

```text
dispatchReplanAction ── PlanOrAnswerError(≤2 非空) → LocalReplan
 ├─ terminalGuard 检查：retryCount >= maxRetries 且非 AcceptPartial → MAX_RETRIES_EXCEEDED
 ├─ handleLocalReplan:
 │   ├─ redo = [failed 节点]，description 注入 " [correction: feedback]"
 │   ├─ 从 completed 移除 failed 节点的旧结果
 │   └─ runVerifyLoop(Plan(goal+" (局部重做)", redo), state.nextRetry())
 │        └─ 回到 §2 的 Execute+Verify（重跑）
```

LocalReplan 的 feedback 注入是打破"同 prompt 同输出"重试死循环的必要条件：executor 在重试时必须收到不同输入。

### 3.3 GlobalReplan（可恢复·全局，受 terminalGuard）

```text
dispatchReplanAction ── PlanOrAnswerError(>2 或空) → GlobalReplan
 ├─ terminalGuard 检查（同上）
 ├─ handleGlobalReplan:
 │   ├─ newPlan = planner.plan(userInput + " [correction: feedback]")
 │   ├─ state.completed.clear()
 │   └─ runVerifyLoop(newPlan, state.nextRetry()) ── 从干净状态重跑
```

### 3.4 INCONCLUSIVE（诚实边界）

```text
handleLocalReplan ── redo 集合为空（verifier 报告的 failed 节点不在 plan 中）
 └─ terminalReason = INCONCLUSIVE
 └─ 不改变 invoke 输出（只如实标记未干净终止）
```

这是 verifier/executor 契约错配的诚实标签，不是正常路径。

## 4. terminalGuard（不死循环）

```text
dispatchReplanAction（任意非 AcceptPartial action）
 └─ 若 retryCount >= config.maxRetries:
     └─ terminalReason = MAX_RETRIES_EXCEEDED
     └─ terminal = true ── 截断，不死循环
```

`AcceptPartial` 不受 `maxRetries` 约束——它是终态，不是重试。默认 `maxRetries=2` → verify 至多 3 次（初 + 2 retry）。

## 5. Verifier 容错运行时

verifier 经 `CompletableFuture.supplyAsync(..., Runnable::run)` 包裹执行，使 verifier 抛错可被分类捕获而不击穿 invoke：

```text
verify(userInput, completed)
 ├─ CompletableFuture.supplyAsync(() -> verifier.verify(...), Runnable::run)
 ├─ .join() 抛 CompletionException → 解包 cause
 │   ├─ RuntimeException → VerifyResult(hasThrown=true) ── 走 PerceptionUnreliable→AcceptPartial
 │   ├─ Error → 原样抛出（不吞 JVM 级错误）
 │   └─ 其他 → IllegalStateException（verifier 违反 unchecked-exception 契约）
 ├─ 返回 null → VerifyResult(hasParseFailure=true) ── 同上路径
 └─ 正常 → 原样 VerifyResult
```

两种降级（throw/null）都经 `PerceptionUnreliable` 走 `AcceptPartial`——不重试不可信判定。这是"不假承重"的运行时兑现：不可信的 FAILED 不能据以行动。

## 6. Trace emit 运行时

```text
emitTrace(trace, output)
 ├─ FutureTask<Void>(() -> sink.onTrace(trace)).run()
 ├─ sinkTask.get() ──InterruptedException/ExecutionException 被吞（隔离）
 │   └─ sink 抛错或被中断都不击穿 invoke 控制流
 └─ fire(AFTER_INVOKE, {payload: output, trace: trace})
```

- **FutureTask 桥隔离**：sink 抛错不传播到 invoke——invoke 输出与终态不受 sink 故障影响。
- **AFTER_INVOKE 双键**：context 同时含 `payload`（输出）和 typed `trace`；只读 `payload` 的 rail 不受影响，`trace` 键是 trace 消费 rail 的缝合点。
- **单次终态 emit**：trace 是闭环终态副产品，一次性 emit；不在循环中途反应（增量 trace 是 OUT）。

## 7. 并发与线程模型

| 项 | 约束 |
|---|---|
| invoke 线程模型 | 同步单线程 loop（`Runnable::run` 同步执行 verifier）；不 spawn 后台线程跑闭环本身。 |
| sink 隔离 | sink 在 FutureTask 内同步执行；sink 故障隔离，不影响控制流。 |
| 实例 scope | `PevTraceSink` 是 PEVAgent 实例字段；非进程级 static——规避并发实例污染与静默安装 footgun。 |
| stream 降级 | `stream` = `List.of(invoke(...)).iterator()`——同步一次 invoke 后单元素返回；真流式（异步 token）属 OUT。 |
| 跨调用无状态 | `completed`/`terminal`/`retryCount` 都是 invoke 局部（VerifyLoopState）；PEV 实例跨 invoke 不保持闭环运行态。 |

## 8. 资源生命周期与关闭

PEV 闭环在 invoke 同步跑完，不持有跨 invoke 的连接/订阅/线程池资源。资源生命周期主要由注入组件决定：

- 三阶段 SPI（Planner/Executor/Verifier）持有的资源（LLM client、工具连接、verifier 后端）由**宿主**管理生命周期；PEV 不拥有。
- `PevTraceSink` 是 PEVAgent 实例字段，随 PEVAgent 实例回收；无独立 close。
- 被 runtime 包装时，runtime 负责 Task/连接/线程池的生命周期，PEV 不参与。

不变量：PEV 不泄漏连接/线程/订阅（它不创建这些）；invoke 不无限挂起（terminalGuard + verifier CompletableFuture join 有界）。

## 9. 关键运行时不变量（非直觉）

只列最易被违反/非直觉的不变量（相位流、IFF 契约等见前文各节）：

- **AcceptPartial 不受 maxRetries 约束**——它是终态不是重试，maxRetries 截不住它。
- **verifier 抛 `Error`（非 RuntimeException）原样抛出**——不降级、不吞 JVM 级错误。
- **`terminalReason` 永不 null**——即便 redo 空集合也落 INCONCLUSIVE，不返回 null。

## 10. 与其他视图的衔接

- 领域对象与状态归属、kernel 决策模型：`logical.md`。
- 代码分层、依赖红线、构建基线：`development.md`。
- 部署形态、网络/持久化边界、sink 消费拓扑：`physical.md`。
- 技术场景路径（直通/重做/降级/截断/rail/trace）：`scenarios.md` TS-01 ～ TS-09。
- 接入契约（invoke/SPI/rail/sink 形态）、对 runtime 要求：L2 `Feat-Func-023-pev-selfheal-loop.md`。
