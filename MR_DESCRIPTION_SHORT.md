## 概述

`common/agent-runtime-ext-java/agent-patterns/` 新增两个 sibling 模块，仅依赖 agent-core-java:0.1.12-jdk17 + 第三方，与现有 adapters 零耦合。

## 模块一：PEV (Plan→Execute→Verify→Diagnose→Dispatch)

自包含 agent 编排内核，extends BaseAgent，sealed types 编译器防火墙。

**类型契约（Java 21 sealed）**：
- `NodeResult` : Success / DeviceFailure / VerifierFailure
- `RootCause` : DeviceFailure / PlanOrAnswerError / PerceptionUnreliable（诊断输出：为什么 verify 失败）
- `ReplanAction` : AcceptPartial / LocalReplan / GlobalReplan（调度输出：下一步做什么）
- `VerifyResult` : 5 字段（passed/failedNodes/feedback/parseFailure/threw），`threw` 区分 verifier 抛异常 vs 非 JSON

**`PevKernel` 纯函数 dispatch（约 20 行）**：
- `diagnoseRootCause`：判断 DeviceFailure → AcceptPartial（永不重试），PerceptionUnreliable → AcceptPartial（永不重试），PlanOrAnswerError ≤2 节点 → LocalReplan，>2 → GlobalReplan
- `toReplanAction`：switch 穷举，删 case arm 编译红

**PEVAgent 主循环**：maxRetries 闭环 + terminalGuard 防双终端 + AcceptPartial → COMPLETED-degraded

**组件 SPI**：Planner（`LlmPlanner` LLM 产计划）/ Executor（`ToolBackedExecutor` longest-match 工具派发）/ Verifier（`LlmVerifier` LLM 判 PASS/FAIL），correction hint 注入（DEFECT-B 治本）

**22 测试**：8 kernel（sealed exhaustiveness + IFF 契约 + mutation-RED）+ 4 agent 控制流 + 4 rail（横切观察）+ 6 真 LLM e2e

## 模块二：react-rails

ReActAgent 原生只有 reason+act 循环，无 verify 能力（javap 实证）。三条 rail 在 afterModelCall hook 补上 verify + replan + degrade。

### CriteriaVerificationRail（external-judge gate）
afterModelCall 检测最终答案 → `CriteriaVerifier.verify()` → PASS→`forceFinish(verified)` / FAIL→`forceFinish(degraded, unmet=[...])`。双方向 gate，`RuleBasedCriteriaVerifier` 关键词覆盖率（确定性，零 LLM）。

### ReplanRail（replan 计数 / 超限 escalate）
afterModelCall 检测 `__replan__` tool_call → 计数 → count>max → `forceFinish(degraded)`。`ReplanTool.registerOnto(agent)` 双注册契约：`AbilityManager.add(card)` 让 LLM 可见 + `Runner.resourceMgr().addTool(tool)` 运行时派发（`AbilityManager.add(Tool)` 静默丢弃，只能收 ToolCard/WorkflowCard/AgentCard/McpServerConfig 四种）。

### RootCauseRail（device-failure degrade）
`onToolException` 标记 pending → 下一轮 `afterModelCall` → `forceFinish(degraded)`。双钩子设计：抛出当下记 pending，等 LLM 下一轮产出后消费。

### forceFinish gate 字节码实证
javap -c -p ReActAgent.invoke 证实 3 个 consumeForceFinish 点（offset 225/700/878）。`SpikeForceFinishOnReActAgent` 运行时实证 invoke 返回 forcedMap（非自然 LLM 响应）。

### ToolCallingEnforcingModel（extends Model，关键新增）

SDK 的 `OpenAiCompatibleModelClient` 不 fallback ModelRequestConfig.getModelName()，ReActAgent 传 null 时序列化空串到 HTTP body → 所有 provider HTTP 400。

**设计**：
- extends Model，首调用 AtomicBoolean CAS 发 `__probe_tool__` 探针→无 tool_calls 则 throw（fail-fast），探针后零开销
- model name fallback：null/空 → `getModelConfig().getModelName()`
- `LLM_THINKING` env 多模式：`thinking-on/off`（DeepSeek），`qwen-on/off`（Qwen），通过 kwargs 透传
- Drop-in 替换：`new ToolCallingEnforcingModel(cliCfg, reqCfg)` 替代 `new Model(cliCfg, reqCfg)`

### ReactRailsAutoConfiguration
@AutoConfiguration，BeanPostProcessor 自动挂 rail，`@ConditionalOnMissingBean(CriteriaVerifier)` 默认 RuleBased，属性 reactrails.enabled/criteria/max-replan。

### 17 测试
mock 承重 12（mutation-RED）+ ToolCallingEnforcingModelTest 2（探针 catch + pass-through）+ spike 1（forceFinish 承重）+ 真 LLM e2e 3

## 真 LLM e2e 测试报告（跨 3 模型 × 双 thinking 模式）

### deepseek-v4-flash（直连，18/18 ✅，推荐首选）

| 模块 | 测试 | off | on |
|------|------|-----|-----|
| PEV | StraightThrough | 6.9s ✅ | 6.2s ✅ |
| PEV | DeviceFailureDegrades | 8.8s ✅ | 7.1s ✅ |
| PEV | MultiStepTools | 5.6s ✅ | 3.3s ✅ |
| PEV | PEVAgentRealLlm | 11.5s ✅ | 3.9s ✅ |
| PEV | AdversarialCatch | 6.7s ✅ | 27.0s ✅ |
| PEV | ClaimsAdjudication | **24.8s ✅** | **30.1s ✅** |
| react-rails | CriteriaVerification | 2.8s ✅ | 3.3s ✅ |
| react-rails | ReplanRail | **6.3s ✅** | **7.8s ✅** |
| react-rails | RootCauseRail | 2.6s ✅ | 4.9s ✅ |

### deepseek-v4-pro（18/18 ✅）
RootCause e2e 实测 LLM 真发 `tool_call(fetchData({"source":"orders"}))` → 工具异常 → Rail 降级触发（11.1s 端到端，bypass 被击碎）。

### Qwen3.5-35B OpenRouter（PEV 12/12 ✅, react-rails 5/6 ✅）
唯一主动调 `__replan__` 的模型（log 实证 tool_call）。ReplanRail thinking=on 仅 OpenRouter DeepInfra provider 不稳（直连 deepseek 全绿）。

## 测试心得（设计决策依据，6条）

1. **工具 bypass 已铲除**：删 `LlmBackedModelClient`（手写文本客户端静默丢 tools）。e2e 全走 `ToolCallingEnforcingModel` + `OpenAiCompatibleModelClient`（SDK 生产客户端）。旧模式例程从不触发 tool-execution 路径——测试恒绿但什么也没测。

2. **LLM 真调工具需要 inputParams schema**：`AlwaysFailTool` 缺 `.inputParams()` 时，GLM/deepseek 均倾向文本"叙述"而非真发 tool_call。补 schema 后 deepseek 正确发出 `tool_call(fetchData({"source":"orders"}))`。ToolCard description 描述不足——必须有完整 inputParams schema 才让 LLM 把工具当可调用单元。

3. **SDK OpenAiCompatibleModelClient 不 fallback model name**：ReActAgent 传 null → 序列化空串 → HTTP 400。ToolCallingEnforcingModel 承当了 `null/空→getConfig().getModelName()` 兜底。

4. **硬核承重在 mock，e2e 软观察诚实**：mock 硬断言 control flow（mutation-RED），e2e 基线断言（result 非 null）+ 条件断言（仅 LLM 触发路径时执行，诚实标注不确定）。

5. **跨模型验证必要**：同代码三个模型行为显著不同。deepseek-v4-flash 最快最稳（推荐主验证），Qwen 更主动调工具但 OpenRouter 路由不稳，GLM 倾向"叙述"而非真调。至少两个 provider 验证。

6. **GEPA 方法论收敛**：50 物种 CORRECTED GEPA 探索（157 agents/8.7M tokens）确认嫁接适用性——react-rails 嫁接 ReActAgent forceFinish gate（offset 700 实证），PEV 走自含 verify-loop。两种宿主形态，互补不互斥。

## 基座

target `common`（module/common-runtime-java 继任），已离线 merge `dcef1db`（零冲突）。合入后 2 个新增模块（pev + react-rails）。

所有提交 author = `Jun Yao <yaojun97@huawei.com>`，CLA 合规。
