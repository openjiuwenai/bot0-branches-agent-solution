# 意图转调出站机制迁移设计：executor 自调 → runtime a2a_delegate 中断续跑

> 状态：设计稿（待评审）。对应 L2 设计
> `agent-solution-docs/develop/03-architecture/L2-Low-Level-Design/agent-runtime/Feat-Func-002-versatile-controller-intent-message-routing.md`
> 的 2.3 / 2.4 / 3.8 / 4.4 / 4.5 / 4.6 章修订。
> 前置：`DUPLICATE_MESSAGE` 挂死安全修复已先行落地（见 git log）。

## 1. 背景与动机

### 1.1 生产旅程约束

客户端永远只面对 L1 runtime；所有轮次（含中断后的续接轮）都经由 runtime 的
A2A 转发机制与中断机制完成，不存在客户端直连 L2。

### 1.2 现状缺口

- executor 自调出站（`RemoteAgentCaller.callOutcome`），下游 `INPUT_REQUIRED`
  依赖模块内部 `CrossAgentResumePort` 续接——该 port 无默认实现，旅程终止于
  `VERSATILE_HANDOFF_CROSS_AGENT_RESUME_UNSUPPORTED`（demo 场景 8 验收现状）。
- runtime 已有完整的中断-续跑链（`A2AEnabledServeOrchestrator` +
  `RemoteInvocationBatchCoordinator`）：shadow task 持久化、`remoteTaskId` 续调
  同一远端 task、push notification 断线恢复。但只消费
  `context._interrupt_kind="a2a_delegate"` 形状的中断。
- 形状不匹配：handoff 中断是 `{message, _interrupt:{targetAgentId, remoteTaskId}}`，
  runtime 视其为本地中断直接转发客户端，不建立可续跑状态。

### 1.3 为什么不能只换 payload

`RemoteInvocationBatchMapper.parseBatch`（runtime）从 item 只读
`toolCallId` / `toolName` / `context.agentName` / `message`，**不接受外部
`remoteTaskId`**——member 的 remoteTaskId 只能由协调器自己发调用后捕获，或从
shadow 快照恢复。因此把现有中断改成 a2a_delegate 形状只会让 runtime 对 L2
**再发一次全新调用**，executor 已发出的那次成为孤儿。正确含义是：**把出站调用
本身移交给 runtime**，与基线 delegate（`VersatileResponseExtractor.buildA2aDelegateInterrupt`
契约）统一管道。

## 2. 目标架构

```
控制器 SSE → IntentHandoffClassifier 识别（不变）
          → HandoffTargetResolver 目标解析（不变）
          → 产出单 item a2a_delegate 中断（agentName=目标， message=用户 query，
            resume=true）替代 executor 出站
          → runtime 协调器发调用/捕获 remoteTaskId/存 shadow task
第一轮 L2 INPUT_REQUIRED → 中断呈现给客户端（runtime 现有行为）
第二轮客户端补 input → L1 task resume → 协调器用 remoteTaskId 续调同一 L2 task
          → L2 判不在范围 → not-in-scope 信封作为 remote 结果回传
          → resume=true 触发 runtime re-invoke 本层 handler（remoteToolResults 进 metadata）
          → handler 入口识别信封 → 重跑控制器重新识别（upstream-signal 语义平移）
```

`CrossAgentResumePort` 及 executor 出站段（`buildRemoteCall`/超时/终态映射）退役。

## 3. 关键语义迁移表

| 现机制（executor 路径） | 迁移后（runtime 路径） |
| --- | --- |
| NOT_IN_SCOPE 后 handler 链内 while 重跑控制器 | runtime re-invoke handler + handler 入口检查 `runtime.remoteToolResults` 中是否含 `HandoffSignals` 信封 |
| `HandoffSignals` 信封由 executor 在 outcome 上识别 | 信封作为 L2 终答交给协调器；识别逻辑移到 handler 入口（metadata 检查），识别后抑制信封不透传用户/控制器 |
| 单请求 dedup / redirect / DUPLICATE_TARGET guard | 单 item 批内无链内重发，`DUPLICATE_MESSAGE` 链路整体消失；「续接弹回后再转调同一目标」由 handler 依据 `runtime.remoteBatchId` / `runtime.remoteToolResults` 把弹回 target 记入本轮 state，再转调同目标 → `DUPLICATE_TARGET` 明确报错 |
| 跨请求 trace（hopCount/routeTrace/sourceAgentId）由 `prepareOutbound` 构造进 `RemoteCall.metadata` | 协调器 `start()` 的 outboundMetadata 取自 `batch.request` metadata（不取 item）——trace 键必须进入 request metadata 并确认协调器透传，或扩展 item 契约携带 trace |
| 错误码 `VERSATILE_HANDOFF_TIMEOUT/TARGET_UNAVAILABLE/...` | 协调器 member fail 语义（`REMOTE_TIMEOUT/REMOTE_UNAVAILABLE/REMOTE_RATE_LIMITED/REMOTE_PROTOCOL_ERROR`）；需在 handler 层映射回 `VERSATILE_HANDOFF_*` 或重新定义错误码分层 |
| 流式 chunk 经 `DownstreamEventBridge` 直推 observer | 协调器 `MemberEventObserver` → `SerialQueryStreamObserver` 转发；需验证事件顺序/完整性等价 |
| 下游超时 `handoff.timeout` | 协调器调度超时（队列/并发参数）语义不同，需对齐 |

## 4. 待确认的 runtime 侧行为（迁移前置调研项）

1. **resume=true 的 re-invoke 请求构造**：`buildBatchResumeRequest` 保留原
   messages 并注入 `runtime.remoteToolResults` metadata。request extractor
   是否会把它注入控制器会话（作为工具/agent 消息）需确认；信封不应作为对话
   内容进入控制器——识别后应在 extractor 之前抑制。
2. **request metadata 透传链**：trace 键放进 L1 入站 request metadata 后，
   协调器出站是否原样透传（`outboundMetadata` 的 allowlist 行为）。
3. **单 item 批的中断呈现形状**：客户端看到的中断 payload（items 形态）
   与现 demo 场景 8 断言的兼容性。
4. **streaming 模式下协调器转发顺序**与 `DownstreamEventBridge` 的差异。

## 5. 分阶段落地

- **阶段 0（已完成）**：`DUPLICATE_MESSAGE` 弹回链补驱动终态，现网不挂死。
- **阶段 1**：executor 产出 a2a_delegate 中断（resume=true），打通第一轮：
  转调 → L2 INPUT_REQUIRED → 中断呈现客户端；错误码映射对齐。
  demo 场景 8 断言从 `RESUME_UNSUPPORTED` 改为可续接中断。
- **阶段 2**：第二轮续接旅程：客户端补 input → 协调器 remoteTaskId 续调 L2 →
  L2 弹回信封 → re-invoke 重识别；handler 入口信封识别 + 弹回 target 记入
  本轮 state（`DUPLICATE_TARGET` 保护）。
- **阶段 3**：跨请求 trace 迁移、流式对齐、L2 spec 2.3/2.4/4.5/4.6 改写、
  `CrossAgentResumePort` 与 executor 出站段退役。
- **验收**：L2 spec 7.2 场景旅程全量 + 新增「中断→续接→弹回→重识别」旅程。

## 6. 风险

- 协调器错误语义与模块错误码不一致，客户端可观测性回归。
- 双形态并存期（基线 delegate 三字段信封中断 vs 意图转调中断）识别优先级
  需明确：转调识别仍先于基线错误映射（spec 3.1-3.7 顺序不变）。
- resume 请求的 metadata 注入对控制器会话的副作用（第 4.1 项）。
- runtime 与本模块分仓演进，协调器契约变更无编译期约束，需以集成测试钉住。
