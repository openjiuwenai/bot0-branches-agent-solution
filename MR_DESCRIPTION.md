# feat: PEV agent 编排模块 + react-rails 认知 rails + ToolCallingEnforcingModel

## 概述

`common/agent-runtime-ext-java/agent-patterns/` 下新增两个 sibling 模块，给 agent-core-java 补上：

1. **PEV**（Plan→Execute→Verify→Diagnose→Dispatch）—— 自包含的 agent 编排内核，extends BaseAgent，适用 plan-driven 范式
2. **react-rails** —— ReActAgent 的三个认知 rail（外部评判校验 CriteriaVerificationRail / replan 计数 ReplanRail / 设备故障自愈 RootCauseRail）+ **ToolCallingEnforcingModel** 单次探针

两模块仅依赖 agent-core-java:0.1.12-jdk17 + 第三方（spring-boot-autoconfigure / junit / assertj），与现有 versatile/agentcore-ext 零耦合。

---

## 模块一：PEV

### 架构

PEV = **P**lan → **E**xecute → **V**erify → **D**iagnose → **D**ispatch 闭环。核心决策逻辑约 50 行，sealed types 让编译器当防火墙。

### 类型契约（Java 21 sealed types）

| 类型 | 态 | 角色 |
|------|-----|------|
| `NodeResult` | Success / DeviceFailure / VerifierFailure | 节点执行结果 |
| `RootCause` | DeviceFailure / PlanOrAnswerError / PerceptionUnreliable | **诊断输出**：为什么 verify 失败？ |
| `ReplanAction` | AcceptPartial / LocalReplan / GlobalReplan | **调度输出**：下一步做什么？ |

`PevKernel` 纯函数：
- `diagnoseRootCause(verifyResult, nodeErrors)` → `RootCause`（3 态诊断）
- `toReplanAction(cause, feedback, failedNodes)` → `ReplanAction`（3 态调度）

switch 穷举——删任一 case arm → 编译红。

### 调度策略

| RootCause | → ReplanAction | 永不 |
|----------|---------------|------|
| DeviceFailure | AcceptPartial（设备故障重试无效） | ✓ 永不重试 |
| PerceptionUnreliable | AcceptPartial（verifier 不可信） | ✓ 永不重试 |
| PlanOrAnswerError (≤2 节点) | LocalReplan（精确失败节点列表 + correction hint） | - |
| PlanOrAnswerError (>2 或空) | GlobalReplan（LLM 重新规划） | ✓ 永不 AcceptPartial |

### PEVAgent 主循环

```java
PEVAgent agent = new PEVAgent(
    AgentCard.builder().build(),
    planner,           // Plan：拆任务为节点
    executor,          // Execute：按节点类型调度（TOOL_CALL/LLM_CALL/SUB_AGENT）
    verifier           // Verify：判 PASS/FAIL
);
Object output = agent.invoke("查询案件 CLM-2026-REDUCE 的信息。", null);
```

- 继承 `BaseAgent`，`invoke` 内自含循环
- `terminalGuard` AtomicBoolean 防双终端信号
- `maxRetries`（默认 2）闭环
- 接受 Partial → `COMPLETED-degraded` 诚实终态

### 组件 SPI

| SPI | 职责 | 内置实现 |
|-----|------|---------|
| Planner | 输入 → Plan(节点列表) | `LlmPlanner`（LLM 产结构化计划） |
| Executor | 执行节点 → Map<nodeId, NodeResult> | `ToolBackedExecutor`（LLM 推理 / 工具派发 / LLM 子 agent 隔离） |
| Verifier | 验证结果 → VerifyResult | `LlmVerifier`（LLM 判 PASS/FAIL） |

`ToolBackedExecutor` 按 **longest-match** 把节点描述里的工具名匹配到注册工具；匹配不上走 LLM 推理。TOOL_CALL 节点执行前 sanitize description/inputs/correctionHint（去除占位符泄漏），LLM_CALL 节点支持 correction hint 注入（DEFECT-B 治本）。

### 测试：22 测试

| 层 | 数量 | 证什么 |
|----|------|--------|
| kernel（纯函数 dispatch） | 8 | sealed exhaustiveness + IFF 契约 + mutation-RED |
| agent 控制流 | 4 | PEVAgent 闭环 / terminalGuard / maxRetries |
| rail（横切观察） | 4 | criteria 关键词 / device-failure 标记 |
| 真 LLM e2e | 6 | PEVAgent + 真 LLM 数据通道（详见测试报告） |

---

## 模块二：react-rails

### 为什么需要

ReActAgent 原生只有 reason+act 循环，**无 verify 能力**（javap 实证 agent-core-java 0.1.12-jdk17 的 ReActAgent.invoke）。SDK 的 `VerificationRail` 是 prompt-injection（提醒 LLM 自我验证，作者不认可这是 verify），不是 external-judge。三条 rail 在 `afterModelCall` 钩子里补上 external-judge 验证 + replan 收敛 + 设备故障诚实降级。

### 三条 rail

#### CriteriaVerificationRail（external-judge gate）

afterModelCall 检测最终答案 → `CriteriaVerifier.verify()` → PASS → `forceFinish(verified=true)` / FAIL → `forceFinish(degraded=true, unmet=[...])`。**双方向 gate**：verify 通过锁正确终态，不通过诚实降级。

```java
agent.registerRail(new CriteriaVerificationRail(
    new RuleBasedCriteriaVerifier(),
    List.of("必须包含金额", "必须引用风险评估")));
```

- `CriteriaVerifier` SPI：外部 judge 接口
- `RuleBasedCriteriaVerifier`：关键词覆盖率（确定性，零 LLM 开销）
- RuleBased 的 ASSUME_FAIL 单测：验证基准优先，防止恒真默认值
- 仅 `afterModelCall` 钩子（不干扰 tool-execution 回调）

#### ReplanRail（replan 计数 / 超限 escalate）

afterModelCall 检测 `__replan__` tool_call → 计数 → count>max 时 `forceFinish(degraded)`。

```java
agent.registerRail(new ReplanRail(2));   // 允许 2 次 replan
agent.getAbilityManager().add(new ReplanTool());  // 让 LLM 看到并可调用
```

- `ReplanTool` 虚拟工具（extends Tool），`registerOnto(agent)` 双注册契约（见注册架构）
- `ReplanRail` synchroinzed afterModelCall，线程安全计数
- 超限后 `forceFinish` 的 degraded map 含 DEGRADED_KEY

#### RootCauseRail（device-failure degrade）

`onToolException` 标记 pendingDegrade → 下一轮 `afterModelCall` → `forceFinish(degraded)`。**双钩子设计**：tool 抛异常当下就记 pending（onToolException），等 LLM 下一轮产出后消费（afterModelCall）。

```java
agent.registerRail(new RootCauseRail());
```

- `onToolException`：单参数钩子，记录异常信息到 pendingDegrade
- `afterModelCall`：检查 pendingDegrade → forceFinish(degraded=true, rootCause=DeviceFailure)
- 与 `onException`（双参数）不同：两钩子在同可抛异常时只有 onException 触发（javap 分析）

### forceFinish gate 字节码实证

三条 rail 都用 `requestForceFinish(Map)` 在 `afterModelCall` 钩子里终止 ReActAgent 循环。**字节码证实消费**（javap -c -p ReActAgent.invoke）：

```
offset 225: consumeForceFinish → BEFORE_MODEL_CALL（not used here）
offset 700: consumeForceFinish → afterModelCall gate（rail 主消费点）
offset 878: consumeForceFinish → afterToolCall gate（onToolException 消费点）
```

`SpikeForceFinishOnReActAgent` 运行时实证：invoke 返回 forcedMap（非自然 LLM 响应）= consumeForceFinish 真短路。

### 注册架构：ReplanTool 双注册契约

`AbilityManager.add(Object)` **只收** ToolCard / WorkflowCard / AgentCard / McpServerConfig 四种类型。传 Tool 实例会静默丢弃（日志"Unknown ability type"，LLM 看不到 `__replan__`）。且 `executeSingleToolCall` 经 `getToolFromResourceMgr` 从 `Runner.resourceMgr()` 解析可执行——不查 AbilityManager。所以正确注册需 **两步**：

```java
// 1. LLM 可见性
agent.getAbilityManager().add(tool.getCard());
// 2. 运行时派发
Runner.resourceMgr().addTool(tool, null);
```

**`registerOnto(agent)` 静态方法做以上两步，是唯一正确入口。** `id == name` 不变性：`executeSingleToolCall` 按 `card.getId()` 解析 key，`ResourceMgr.addTool` 按 `card.getId()` 存储——两者匹配唯一条件是 id 值显式设置为本工具名。

### ToolCallingEnforcingModel（extends Model，关键新增）

在测试中发现 `OpenAiCompatibleModelClient`（SDK 生产客户端）在 ReActAgent 传空 model 时会序列化空串到 HTTP body → 所有 provider 返回 HTTP 400。

**设计**：
- extends Model，`invoke` 首调用 AtomicBoolean CAS 发不可拒 `__probe_tool__` 探针
- 探针返回无 tool_calls → throw `ToolCallingBypassException` fail-fast
- 探针过后后续 invoke 零开销（CAS false 一次跳过）
- **model name fallback**：ReActAgent 传 null/空 → `getModelConfig().getModelName()` 兜底

**LLM_THINKING env 多模式支持**（通过 kwargs 透传 OpenAiCompatibleModelClient 序列化）：
- `thinking-on` / `thinking-off` → `{"thinking":{"type":"enabled"/"disabled"}}`（DeepSeek）
- `qwen-on` / `qwen-off` → `{"enable_thinking":true/false}`（Qwen）
- `none` 或未设 → 不注入

构造与 Model 一致（`ModelClientConfig` + `ModelRequestConfig`），**drop-in 替换**：

```java
// Before:
Model m = new Model(cliCfg, reqCfg);
// After:
Model m = new ToolCallingEnforcingModel(cliCfg, reqCfg);
agent.setLlm(m);  // 不变——多态
```

**暂不接入 autoconfig**（GEPA 判定 rails 模块不做 client provider，应拆 sibling 模块，后续独立 PR）。

### AutoConfiguration

`ReactRailsAutoConfiguration`（@AutoConfiguration，@ConditionalOnClass{ReActAgent, AgentRail}）：
- BeanPostProcessor 将三个 rail 挂到每个 ReActAgent bean 上
- `@ConditionalOnMissingBean(CriteriaVerifier.class)` → 默认 `RuleBasedCriteriaVerifier`
- 属性 `reactrails.enabled` / `reactrails.criteria` / `reactrails.max-replan`
- `ReplanTool.registerOnto()` 在 autoconfig 内部调用——全自动

### 测试：17 测试

| 层 | 数量 | 证什么 |
|----|------|--------|
| CriteriaVerificationRailTest | 3 | 双向 forceFinish + ASSUME_FAIL 安全网 |
| ReplanRailTest | 4 | __replan__ 计数 + 超限 escalate + 线程安全 |
| RootCauseRailTest | 3 | onToolException → degrade + dual-hook 协作 |
| ReplanToolRegistrationTest | 2 | registerOnto 双注册（LLM 可见性 + 运行时派发） |
| SpikeForceFinishOnReActAgent | 1 | IFF 承重：consumeForceFinish offset 225/700 真消费 |
| ToolCallingEnforcingModelTest | 2 | bypass 探针 catch + 合法终态 pass-through |
| 真 LLM e2e | 3 | 真 ReActAgent + 真 LLM 数据通道（详见测试报告） |

---

## 真 LLM e2e 测试报告（跨 3 模型 × 双 thinking 模式）

### deepseek-v4-flash（直连，推荐首选）

| 模块 | 测试 | thinking=off | thinking=on |
|---|---|---|---|
| PEV | StraightThrough | 6.9s ✅ | 6.2s ✅ |
| PEV | DeviceFailureDegrades | 8.8s ✅ | 7.1s ✅ |
| PEV | MultiStepTools | 5.6s ✅ | 3.3s ✅ |
| PEV | PEVAgentRealLlm | 11.5s ✅ | 3.9s ✅ |
| PEV | AdversarialCatch | 6.7s ✅ | 27.0s ✅ |
| PEV | **ClaimsAdjudication** | **24.8s ✅** | **30.1s ✅** |
| react-rails | CriteriaVerification | 2.8s ✅ | 3.3s ✅ |
| react-rails | **ReplanRail** | **6.3s ✅** | **7.8s ✅** |
| react-rails | RootCauseRail | 2.6s ✅ | 4.9s ✅ |
| **合计** | | **9/9 ✅** | **9/9 ✅** |

flash 直连 18/18 全绿。ClaimsAdjudication（最复杂理赔场景）flash 24.8–30.1s。

### deepseek-v4-pro（直连）

- PEV: 6/6 ✅ / react-rails 3/3 ✅ / 双 thinking 模式 = 18/18 全绿
- RootCause e2e 实测 LLM 真发 `tool_call(fetchData({"source":"orders"}))` → AlwaysFailTool 抛异常 → RootCauseRail 降级触发（11.1s 端到端实证 bypass 被击碎）

### Qwen3.5-35B a3b（OpenRouter）

- PEV 12/12 ✅（off+on） | react-rails 5/6 ✅
- **唯一主动调了 `__replan__` 工具的模型**：log 实证 `tool_call(__replan__({"replan_reason":"..."}))`
- ReplanRail thinking=on 失败原因为 OpenRouter 内部 DeepInfra provider 对第二轮带 tool_call 历史的消息体返回 400（非代码 bug，直连 deepseek 该测试全绿）
- OpenRouter 较慢（ClaimsAdjudication 258-578s vs flash 25-30s）

---

## 测试心得（设计决策的依据）

### 1. 工具 bypass 被铲除

删 `LlmBackedModelClient`（手写文本客户端静默丢弃 tools 参数）。3 个 e2e 改用 `ToolCallingEnforcingModel` + `OpenAiCompatibleModelClient`（SDK 生产客户端）。旧的 bypass 模式是历史遗留，技术上是：测试注册了 model factory 产生 `LlmBackedModelClient`（extends `BaseModelClient`），其 `invoke` 只调 `LlmClient.chat()`（纯文本 HTTP body，不带 tools 参数），ReActAgent 的 tool-execution 路径从未被触发——测试恒绿但什么也没测到。

### 2. LLM 真调工具需要 inputParams schema

`AlwaysFailTool` 初始 ToolCard 无 `.inputParams(...)`——`description` 提到"参数：source（数据源）"但 schema 缺失。实测 GLM-5.2 / deepseek-v4-pro / deepseek-v4-flash 在此情况下均倾向于在文本中"叙述"使用工具的意图而非真发 `tool_call`。补上 `inputParams(Map.of(...))` 后 deepseek 立即发出正确的 `tool_call(fetchData({"source":"orders"}))`。结论：**ToolCard 的 description 参数描述不足，必须有完整的 inputParams schema 才能让 LLM 形成 tool_call。** 这不只是 UX 问题——缺少 schema 时 LLM 不把工具当可调用单元。

### 3. SDK 的 OpenAiCompatibleModelClient 不 fallback model name

`ReActAgent.railedModelCall` 调用 `Model.invoke(..., model=null, ...)`，`Model.invoke` 原样透传给 `this.client.invoke()`。`OpenAiCompatibleModelClient` 在构建请求体时读的是 `ModelRequestConfig.getModelName()` 而不是 fallback——它直接从入参的 model 参数读。当入参为 null 时，序列化到 JSON body 的是空字符串 "" → 所有 provider 返回 `HTTP 400: model code cannot be empty` / `but you passed .`。**`ToolCallingEnforcingModel` 的 `if (model == null || model.isEmpty()) model = getModelConfig().getModelName()` 承当了这一层兜底。**

### 4. 硬核承重在 mock，e2e 软观察诚实

所有真 LLM e2e 遵守"分层承重"原则：
- **mock 测试**硬断言控制流（mutation-RED：剥某代码 → 测试红），证明通道装配正确
- **e2e 基线断言**始终执行（result 非 null + 计数器可读），证明 infra 健康
- **e2e 条件断言**仅在 LLM 触发预期路径时执行（非确定），诚实标注"软观察：LLM 行为不确定"
- 每个 e2e 的 javadoc 明确说明 soft-observe 范围

### 5. 跨模型验证的必要性

同一 prompt + 同一代码，三个模型工具调用行为差异显著：
- **deepseek-v4-flash/pro**：按 prompt 指令调用工具可靠，适合作为主要验证模型
- **Qwen3.5-35B**：更主动调用工具（唯一真调 `__replan__`），but OpenRouter provider 路由不稳定
- **GLM-5.2**：倾向在文本中"叙述"工具调用而非真发 tool_call，需更强指令

**结论：e2e 验证至少需两个 provider（直连 + OpenRouter 备选），主模型推荐 deepseek-v4-flash（快+稳），交叉验证用 Qwen。**

### 6. GEPA 方法论的收敛

该模块从 spring-ai-ascend PR#375 嫁接而来，经过 50 物种 CORRECTED GEPA 探索（157 agents / 8.7M tokens）确认嫁接适用性。嫁接点是 ReActAgent 的 afterModelCall forceFinish gate（字节码 offset 700 实证消费），非 PEVAgent（PEVAgent 无 forceFinish offset，走自含 verify-loop）。react-rails 的 forceFinish gate + PEV 的 sealed 纯函数 dispatch 是同一套认知能力的两种宿主形态，**互补不互斥**。

---

## 架构演进

### PEV 模块的 drift-debt ledger（与 spring-ai-ascend 同构类型族的关系）

`package-info.java` 含完整 drift-debt ledger，记录与 spring-ai-ascend 仓库同构类型族的已知分歧。当前 isomorphic 类型有：`NodeResult` / `RootCause` / `ReplanAction` / `VerifyResult`（5 field 含 threw）/ `PevKernel.diagnoseRootCause` + `toReplanAction`。Sealed switch 编译守卫各端自守，跨端漂移无声——ledger 是唯一跨端守卫。如果 agent-core-java 未来发布这些类型到公共包，两端都应删本地副本依赖 jar——ledger 即合并清单。

### 后续规划

- ToolCallingEnforcingModel 接入 autoconfig（拆 sibling 基建模块，非 rails 内）
- GEPA-lite 规划能力（ReActAgent 无 BetaPlan 落点，当前 defer）
- GroundTruthVerifier / 约束注入（优化 verify 精度）
- Per-node ReActAgent（Pregel 执行器内单节点循环）

---

## 基座

target: `common`（`module/common-runtime-java` 继任分支）。已离线 merge（`dcef1db`，零冲突）。合入后 agent-runtime-ext-java 下共 4 模块：versatile + agentcore-ext + pev + react-rails。

## CLA

所有提交 author = `Jun Yao <yaojun97@huawei.com>`，CLA 已签署（见 gitcode openJiuwen/agent-solution CLA 协议）。

## 模块统计

| | PEV | react-rails |
|---|---|---|
| 生产源文件 | 11 | 10 |
| 测试源文件 | 14 | 14 |
| mock 测试 | 16 | 14 |
| 真 LLM e2e | 6 | 3 |
| 总测试 | 22 | 17 |
| 新增代码行（约） | ~1000 | ~800 |
