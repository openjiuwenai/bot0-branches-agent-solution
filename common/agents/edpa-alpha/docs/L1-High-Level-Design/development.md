---
level: L1-HLD
module: agents/edpa-alpha
TAG: [development-view, module-structure, dependency, architecture-fact]
status: active
updated: 2026-08-08
dependency: [overview.md, logical.md]
---

# EDPA-alpha L1 架构开发视图

## 1. 开发视图定位

开发视图描述 `agents/edpa-alpha` 的 Maven module 结构、包划分、依赖红线和 SPI 组织。

## 2. Module 结构

```text
common/agents/edpa-alpha/
├── pom.xml          依赖 agent-core-java + react-rails + spring-boot-autoconfigure（OTel 依赖已移除，MR !77）
└── src/main/java/com/openjiuwen/agents/edpa/
    ├── autoconfigure/   Spring @AutoConfiguration + EdpaProperties
    ├── kernel/          EdpaKernel + RootCause + ReplanAction
    ├── verification/    GroundTruthVerifier + DeterministicChecker + ProactiveConvergenceRail
    ├── rail/            ExploreRail + UserInputCaptureRail（DataFlowObserverRail 已移除，MR !77）
    ├── explore/         Explorer/LlmExplorer + ExploreTool + ExploreBudget
    ├── mcp/             StdioMcpClient + McpToolAdapter + McpToolRegistrar
    ├── subagent/        SubAgentDispatcher + SubAgentTool + SubAgentExecutor
    ├── tool/            ClaimDeterministicTools（示例领域工具）
    └── util/            LlmResponseExtractor
```

## 3. 依赖红线（CI 强制）

### 3.1 禁止的依赖

- **PEV 模块**（`com.openjiuwen.agents.pev.*`）：EDPA 和 PEV 是 peer agent，不得互相依赖。kernel 是独立拷贝。
- 任何特定 LLM SDK（Explorer/Verifier 经 SPI/Function 接入 LLM）。
- broker、数据库厂商类型。

### 3.2 允许的依赖

| 依赖 | 用途 |
|---|---|
| `agent-core-java` | ReActAgent / BaseAgent / rail.* / AgentCard / Tool |
| `react-rails` | CriteriaVerifier / Violation / ReplanRail 等认知 rail 基座 |
| `spring-boot-autoconfigure` | EdpaAutoConfiguration @AutoConfiguration + BeanPostProcessor |

### 3.3 公共 SPI 红线

公共 SPI（DeterministicChecker / Explorer / McpClient / SubAgentExecutor）只出现 `java.*` 与 EDPA 自有类型。

## 4. SPI 与扩展点

| SPI | 位置 | 实现方 | 承载 |
|---|---|---|---|
| `DeterministicChecker` | verification | 宿主实现 DeterministicChecker SPI（ClaimDeterministicTools 是示例 Tool 非 Checker，需自行包装） | 领域特定纯计算验证 |
| `Explorer` | explore | 宿主（默认 LlmExplorer） | 调研 → findings |
| `McpClient` | mcp | 宿主（默认 StdioMcpClient） | MCP 工具服务器连接 |
| `SubAgentExecutor` | subagent | 宿主 | 子 agent 执行后端 |
| `CriteriaVerifier` | verification（react-rails） | 宿主（默认 GroundTruthVerifier） | criteria 验证 |

## 5. 测试守卫

| 测试组 | 承重点 |
|---|---|
| convergence 测试 | stall 检测 edge-triggered / coverage 追踪 / steering 内容 |
| 确定性验证测试 | checker 匹配 + 纯计算 + keyword fallback |
| MCP 测试 | tool 适配 + callTool + 错误处理 |
| SubAgent 测试 | 派发 + 执行 + 结果返回 |
| 真 LLM e2e | convergence fire + 探索 + 跨模型 |

## 6. 构建与质量守卫

- Java 17（sealed types、pattern matching）。
- Spring Boot autoconfig（`META-INF/spring/`）。
- CodeCheck + formatter + checkstyle（镜像父 pom）。

## 7. 演进与 artifact 拆分

- 当前：单 module 内按包隔离。
- kernel 拷贝同步：EdpaKernel 与 PevKernel 是同逻辑独立拷贝；修改需同步两边。
- 未来 MCP SSE/HTTP 传输：作为 McpClient 新实现，不改 SPI。

## 8. 与其他视图的衔接

- 领域对象/责任面/状态模型：`logical.md`。
- ReAct 循环/rail 钩入/convergence 流程：`process.md`。
- 部署/MCP subprocess/可观测性（RailTelemetry；OTel 层已移除 MR !77）：`physical.md`。
- 技术场景：`scenarios.md`。
- 详细设计：L2 `Feat-Func-025` + `Feat-Func-026`。
