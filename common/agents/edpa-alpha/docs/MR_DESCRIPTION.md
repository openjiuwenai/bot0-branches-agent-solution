# EDPA-alpha：认知增强 Agent（Explore→Decision→Plan→Action）

## 概述

EDPA-alpha 在 agent-core-java 的 DeepAgent 引擎之上，增加**认知闭环**（Plan→Execute→Verify→Diagnose→Dispatch→Replan/Degrade），复用 react-rails 认知 rail + 自有 sealed dispatch kernel，为开环 ReAct 循环补上外部验证、根因诊断和自适应重规划能力。

## 设计思路

agent-core-java 0.1.13 的 DeepAgent/ReActAgent 提供 Plan→Execute→Emit **开环循环**（LLM 自我反思），但缺少三个关键能力：

1. **外部验证**——output 是否满足 success criteria，不靠 LLM 自评
2. **根因诊断**——verify 失败是感知出错 / 计划错误 / 设备故障
3. **自适应重规划**——根据根因选 LocalReplan（局部重做）/ GlobalReplan（全局重规划）/ AcceptPartial（诚实降级）

EDPA-alpha 补这三层：
- 4 条认知 rail（CriteriaReplanBridgeRail / ReplanRail / RootCauseRail / ProactiveConvergenceRail）挂到 DeepAgent 内部 ReActAgent（零改动，同基类 AgentRail）
- 自有 sealed dispatch kernel（RootCause 3 态 → ReplanAction 3 态，纯函数零 LLM）
- 确定性兜底（GroundTruthVerifier：规则/真值优先于 LLM judge）

## 架构

```
agent-core-java 0.1.13（DeepAgent + ReActAgent + AgentRail + AgentCallbackContext）
       ↑
react-rails（公共认知 rail + 状态隔离）
  ├── ReplanRail / CriteriaReplanBridgeRail / RootCauseRail（generic rail）
  ├── RailInvocationState（per-invocation 状态隔离）
  └── CriteriaVerifier / RuleBasedCriteriaVerifier（接口 + keyword 实现）
       ↑
EDPA-alpha（与 pev 平级，common/agents/ 下，互不依赖）
  ├── kernel: RootCause / ReplanAction / EdpaKernel.toReplanAction（sealed dispatch 纯函数）
  ├── verification: GroundTruthVerifier（DeterministicChecker SPI 确定性优先 → keyword fallback）
  ├── verification: ProactiveConvergenceRail（主动收敛检测，flatline → replan）
  ├── tool: ClaimDeterministicTools（calcDeductible 85% 共担 + authorizePayment 险种阈值）
  ├── observability: EDPA rails fire react-rails RailTelemetry SteeringEvents（EXPLORE_FINDINGS / CONVERGENCE_STALL，继承 react-rails bus）
  ├── explore: ExploreRail / ExploreTool（tool-driven 探索，LLM 主动调 explore 工具）
  ├── mcp: StdioMcpClient（薄 JSON-RPC 客户端，绕过 SDK MCP client bug）
  ├── subagent: SubAgentDispatcher（orchestrator + specialist 共享 MCP 工具池）
  └── e2e: MCP 投资研究 + 认知闭环 + SubAgent + 6 模型矩阵
```

**关键接线**：`HarnessFactory.createDeepAgent(config)` → `deep.getAgent().registerRail(cognitive rails)` → `deep.getAgent().invoke(task, null)` 跑 railedModelCall 循环 + rail fire。

## 核心能力

### 1. 认知闭环

DeepAgent 的 ReActAgent 循环里，认知 rail 在 afterModelCall / afterToolCall 钩子触发：

| Rail | 钩子 | 行为 |
|---|---|---|
| CriteriaReplanBridgeRail | afterModelCall | final answer 后 verify criteria → fail 则 pushSteering replan |
| ReplanRail | afterModelCall | `__replan__` 工具调用计数 → 超限 forceFinish(degraded) |
| RootCauseRail | onToolException + afterModelCall | 工具异常 → DeviceFailure → forceFinish(degraded)（不重试坏设备）|
| ProactiveConvergenceRail | afterModelCall | 滑窗 flatline 检测（连续 N 轮 coverage 停滞）→ 主动 replan |

verify→replan 路径用 sealed dispatch（纯函数，零 LLM）：
- **DeviceFailure / PerceptionUnreliable** → AcceptPartial（诚实降级，不重试）
- **PlanOrAnswerError** → LocalReplan（≤2 failed nodes）/ GlobalReplan（>2 或 empty）

### 2. 确定性兜底（GroundTruthVerifier）

规则/真值优先于 LLM judge。三级分层验证：
- **DeterministicChecker SPI**：注入确定性算子（金额/阈值/规则计算，零 LLM，compute don't guess）
- **RuleBasedCriteriaVerifier**：keyword 覆盖匹配（deterministic coverage proxy）
- LLM judge（future，未实现）

### 3. 大气功能

- **MCP 薄客户端**（StdioMcpClient）：JSON-RPC over stdio 绕过 SDK 0.1.12 MCP client 三个 bug（StdioClient hang / StreamableHTTP drop session / SSE 0 tools）。真 SEC EDGAR e2e 7/7 PASS。
- **SubAgent 派发**（SubAgentDispatcher）：orchestrator + specialist 共享全局 MCP 工具池，子 agent 复用 host adapter 取真数据。
- **可观测性**：EDPA rails fire react-rails RailTelemetry SteeringEvents（ExploreRail "EXPLORE_FINDINGS" / ProactiveConvergenceRail "CONVERGENCE_STALL"），继承 react-rails bus，不自带 OTel 层（MR !77 移除了旧的 DataFlowObserverRail/OTel —— react-rails 结论甲实证 ext 层 OTel-as-source 错层）。

## 测试验证

| 维度 | 结果 |
|---|---|
| 单元测试 | **129 测试全绿**（52 文件：27 main + 25 test）|
| 认知闭环 e2e | 6 模型 × thinking on/off = 12 配置，**12/12 BUILD SUCCESS** |
| MCP 投资研究 e2e | SEC EDGAR 真数据 + MS 7 段报告，12 配置 **10/12 PASS**（9× 7/7 + 1× 6/7）|
| SubAgent e2e | **2/2 PASS**（dispatch chain host→子 agent→fan-back）|
| 对抗审查 | 4 lens 审查 + 修复（Span try/finally + Javadoc 诚实降级 + null 防御 + payload 泛化）|

MCP e2e 亮点：deepseek-v4-flash + thinking on 调了 9 个不同 MCP 工具（含跨期对比 + XBRL 概念发现），产出 6284 字完整 AAPL 投研报告（真 SEC EDGAR XBRL 数据：营收 $416B / 净利润 $112B / EPS $7.46），Criteria verify PASS + 7/7 段结构。

## 优点

1. **认知闭环补全**——DeepAgent/ReActAgent 开环 → 闭环（外部 verify + 根因诊断 + 自适应 replan），不靠 LLM 自觉，代码兜底
2. **确定性优先**——规则/真值 > LLM judge（GroundTruthVerifier DeterministicChecker SPI），涉数值/逻辑/合规的验证绝不 LLM-as-judge
3. **零改动复用**——认知 rail 同基类 AgentRail，`deep.getAgent().registerRail(...)` 零改动挂 DeepAgent
4. **MCP 真数据验证**——SEC EDGAR 真实 XBRL 数据（AAPL 营收 $416B 等），不是 mock；绕过 SDK MCP client bug
5. **OpenTelemetry trace span 架构**——一开始设计（不先简单后补），配置降档，未来 export Jaeger
6. **跨模型稳健**——6 模型 × thinking 矩阵验证（认知 12/12 + MCP 10/12）
7. **与 PEV 平级互不依赖**——自有 sealed kernel（RootCause/ReplanAction/EdpaKernel），不依赖 pev 模块

## 缺点 / 诚实边界

1. **GroundTruthVerifier 默认无 DeterministicChecker**——SPI 占位，需注入业务算子才确定性优先；当前 EdpaAutoConfiguration 注入空 GroundTruthVerifier()，生产 100% 走 keyword fallback
2. **ClaimDeterministicTools 未接线**——示例确定性 Tool（保险理赔场景），需显式 `registerOnto(agent)` 注册；EdpaAutoConfiguration 默认不注册
3. **状态隔离代价=可观测性下降**——RailInvocationState per-invocation 隔离后，invoke 返回 ctx 不可达，rail 操作状态（replanCount/triggerCount）invoke 后读不到（走 result Map 透传）
4. **glm-4.7 + thinking ON content-empty**——模型特性（bigmodel + openrouter 两 endpoint 一致复现），非 EDPA bug
5. **DSPY/GEPA 离线工具 defer**——Java 生态无成熟 DSPY 库，compile-time prompt 优化 + rail 空间探索 defer 到后续

## 模块依赖

```
edpa-alpha → agent-core-java 0.1.13（DeepAgent / ReActAgent / AgentRail / AgentCallbackContext）
edpa-alpha → react-rails（ReplanRail / CriteriaReplanBridgeRail / RootCauseRail / RailInvocationState / CriteriaVerifier / RailTelemetry / RailEvent）
```

**不依赖 pev**（自有 kernel：RootCause / ReplanAction / EdpaKernel.toReplanAction）。EDPA-alpha 和 PEV 完全平级，互不依赖。

## 文件规模

- 52 文件 ~8,000 行（27 main ~2,850 + 25 test ~5,150）
- logs/ + target/ 已 gitignore
- 最大文件：ExploreRailTest 497 行 / EdpaAutoConfigurationTest 427 行 / e2e 测试 ~300-370 行
