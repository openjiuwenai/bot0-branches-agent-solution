---
level: L1-HLD
module: agents/pev
TAG:
  - development-view
  - module-structure
  - dependency
  - architecture-fact
status: active
updated: 2026-08-06
dependency:
  - overview.md
  - logical.md
---

# PEV L1 架构开发视图

## 1. 开发视图定位

开发视图描述 `agents/pev` 的 Maven module 结构、包划分、依赖边界、SPI 组织和测试守卫。它回答"代码怎么组织、依赖什么不依赖什么、扩展点在哪、测试怎么钉"。

## 2. Module 结构

`common/agents/pev` 是 Maven module（父 `common/agents`，version 0.1.0）。它是**自包含 agent-service-app**，不是 ext adapter。

```text
common/agents/pev/
├── pom.xml                     依赖 agent-core-java:0.1.13（managed）+ test（junit/assertj）
└── src/
    ├── main/java/com/openjiuwen/agents/pev/
    │   ├── agent/              闭环主体 + 阶段 SPI
    │   ├── kernel/             决策核心（sealed types + 纯函数）
    │   ├── observability/      kernel-native trace
    │   └── rail/               示例认知 rail（Beta）
    └── test/java/...           控制流承重测试 + 真 LLM e2e
```

## 3. 包划分与职责

| 包 | 承重类 | 职责 |
|---|---|---|
| `agent` | `PEVAgent`, `PevComponents` | 闭环主体（invoke/runVerifyLoop/dispatch/handleLocalReplan/emitTrace）+ 三阶段 SPI（Planner/Executor/Verifier）+ Plan/PlanNode + PevConfig |
| `kernel` | `PevKernel`, `NodeResult`, `RootCause`, `ReplanAction` | 决策核心：两纯函数（diagnoseRootCause/toReplanAction）+ VerifyResult + 三个 sealed 类型。零 LLM、零框架耦合。 |
| `observability` | `PevTrace`, `PevTraceSink` | 闭环终态投影（sealed Phase 序列 + TerminalReason）+ 函数式 sink 接口 |
| `rail` | `CriteriaVerificationRail`, `RootCauseRail` | 示例横切认知 rail：afterInvoke 成功关键词校验 / afterToolCall 累积 DeviceFailure 遥测 |

包间依赖单向：`agent` → `kernel` → （仅 agent-core-java）；`observability` → `kernel`（Phase 包装 kernel 类型；Planned 用 NodeSnapshot 本地投影，零 agent import）；`rail` → `kernel`（读 NodeResult）。ArchUnit 强制（`PackageDependencyArchTest` 4 条规则 CI 守护）。`kernel` 不反向依赖 `agent`——这是同级模式可独立复用 kernel 的保证。

## 4. 依赖红线（CI 强制）

### 4.1 禁止的依赖

`agents/pev` 是自包含 agent-service-app，**任何包都禁止 import** 以下类型：

- `agent-runtime-java` 的内部实现（runtime 包装 PEV，PEV 不反向依赖 runtime）。
- 任何特定 LLM SDK / 模型 client（三阶段是 SPI，LLM 接入由宿主注入）。
- Spring 注解与类型（PEV 不提供 auto-config；接线由集成方/runtime 完成）。
- engine adapter、broker、数据库厂商类型。

**kernel 包更强**：禁止依赖 `agent-core-java` 的 agent 抽象（`BaseAgent` 等）——kernel 只用 sealed types + 纯函数，可被不引入 agent 框架的同级模式独立复用。这是 kernel 复用承诺的承重保证。

### 4.2 公共 API/SPI 的第三方类型红线

公共 SPI（`PevComponents.*`、`PevTraceSink`）与 sealed 类型**只允许出现 `java.*` 与 PEV 自有类型**。禁止在公开签名泄漏：Spring、Reactor `Mono/Flux`、Jackson `JsonNode`、LLM SDK、HTTP/broker 厂商类型。

并发用 `Iterator`/`CompletableFuture`（PEV 当前同步，无并发类型）；rail 经 `AgentCallbackContext`（agent-core-java 提供）；sink 是 `@FunctionalInterface`。宿主实现可在内部用任意框架，但必须在 SPI 边界转换。

### 4.3 允许的依赖

| 需求 | 允许方式 |
|---|---|
| agent 框架基座 | `agent-core-java:0.1.13`（`BaseAgent`/`Session`/`rail.*`/`AgentCard`） |
| （无外部测试数据依赖） | 测试用内联 mock，不引 jackson/poi |
| 同级模式复用 kernel | kernel 包零框架耦合，可直接 import |

### 4.4 依赖守卫

- ArchUnit 规则已落地（`PackageDependencyArchTest` 4 条 CI 强制）：kernel 不依赖 agent/observability、observability 不依赖 agent（xuefanfan gap 守卫）、rail 不依赖 agent。
- 依赖方向单向：runtime/sibling 调用 PEV，PEV 不反向依赖它们。
- CodeCheck / 承重测试见 §7。

## 5. SPI 与扩展点

| SPI | 位置 | 实现方 | 承载 |
|---|---|---|---|
| `PevComponents.Planner` | agent | 宿主 | NL 目标 → Plan |
| `PevComponents.Executor` | agent | 宿主 | PlanNode → NodeResult |
| `PevComponents.Verifier` | agent | 宿主 | (userInput, completed) → VerifyResult |
| `PevTraceSink` | observability（函数式） | 宿主（默认 noop） | trace 消费（logger/OTel/Micrometer） |
| `AgentRail`（继承自 agent-core） | rail 示例 | 宿主/同级模式 | 相位观测/认知扩展 |

所有扩展点经构造器注入（显式 opt-in），无强制安装入口——避免静默安装 footgun。

## 6. 测试守卫

| 测试组 | 承重点 | 范式 |
|---|---|---|
| `PEVAgentControlFlowTest` | dispatch 各分支（PASSED/LocalReplan/GlobalReplan/AcceptPartial/MAX_RETRIES_EXCEEDED/INCONCLUSIVE）+ feedback 注入 + verifier 容错 + maxRetries 截断 | mock 三阶段；剥→RED IFF 范式（禁"有调用/非空"弱断言） |
| kernel 纯函数单测 | diagnose 四分支优先级 + toReplanAction 四条 IFF 映射 | 纯函数，零 LLM |
| trace 测试 | 四终态 terminalReason + phase 序列（PASSED 无 Diagnosed/Dispatched）+ 非 null | — |
| sink 隔离测试 | 抛错 sink 不击穿 invoke | — |
| 真 LLM e2e（软观察） | Planner/Executor/Verifier 接真 LLM 的数据通道 | mock 证控制流，真 LLM 证数据通道 |

承重测试是承重契约的活文档——Phase 3 曾靠它暴露 subCtx NPE 的 production bug。

## 7. 构建与质量守卫

- Java 17（sealed types、pattern matching）。
- formatter-maven-plugin + checkstyle（镜像父 pom 规则）。
- CodeCheck（华为扩展规则）：G.DCL/CTL/TYP/LOG/ERR/FMT 等——PEV 维护零违反（FutureTask 桥既是隔离机制也避 G.ERR.02 catch RuntimeException）。

## 8. 演进与 artifact 拆分策略

- 当前：单 Maven module（`common/agents/pev`）内按包隔离（agent/kernel/observability/rail），以 CodeCheck + 承重测试 + ArchUnit（CI 强制）维持边界。
- kernel 复用：kernel 包（`PevKernel` + 三 sealed 类型）零框架耦合，同级模式（EDPA）可直接 import 复用；未来若复用面扩大，可将 kernel 拆为独立 artifact，不污染 agent 主干。
- 真流式：需 Executor 暴露 `Flux`，作为新增能力，不改当前 sealed dispatch / SPI 公开形状。
- 示例 rail（CriteriaVerificationRail/RootCauseRail）是缝合点示范；后续认知 rail 作为新增责任面，不污染闭环主干。

## 9. 与其他视图的衔接

- 责任面语义、领域对象、kernel 决策模型：`logical.md`。
- 运行时相位流、dispatch、verifier 容错、trace emit、并发模型：`process.md`。
- 部署形态、网络/持久化边界、sink 消费拓扑：`physical.md`。
- 技术场景：`scenarios.md` TS-01 ～ TS-09。
- 接入契约（invoke/SPI/rail/sink 形态）、代码结构详图、对 runtime 要求：L2 `Feat-Func-023-pev-selfheal-loop.md`。
