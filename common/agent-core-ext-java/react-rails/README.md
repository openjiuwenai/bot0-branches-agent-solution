# react-rails — ReActAgent 认知能力补全（external-judge + replan + self-heal）

> 给 agent-core-java 的 ReActAgent 补三条认知 rail：criteria 验证、replan 计数、设备故障降级。
> ReActAgent 原生只有 reason+act 循环（无 verify）；本模块用 `afterModelCall forceFinish` gate
> 补上 external-judge 验证能力——**capability gap 由 50 物种 GEPA 探索确认**。

## 30 秒上手

本模块是纯 Java SDK，不包含 Spring 或自动配置。所有 rail 和工具都由应用显式注册。

```xml
<dependency>
    <groupId>com.openjiuwen</groupId>
    <artifactId>react-rails</artifactId>
    <version>0.1.0</version>
</dependency>
```

```java
ReActAgent agent = new ReActAgent(AgentCard.builder().build());
agent.setLlm(model);

// 注册三条认知 rail
agent.registerRail(new CriteriaVerificationRail(
        new RuleBasedCriteriaVerifier(),
        List.of("给出配置建议", "引用风险评估")));
ReplanRail replanRail = new ReplanRail(2); // maxReplan=2
agent.registerRail(replanRail);
agent.registerRail(new RootCauseRail());

// 注册 ReplanTool（让 LLM 能表达 replan 意图）
ReplanTool.registerOnto(agent);

Object result = agent.invoke("分析这个投资组合", null);
// result 是 forcedMap（不是自然 String）——forceFinish gate 在 afterModelCall 真消费
```

## 三条 Rail

### CriteriaVerificationRail（external-judge gate）

**补的 gap**：ReActAgent 无 verify 方法（javap 实证）。SDK 的 `VerificationRail` 是 prompt-injection（提醒自我验证），不是 external-judge。

**机制**：`afterModelCall` 检测最终答案 → `CriteriaVerifier.verify()` → PASS→`forceFinish(verified=true)` / FAIL→`forceFinish(degraded=true, unmet=[...])`

```java
// 规则验证（确定性，零 LLM）
agent.registerRail(new CriteriaVerificationRail(
    new RuleBasedCriteriaVerifier(),
    List.of("必须包含金额", "必须引用风险评估")));
```

### ReplanRail（replan 计数/超限 escalate）

**补的 gap**：ReActAgent 无 replan 意识——LLM 可能反复换策略不收敛。

**机制**：`afterModelCall` 检测 `__replan__` tool_call → 计数 → count>max 时 `forceFinish(degraded)`

```java
agent.registerRail(new ReplanRail(2));  // 允许 2 次 replan，第 3 次 escalate
ReplanTool.registerOnto(agent);  // LLM 可见且运行时可派发
```

### RootCauseRail（device-failure degrade）

**补的 gap**：ReActAgent 工具失败时继续跑 maxIterations（浪费轮次）。

**机制**：`onToolException` 标记 pendingDegrade → 下一轮 `afterModelCall` → `forceFinish(degraded)`（设备故障重试无效，诚实降级）

```java
agent.registerRail(new RootCauseRail());
// 任何注册的工具抛异常 → rail 自动降级终止
```

## 架构：forceFinish gate

三条 rail 都用 `requestForceFinish(Map)` 在 `afterModelCall` 钩子里终止 ReActAgent 循环。**承重实证**（spike gate）：

```
ReActAgent.invoke 字节码：
  offset 220: fireCallbackEvent(AFTER_MODEL_CALL)
  offset 225: consumeForceFinish  ← rail 的 forceFinish 在这里被消费，短路 invoke
  ...
  offset 700: 同上（主循环内的第二消费点）
```

SpikeForceFinishOnReActAgent 运行时实证：invoke 返回 forcedMap（不是自然 LLM 响应）= consumeForceFinish 真短路。

## 模块结构

```
src/main/java/com/openjiuwen/agents/reactrails/
├── verification/
│   ├── CriteriaVerificationRail.java   afterModelCall forceFinish 双向 gate
│   ├── CriteriaVerifier.java           接口（verify(successCriteria, output, history)）
│   └── RuleBasedCriteriaVerifier.java  关键词覆盖（确定性，零 LLM）
├── replan/
│   ├── ReplanRail.java                 afterModelCall __replan__ 计数/超限 escalate
│   └── ReplanTool.java                 虚拟工具（extends Tool，__replan__）
├── selfheal/
│   └── RootCauseRail.java              onToolException + afterModelCall 双钩子 degrade
└── types/
    └── Violation.java                  极简 record
```

## 测试

| 层 | 数量 | 证什么 |
|----|------|--------|
| mock 承重 | 10 | 每 rail 控制流硬断（mutation-RED）|
| real LLM e2e | 3 | 真 ReActAgent + 真 GLM-5.2 数据通道 |
| spike gate | 1 | forceFinish offset 225/700 真消费 |

13 测试全绿。诚实分层：mock 证控制流 + real LLM 证数据通道。

## 依赖

`agent-core-java` 0.1.13（ReActAgent / AgentRail / AgentCallbackContext / Tool / ToolCard / AbilityManager）；Java 17。模块不依赖 Spring、runtime-ext 或具体 Agent 实现。

```bash
mvn -f common/agent-core-ext-java/pom.xml -pl :react-rails -am test
```

## 设计背景

50 物种 CORRECTED GEPA 探索确认：gitcode ReActAgent 缺 external-judge verifier + GEPA-lite + degraded terminal。本模块补 external-judge + degraded terminal（GEPA-lite defer——ReActAgent 无 BetaPlan 落点）。

与 `common/agents/pev` 的关系：两者是独立产物且没有依赖关系。PEV 自带 verify-loop dispatch；react-rails 通过显式注册扩展 ReActAgent。
