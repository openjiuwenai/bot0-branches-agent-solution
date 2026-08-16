---
level: L1-HLD
module: agents/edpa-alpha
TAG:
  - logical-view
  - domain-model
  - architecture-fact
status: active
updated: 2026-08-15
dependency:
  - overview.md
  - process.md
  - development.md
  - ../features/FEAT-025-edpa-cognitive-loop.md
  - ../features/FEAT-026-edpa-capability-extensions.md
---

# EDPA-alpha L1 架构逻辑视图

## 1. 逻辑视图定位

`agents/edpa-alpha` 是 ReActAgent 的认知增强 overlay。逻辑视图描述 overlay 内部的领域对象、逻辑责任面、状态模型和依赖方向。

## 2. 领域对象模型

### 2.1 决策核心：EdpaKernel + sealed 类型

EdpaKernel 是 PEV kernel toReplanAction 的独立拷贝（仅 dispatch 半，不含 diagnoseRootCause；不依赖 PEV 模块；注：RootCause/ReplanAction 代码注释仍残留 @link PevKernel，待清理）。RootCause 和 ReplanAction 是 sealed 接口，与 PEV 同形。

```text
EdpaKernel.toReplanAction(RootCause, feedback, failedNodes) → ReplanAction

RootCause (sealed)            ReplanAction (sealed)
├── DeviceFailure(nodes)      ├── AcceptPartial(reason)
├── PerceptionUnreliable      ├── LocalReplan(failedNodes, feedback)
└── PlanOrAnswerError(nodes)  └── GlobalReplan(feedback)
```

IFF 契约与 PEV 一致（不可恢复→AcceptPartial，可恢复→Local/GlobalReplan）；完整映射见 PEV `logical.md` §4.3。

### 2.2 验证层：GroundTruthVerifier + DeterministicChecker

```text
GroundTruthVerifier implements CriteriaVerifier
├── checkers: List<DeterministicChecker>   注入的领域 checker
├── keywordFallback: RuleBasedCriteriaVerifier   不命中 checker 的兜底
└── verify(criteria, output, history) → List<Violation>
    ├── 逐条 criterion 匹配 DeterministicChecker.matches()
    │   ├── 匹配 → checker.check() 纯计算（零 LLM）
    │   └── 不匹配 → 收集到 keywordCriteria
    └── keywordCriteria 非空 → keywordFallback.verify()
```

```text
DeterministicChecker (SPI)
├── matches(criterion) → boolean     声明 own 哪些 criteria
└── check(criterion, output, history) → Violation | null   纯函数，零 LLM
```

示例：`ClaimDeterministicTools` 提供"理赔 85% 共担比例"等领域确定性工具（注意：它是 Tool 非 DeterministicChecker，需宿主自行实现 checker SPI 包装）。

### 2.3 收敛检测：ProactiveConvergenceRail

```text
ProactiveConvergenceRail extends AgentRail (priority=70)
├── verifier, successCriteria, stallWindow(默认2)
├── coverageCritical: 硬编码常量 0.34（EdpaProperties 暂无配置键）
├── stateKey: per-invocation 隔离（RailInvocationState）
└── per-invocation 状态: toolRoundCount / coverageHistory / wasStalled / triggerCount
```

afterModelCall 的 convergence 流程（coverage 追踪 → stall 检测 → edge-triggered steering）见 `process.md` §3。

### 2.4 探索：Explorer + ExploreBudget

```text
Explorer (SPI)
└── explore(topic, budget) → ExplorationResult(findings, candidateApproaches)

ExploreBudget
├── maxRounds
├── maxSubAgents
└── timeoutMillis

LlmExplorer implements Explorer
└── 用 LLM 调研 topic，返回发现 + 候选方案
```

两种集成模式：tool 模式（ExploreTool 注册为可调用 Tool）/ rail 模式（ExploreRail 钩 afterModelCall（探索注入））。

### 2.5 MCP 工具集成

```text
McpClient (SPI, AutoCloseable)

├── listTools() → List<McpTool>
├── callTool(name, args) → String
└── close() → 销毁 subprocess

StdioMcpClient implements McpClient
└── stdio JSON-RPC over subprocess（protocol 2024-11-05）

McpToolAdapter extends Tool
├── getCard() → 从 McpTool 元数据生成 ToolCard
└── invoke(args) → client.callTool(name, args) → 返回文本
```

### 2.6 SubAgent 派发

```text
SubAgentDispatcher
└── registerOnto(agent, toolName, description, SubAgentExecutor)
    └── 注册 SubAgentTool（extends Tool）到 agent

SubAgentExecutor (SPI)
└── execute(userInput, subGoal) → String

SubAgentTool extends Tool
└── invoke(args) → executor.execute(userInput, subGoal) → 子 agent 结果
```

### 2.7 配置：EdpaProperties

9 字段：`enabled` / `exploreMode` / `exploreRounds` / `maxSubagents` / `exploreTimeoutMillis` / `criteria` / `maxReplan` / `proactiveConvergenceEnabled` / `proactiveConvergenceStallWindow`。完整 YAML 示例见 L2 `Feat-Func-025` §2.2。

## 3. 逻辑责任面

```text
┌─ (根) EdpaRails ─────────── registerOnto 静态装配门面（单一真源，config-gated）
├─ autoconfigure ──────────── 基础设施 Bean（EdpaProperties/CriteriaVerifier/Explorer）+ 零命中探测
├─ verification ───────────── GroundTruthVerifier + DeterministicChecker + ProactiveConvergenceRail
├─ rail ──────────────────── ExploreRail + UserInputCaptureRail（注：DataFlowObserverRail 已于 MR !77 移除；EDPA 可观测性继承 react-rails RailTelemetry，无独立 OTel/DataFlow 层）
├─ explore ───────────────── Explorer/LlmExplorer + ExploreTool + ExploreBudget
├─ mcp ───────────────────── StdioMcpClient + McpToolAdapter + McpToolRegistrar
├─ subagent ──────────────── SubAgentDispatcher + SubAgentTool + SubAgentExecutor
├─ kernel ────────────────── EdpaKernel + RootCause + ReplanAction
└─ tool ──────────────────── ClaimDeterministicTools（示例领域工具）
```

## 4. 状态模型

### 4.1 覆盖率 + 停滞状态（per-invocation）

```text
tool round 1 → coverage=0.0
tool round 2 → coverage=0.2
tool round 3 → coverage=0.2 (delta≈0) ─┐
tool round 4 → coverage=0.2 (delta≈0) ─┘ stallWindow=2 → isFlatlined=true
           coverage < coverageCritical(0.34) → STALL DETECTED
           edge-triggered: pushSteering(convergenceFeedback)
tool round 5 → coverage=0.6 (steering 生效，agent 调整方向)
```

### 4.2 DeterministicChecker 分派状态

```text
criterion "理赔金额符合85%共担比例"
  → 宿主自定义 Checker（如 ClaimDeductibleChecker）.matches() = true
  → check() 纯计算 → Violation 或 null

criterion "回答包含理赔结论"
  → 无 checker 匹配
  → keywordFallback.verify() → 文本匹配
```

### 4.3 状态归属

| 状态对象 | 归属 | 职责 |
|---|---|---|
| InvocationState（coverage/toolRound/wasStalled） | ProactiveConvergenceRail per-invocation（RailInvocationState 隔离） | 收敛检测的滑动状态。 |
| MCP subprocess | StdioMcpClient（宿主管理生命周期） | MCP 工具连接。 |
| EdpaProperties | Spring 配置 | 总开关 + 各参数。 |
| ReAct agent 状态 | agent-core-java（EDPA 不拥有） | EDPA 只 overlay，不改 agent 内部状态。 |

## 5. 逻辑依赖方向

```text
autoconfigure ──▶ verification / rail / explore / mcp / subagent / kernel
verification  ──▶ kernel（dispatch）+ react-rails（CriteriaVerifier/Violation）
rail          ──▶ kernel + explore + react-rails
mcp           ──▶ agent-core-java（Tool 适配）
subagent      ──▶ agent-core-java（Tool 适配）
kernel        ──▶ （零框架耦合，toReplanAction 独立拷贝）
```

kernel 不依赖 PEV 模块、不依赖 agent-core-java agent 抽象——是 PEV kernel toReplanAction 的独立拷贝（仅 dispatch 半，不含 diagnoseRootCause；同 IFF，不同包）。

**结构缺口（honest，deferred）**：EDPA 当前 0 个 `package-info.java` + 0 个 ArchUnit 测试（同 PEV / agent-core-ext-react-rails 已补的 package-info + ArchUnit，EDPA 未补，deferred）。

## 6. 与其他视图的衔接

- 运行时 rail 钩入 + convergence 流程 + MCP 调用 + SubAgent 派发：`process.md`。
- module/包/依赖红线/SPI/测试：`development.md`。
- 部署（Spring Boot + MCP subprocess）：`physical.md`。
- 技术场景（convergence fire / 确定性验证 / MCP / SubAgent / 探索）：`scenarios.md`。
- 接入契约 + 详细设计：L2 `Feat-Func-025` + `Feat-Func-026`。
