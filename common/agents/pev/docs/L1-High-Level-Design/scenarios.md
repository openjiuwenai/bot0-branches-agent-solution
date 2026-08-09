---
level: L1-HLD
module: agents/pev
TAG:
  - scenarios
  - technical-scenario
  - architecture-fact
status: active
updated: 2026-08-06
dependency:
  - overview.md
  - logical.md
  - development.md
  - process.md
  - physical.md
  - ../features/FEAT-023-pev-selfheal-loop.md
---

# PEV L1 架构场景视图

## 目的

本文档从 `agents/pev` 的 L1 模块定位反推技术场景，用于把 L1 架构概览、逻辑视图、开发视图、进程视图和物理视图连接到可验证的闭环运行路径。

本文档不承载业务场景。业务场景应从需求导入。本文只描述 PEV 作为自包含 agent-service-app、闭环执行体、kernel 决策核心和可观测出口时必须成立的技术路径。

## 场景边界

`agents/pev` 的技术场景围绕自包含 invoke 闭环、verify 失败诊断、dispatch 分支、相位 rail 缝合点和 kernel-native trace 展开。它不拥有服务端 Task 生命周期（runtime 拥有），不暴露 HTTP/A2A 服务面（runtime 承接），不做多 agent 编排。

当前版本场景遵循以下边界：

- 闭环在单次 `invoke` 内同步跑完；serving handler 只转发，不 override 控制流。
- 根因诊断依据确定性信号（verifier 是否抛错/不可解析、node 结果图、failed 集合），不依赖 LLM 自我报告。
- dispatch 是 sealed IFF 契约：不可恢复根因→AcceptPartial 永不重试；可恢复根因→Local/GlobalReplan 永不降级。
- 当前版本 stream 降级为单 chunk；真流式/多 agent/增量 trace 是显式排除（架构非目标）。
- PEV 已实现（origin/common）；本文场景是已验证的技术路径，承重测试覆盖（剥→RED IFF 范式）。

## TS-01 一次性直通（Verify 首次 PASS）

### 场景目标

verifier 首次即判通过时，闭环在 Verify 相位短路进入 PASSED 终态，不触发 Diagnose/Dispatch。验证"通过即终止"是真分支而非恒 fire 全部相位。

### 参与组件

| 组件 | 角色 |
|---|---|
| Planner | 产出 Plan |
| Executor | 执行节点 → NodeResult.Success |
| Verifier | 判定 isPassed && !hasParseFailure |
| PEVAgent | 短路 PASSED、装配输出 |
| PevTrace | 投影相位（无 Diagnosed/Dispatched） |

### 基本路径

1. 宿主调 `invoke(input)`。
2. Planner 产出 Plan；Executor 执行节点得 Success；completed 累积。
3. Verifier 判 `isPassed && !hasParseFailure`。
4. PEVAgent 短路：terminalReason=PASSED，不进 Diagnose/Dispatch。
5. assembleOutput 装配 completed；emitTrace 投递 PevTrace。

### 验证关注点

- trace 相位序列只有 Planned/Executed/Verified，**无 Diagnosed/Dispatched**（证短路是真分支）。
- PASSED 是真实终态，不是恒 fire 全部相位后的默认。
- `hasParseFailure=true` 时即使 isPassed=true 也不短路（不可解析 PASS 不可信）。

## TS-02 内容错误局部重做（LocalReplan → 通过）

### 场景目标

verify FAIL、根因 PlanOrAnswerError、failed 节点 ≤2 时，LocalReplan 只重做 failed 节点并注入 feedback，重跑 verify 通过即 PASSED。验证 feedback 注入打破"同 prompt 同输出"重试死循环。

### 参与组件

| 组件 | 角色 |
|---|---|
| Verifier | 判 FAIL，返回 failedNodes + feedback |
| PevKernel.diagnoseRootCause | → RootCause.PlanOrAnswerError |
| PevKernel.toReplanAction | → ReplanAction.LocalReplan(failedNodes, feedback) |
| PEVAgent.handleLocalReplan | 重做 failed 节点，description 注入 correction |
| Executor | 重跑重做节点 |

### 基本路径

1. Verify FAIL；diagnoseRootCause 得 PlanOrAnswerError({A})。
2. toReplanAction 得 LocalReplan({A}, feedback)。
3. handleLocalReplan：redo=[A]，A 的 description 拼入 " [correction: feedback]"，从 completed 移除 A 旧结果。
4. runVerifyLoop(Plan(goal+" (局部重做)", redo), nextRetry) → Execute(A) → Verify。
5. 重跑 verify 通过 → PASSED。

### 验证关注点

- 重做节点 description 含 "correction"（mutation-RED：剥注入→redoDescription 不含→RED）。
- 只重做 failed 节点，不重做已通过节点；executor 至少被调 2 次。
- feedback null/blank 时回退原 description，不破坏重试路径。

## TS-03 内容错误全局重规划（GlobalReplan）

### 场景目标

failed 节点 >2 或为空时，GlobalReplan 丢弃当前 plan，以带 correction 的 goal 全量重规划。验证大范围错误从干净状态重跑。

### 参与组件

| 组件 | 角色 |
|---|---|
| PevKernel.toReplanAction | → ReplanAction.GlobalReplan(feedback) |
| Planner | 以 userInput + " [correction: feedback]" 重 Plan |
| PEVAgent | completed.clear()，重跑闭环 |

### 基本路径

1. Verify FAIL；diagnose PlanOrAnswerError；failed >2 或空。
2. toReplanAction 得 GlobalReplan(feedback)。
3. newPlan = planner.plan(userInput + " [correction: feedback]")。
4. completed.clear()；runVerifyLoop(newPlan, nextRetry) 全新 Execute+Verify。

### 验证关注点

- 清空已完成结果（非增量修补）。
- feedback 注入重规划 goal（与 LocalReplan 对称）。
- 全新 plan 重新走 Execute+Verify。

## TS-04 设备故障诚实降级（DeviceFailure → AcceptPartial）

### 场景目标

某节点 Execute 返回 DeviceFailure 且与 verify failed 相交时，AcceptPartial 终态，executor 只调一次（不重试坏设备）。验证不可恢复根因永不重试。

### 参与组件

| 组件 | 角色 |
|---|---|
| Executor | 节点返回 NodeResult.DeviceFailure |
| Verifier | 判 FAIL，failedNodes 含该节点 |
| PevKernel.diagnoseRootCause | device ∩ failed 非空 → RootCause.DeviceFailure |
| PevKernel.toReplanAction | → AcceptPartial |
| PEVAgent | ACCEPT_PARTIAL 终态 |

### 基本路径

1. Execute 返回 {A: DeviceFailure(A, timeout)}。
2. Verify FAIL({A})。
3. diagnoseRootCause：device={A} ∩ failed={A} 非空 → DeviceFailure({A})。
4. toReplanAction → AcceptPartial。
5. terminalReason=ACCEPT_PARTIAL；不重试；输出含 [DeviceFailure]。

### 验证关注点

- executor 只被调一次（mutation-RED：剥 AcceptPartial→executor 被重试→execCount>1→RED）。
- 坏设备不因重试自愈——IFF 契约的硬约束。
- 输出 [DeviceFailure] 占位，非 Success。

## TS-05 Verifier 不可信降级（PerceptionUnreliable → AcceptPartial）

### 场景目标

verifier 抛 RuntimeException 或返回 null/不可解析时，降级为 PerceptionUnreliable→AcceptPartial，invoke 不被击穿。验证不可信判定不可据以行动。

### 参与组件

| 组件 | 角色 |
|---|---|
| Verifier | 抛 RuntimeException 或返回 null |
| PEVAgent.verify | CompletableFuture 捕获，置 hasThrown/hasParseFailure |
| PevKernel.diagnoseRootCause | → PerceptionUnreliable |
| PevKernel.toReplanAction | → AcceptPartial |

### 基本路径

1. verifier.verify 抛 RuntimeException（或返回 null）。
2. CompletableFuture 捕获 → VerifyResult(hasThrown=true)（或 hasParseFailure=true）。
3. diagnoseRootCause → PerceptionUnreliable。
4. toReplanAction → AcceptPartial；不重试。

### 验证关注点

- verifier 异常不击穿 invoke（CompletionException 解包分类）。
- hasThrown 压倒 hasParseFailure（抛错意味着无返回值可解析）。
- 不重试不可信判定（不可信的 FAILED 不可据以行动）。
- `Error` 原样抛出（不吞 JVM 级错误）。

## TS-06 重试上限截断（MAX_RETRIES_EXCEEDED）

### 场景目标

PlanOrAnswerError 持续不通过至 retryCount >= maxRetries 时，MAX_RETRIES_EXCEEDED 终态，不死循环。验证 terminalGuard 有界。

### 参与组件

| 组件 | 角色 |
|---|---|
| PevKernel | 持续产出 PlanOrAnswerError → Local/GlobalReplan |
| PEVAgent.dispatchReplanAction | terminalGuard 检查 |
| PevConfig | maxRetries（默认 2） |

### 基本路径

1. 连续 Local/GlobalReplan 后 verify 仍 FAIL。
2. dispatchReplanAction：retryCount >= maxRetries 且非 AcceptPartial。
3. terminalReason=MAX_RETRIES_EXCEEDED；截断。

### 验证关注点

- verify 至多 `maxRetries+1` 次（默认 3）（mutation-RED：剥 terminalGuard→死循环→测试挂→RED）。
- AcceptPartial 不受 maxRetries 约束（终态非重试）。

## TS-07 横切认知 rail 组合

### 场景目标

宿主注册示例 rail（CriteriaVerificationRail/RootCauseRail），rail 在相位事件被动触发，与 PEV 内部 verify/diagnose 独立。验证 rail 缝合点是 defense-in-depth 而非控制流分支。

### 参与组件

| 组件 | 角色 |
|---|---|
| CriteriaVerificationRail | 钩 afterInvoke，读 payload 校验成功关键词 |
| RootCauseRail | 钩 afterToolCall，累积 DeviceFailure 遥测 |
| BaseAgent.registerRail | 注册入口 |
| AgentCallbackContext | 相位事件投递 |

### 基本路径

1. 宿主 registerRail(CriteriaVerificationRail + RootCauseRail)。
2. 调 invoke；PEV 在相位边界 fire 事件。
3. RootCauseRail 在每个 afterToolCall 累积 DeviceFailure。
4. CriteriaVerificationRail 在 afterInvoke 校验输出含成功关键词。
5. rail 记录观测，不改 PEV 控制流。

### 验证关注点

- rail 与 PEV 内部 verify/diagnose 独立（不互相依赖）。
- rail 可被同级模式复用。
- rail 不改变 invoke 输出或终态。

## TS-08 可观测 trace 采集（sink opt-in）

### 场景目标

宿主构造器注入非 noop PevTraceSink，终态 emitTrace 投递完整 PevTrace。验证 trace 是终态副产品、sink 故障隔离、AFTER_INVOKE 双键。

### 参与组件

| 组件 | 角色 |
|---|---|
| PevTraceSink | 宿主注入（logger/OTel/Micrometer） |
| PEVAgent.emitTrace | FutureTask 桥投递 sink + fire AFTER_INVOKE |
| PevTrace | 终态相位序列 + terminalReason |

### 基本路径

1. 宿主构造 PEVAgent(..., sink)。
2. 调 invoke 跑完闭环。
3. emitTrace：FutureTask 内 sink.onTrace(trace)；fire AFTER_INVOKE({payload: output, trace})。
4. sink 消费 trace（落盘/上报）。

### 验证关注点

- sink 抛错被 FutureTask 桥隔离，invoke 输出与终态不受影响（mutation-RED：剥隔离→sink 抛错击穿→RED）。
- AFTER_INVOKE 双键：payload（输出）+ trace；只读 payload 的 rail 不受影响。
- trace 单次终态 emit（非循环中途反应）。
- sink 实例 scope（PEVAgent 字段），非进程级 static。

## TS-09 redo 空集合（INCONCLUSIVE，边界场景）

### 场景目标

verifier 报告的 failed 节点不在 plan 中（契约错配）时，handleLocalReplan redo 为空，INCONCLUSIVE 终态。验证契约违反时可观测而非静默。

### 参与组件

| 组件 | 角色 |
|---|---|
| Verifier | 返回 failedNodes 不在 plan 中 |
| PEVAgent.handleLocalReplan | redo 集合为空 |
| PevTrace | terminalReason=INCONCLUSIVE |

### 基本路径

1. Verify FAIL，failedNodes 含 plan 中不存在的节点 id。
2. dispatchReplanAction → LocalReplan。
3. handleLocalReplan：redo 集合为空。
4. terminalReason=INCONCLUSIVE；输出为当前 partial completed 装配。

### 验证关注点

- INCONCLUSIVE 只如实标记未干净终止，不改变 invoke 输出（不是 PASSED/ACCEPT_PARTIAL）。
- 是 verifier/executor 契约错配的可观测信号，非正常路径。
- terminalReason 永不 null（PevTrace 契约）。

## 场景对视图的覆盖

| 场景 | 主要验证的视图要素 |
|---|---|
| TS-01 直通 | process（短路）、logical（VerifyResult/PASSED）、observability（相位忠实） |
| TS-02 LocalReplan | process（feedback 注入）、logical（RootCause/ReplanAction 分层）、kernel（IFF） |
| TS-03 GlobalReplan | process（清空 completed）、logical（GlobalReplan） |
| TS-04 DeviceFailure | logical（NodeResult.DeviceFailure/交集）、kernel（不可恢复→AcceptPartial） |
| TS-05 PerceptionUnreliable | process（verifier 容错 CompletableFuture）、kernel（感知不可信） |
| TS-06 MAX_RETRIES | process（terminalGuard）、development（PevConfig） |
| TS-07 rail | development（SPI/rail）、process（相位缝合点） |
| TS-08 trace | observability（PevTrace/sink）、physical（消费拓扑）、process（FutureTask 隔离） |
| TS-09 INCONCLUSIVE | observability（四终态）、logical（诚实边界） |

## 场景覆盖

- TS-01～TS-09 覆盖 PEV 闭环全部可达终态（PASSED/ACCEPT_PARTIAL/MAX_RETRIES_EXCEEDED/INCONCLUSIVE）与全部 dispatch 分支。
- TS-09 是诚实边界（契约错配非正常路径），存在保证契约违反时可观测而非静默。
- 显式排除场景（真流式/多 agent/HTTP-A2A）见 FEAT-023 §2.2。
