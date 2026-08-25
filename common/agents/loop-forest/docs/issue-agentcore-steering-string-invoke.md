# Issue: `ReActAgent.invoke(String, null)` 分支下 steering 通道静默失效（pushSteering silent no-op）+ 主循环非 AssistantMessage 退出分支无视 pending steering（缺陷 #2）

**影响仓库**：agent-core-java（冻结层，需跨仓决策）
**实证版本**：agent-core-java 0.1.14.post1（字节码核验）+ 0.1.12（SteeringProvisionRail 注释中的 offset 引用）
**发现日期**：2026-08-24（graph-loop bench e2e 调试中实证）
**已知 workaround**：SteeringProvisionRail（edpa-alpha 已合入生产装配）

---

## 1. 问题陈述

`ReActAgent.invoke(Object, Session)` 有两个入参分支：

- **Map 分支**（`invoke(Map, session)`）：从入参 Map 复制 `loop_queues`（等 steering 队列）到 ctx.extra，`bindSteeringQueue` 能读到并绑定 → steering 正常。
- **String 分支**（`invoke(taskString, session)`，常见形态 `invoke(task, null)`）：**物理上不经过** `loop_queues` 的复制路径 → `ctx.extra` 里没有 `loop_queues` → `bindSteeringQueue` 读到 null → `ctx.steeringQueue` 保持默认 null。

此后**所有** `ctx.pushSteering(String)` 调用命中 `AgentCallbackContext.pushSteering` 的静默 guard：

```java
// AgentCallbackContext.pushSteering 字节码（0.1.14.post1）
0: getfield steeringQueue
4: ifnull 17        // ← 队列 null 直接 return，不抛错不告警
7: ... pushSteering 正常路径
17: return          // ← 静默丢弃
```

**结果**：String 分支宿主上，一切依赖 steering 通道的机制（中途转向 / 进度注入 / 重锚消息 / 探索发现注入）**全部静默失效**——调用方拿到正常返回值，遥测计数照常递增（生产者侧），但消息**从未进入任何一次模型请求**（消费者侧零到达）。

## 2. 字节码证据链（0.1.14.post1 核验）

**invoke 的 Map-only 复制**（`ReActAgent.invoke`）：

```
45: ifeq 140                    // instanceof Map？否 → 跳 140（String 路径）
  ...Map 分支内：
131: ldc  "loop_queues"
134: invokestatic copyInvokeExtra // ← 仅 Map 分支可达
140: （String 路径起点——无任何 queue 复制）
```

**bindSteeringQueue 只认 extra 里的 loop_queues**：

```
12: getExtra()
16: ldc "loop_queues"
19: Map.get
26: instanceof SteeringQueue
29: ifeq 42                     // 不是 → 直接结束（不绑定）
39: ctx.bindSteeringQueue(queue)
42: return
```

**pushSteering 的静默 guard**（见上）。

三个环节叠加 = String 分支下 steering 全链路 no-op，且无任何日志/异常暴露。

## 3. 复现路径（最小化）

```java
ReActAgent agent = new ReActAgent(card);
agent.setLlm(...);
// 任意 rail：
agent.registerRail(new AgentRail() {
    @Override public void afterModelCall(AgentCallbackContext ctx) {
        ctx.pushSteering("should reach the model");   // ← 被静默丢弃
    }
});
Object result = agent.invoke("do something", null);   // ← String 分支，触发缺陷
// 观察：模型请求 messages 中永远不会出现该消息；无任何错误。
```

判定方法：hook 里打印 `ctx.hasSteeringQueue()`——String 分支整个轨迹恒 `false`。

## 4. 影响面

| 面 | 影响 | 说明 |
|---|------|------|
| **所有 String-invoke 宿主**（CLI 单任务、A2A 文本路径、e2e 测试、bench harness） | **高** | 任何在 String 分支上使用 pushSteering 的 rail 静默失效。`JiuwenCoreAgentHandler` A2A 路径同病（issue#13 已记） |
| Map 分支宿主（DeepAgent 等自带 loop_queues） | 无 | 复制路径可达 |
| 已挂 SteeringProvisionRail 的装配（EDPA 生产栈） | 已缓解 | beforeInvoke 绑 `new LoopQueues()` 兜底 |
| 静默失败的可观测性 | **高** | 无日志无异常无遥测区分——调用侧与生产侧计数正常，只有消费者侧（模型请求）能发现。实验类宿主极易误判"机制已生效"（实测案例：14 轮 e2e 调试误判注入生效，实际是语料词元匹配假象） |
| 框架 API 语义 | **中** | `pushSteering` 作为公开回调 API，其"静默丢弃"语义未在任何 Javadoc 声明——调用方无法从契约得知前置条件 |

## 4b. 缺陷 #2：主循环"非 AssistantMessage"退出分支无视 pending steering

**主循环退出分支的不对称**（`ReActAgent` invoke 主循环，源码实证）：

```java
Object modelResult = callModel(ctx, context, tools);
...
if (!(modelResult instanceof AssistantMessage aiMessage)) {
    // ← 此分支【无】 hasPendingSteering 检查——直接 break，空 answer 退出
    invokeInputs.setResult(... /* output=空, result_type=answer */);
    break;
}
...
if (toolCalls == null || toolCalls.isEmpty()) {
    if (ctx.hasPendingSteering()) {
        continue;     // ← 只有这个分支有续跑检查
    }
    ...
}
```

**组合效应**：模型返回 null/异型响应时（如上游 LLM 客户端空响应），即使 rail 在
AFTER_MODEL_CALL 钩子里 push 了续跑 steering，该分支也直接退出——**steering
消费者（drainSteering→注入下一轮）永远没有机会运行**。实证（2026-08-24 bench
e2e）：响应异型退出时 `[STEERING] Continue` 消息 0 次进入请求，尽管
afterModelCall 确认 push 了。

**建议**：该分支同样加 `if (ctx.hasPendingSteering()) continue;`（与
toolCalls-empty 分支对称）——语义一致性 + 给"上游偶发空响应"一个自愈机会
（下一轮 drain 注入的 steering 会引导模型重试）。

## 5. 建议修复（agent-core 层的根修）

**首选：String 分支 auto-provision。** `ReActAgent.invoke` 的 String 路径上（构造 InvokeInputs 之后、`bindSteeringQueue` 之前）为 ctx.extra 自动放入 `new LoopQueues()`（与 Map 分支 `loop_queues` 键一致）。这样：

- String 宿主获得与 Map 宿主一致的 steering 能力（行为对齐）；
- 已挂 SteeringProvisionRail 的栈无冲突（其绑定幂等：`if (!hasSteeringQueue())`）；
- 无行为破坏：从不 push 的宿主多绑一个空队列，零副作用。

**配套（可观测性）**：`pushSteering` 在 queue 为 null 时至少打一行 WARN（含调用方 rail 类名）——静默丢弃违背最小意外原则；这次 14 轮调试误判的根因就是零可观测。

**缺陷 #2 修复**：主循环"非 AssistantMessage"退出分支加 `if (ctx.hasPendingSteering()) continue;`（见 §4b）。

**备选（若不想动 invoke）**：`bindSteeringQueue` 在 extra 无 loop_queues 时自动 new 一个默认队列（等价效果，位置更集中）。

## 6. 已知 workaround（过渡期）

挂 `SteeringProvisionRail`（edpa-alpha 生产代码，issue#13 治本产物）：在 `beforeInvoke`（invoke 尾部 hook，晚于 no-op 的 bindSteeringQueue、早于首次模型调用）为 ctx 绑定 `new LoopQueues()`。幂等、Map 路径 no-op。**局限**：EDPA-local，任何新 String-invoke 宿主必须记得挂它——坑会持续繁殖直到根修。

## 7. 相关记录

- edpa-alpha issue#13：首次实证（`pushSteering` silent no-op 双铁证）+ SteeringProvisionRail 治本
- 实验侧教训（2026-08-24，graph-loop bench）：注入类实验必须验证消息真的进了请求 messages（遥测计数≠到达）；该日 16 轮调试的最后一轮抓到此缺陷，避免了 24 发正式实验在"注入从未生效"条件下跑完
- `injectPendingSteering`（每轮 drainSteering → UserMessage 注入）本身工作正常——缺陷仅在队列绑定环节
