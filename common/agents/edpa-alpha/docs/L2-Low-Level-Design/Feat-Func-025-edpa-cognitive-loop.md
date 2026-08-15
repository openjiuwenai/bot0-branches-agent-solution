---
level: L2-LLD
module: agents/edpa-alpha
feature_type: functional
feature_id: FEAT-025
status: active
authority: authoritative
updated: 2026-08-08
dependency:
  - ../features/FEAT-025-edpa-cognitive-loop.md
  - README.md
  - ../L1-High-Level-Design/overview.md
  - ../L1-High-Level-Design/logical.md
  - ../L1-High-Level-Design/process.md
---

# EDPA 认知增强闭环 — 设计文档（FEAT-025 L2）

> 目标模块：`agents/edpa-alpha`（ReActAgent 认知 overlay）
> 事实来源：`features/FEAT-025-edpa-cognitive-loop.md`
> 参照代码：`common/agents/edpa-alpha/src/main/java/com/openjiuwen/agents/edpa/`

## 1. 概述

### 1.1 特性定位
EDPA 在 ReActAgent reason+act 循环上叠加主动收敛、确定性验证和探索——不改 ReAct 控制流本体。

### 1.2 设计原则
1. **overlay 不改本体** — rail 经 registerRail 挂载，ReAct 控制流不变。
2. **规则 > LLM judge** — 数值/逻辑/合规用 DeterministicChecker 纯计算。
3. **edge-triggered convergence** — 停滞入口 fire 一次，不每轮干扰。
4. **kernel 独立拷贝** — EdpaKernel 不依赖 PEV 模块（PEV kernel `toReplanAction` 的独立拷贝，仅 dispatch 半，不含 `diagnoseRootCause`；同逻辑、不同包）。

### 1.3 子特性全景
| 子特性 | 职责 | 关键类 |
|---|---|---|
| 主动收敛 | coverage 追踪 + stall 检测 + steering | ProactiveConvergenceRail |
| 确定性验证 | checker 纯计算 + keyword 兜底 | GroundTruthVerifier + DeterministicChecker |
| 探索 | LLM 调研 → findings | ExploreRail / ExploreTool + Explorer |
| 数据流观测 | —（已移除） | DataFlowObserverRail（MR !77 移除：ext 层 OTel-as-source 错层；EDPA 无自带 OTel/DataFlow 层，deferred） |
| kernel | RootCause→ReplanAction | EdpaKernel |

## 2. 特性规格

### 2.1 接入契约

```java
// Spring Boot 配置（基础设施 Bean，不装配 agent —— 对齐官方 agent-service-app 模式）
@AutoConfiguration
public class EdpaAutoConfiguration {
    @Bean public EdpaProperties edpaProperties() { ... }
    @Bean public CriteriaVerifier edpaCriteriaVerifier() { ... }  // 默认 RuleBasedCriteriaVerifier
    @Bean public Explorer edpaExplorer(properties, modelProvider) { ... }  // LlmExplorer
}

// 装配显式化：宿主在 @Bean AgentHandler 里调用（agent 不外露成 Spring bean）
@Bean
AgentHandler myAgentHandler(LlmConfigResolver r, EdpaProperties props,
        CriteriaVerifier verifier, Explorer explorer) {
    ReActAgent agent = ExampleReActAgentFactory.build("my-agent", ..., llm);
    EdpaRails.registerOnto(agent, props, verifier, explorer);  // 单一装配真源
    return new JiuwenCoreAgentHandler(agent);
}
```

```java
// DeterministicChecker SPI（宿主实现）
public interface DeterministicChecker {
    boolean matches(String criterion);
    Violation check(String criterion, String output, String decisionHistory);
}
```

### 2.2 配置

```yaml
edpa:
  enabled: true                    # 总开关（默认 false）
  explore-mode: tool               # tool | rail
  explore-rounds: 2
  max-subagents: 3
  explore-timeout-millis: 60000
  criteria:                        # success criteria 列表
    - "理赔金额符合85%共担比例"
    - "回答包含理赔结论"
  max-replan: 2
  proactive-convergence-enabled: true
  proactive-convergence-stall-window: 2
```

## 3. 核心实现

### 3.1 EdpaAutoConfiguration wiring

`EdpaRails.registerOnto` 显式装配（单一真源，config-gated），装配顺序：
1. tool 模式 → UserInputCaptureRail + ExploreToolRegistrar。
2. rail 模式 → ExploreRail。
3. criteria 非空 → CriteriaReplanBridgeRail +（可选）ProactiveConvergenceRail。
4. maxReplan ≥ 0 → ReplanRail + ReplanTool。
5. 始终 → RootCauseRail（DeviceFailure 降级门）。

### 3.2 ProactiveConvergenceRail convergence 逻辑

```java
afterModelCall(ctx):
  toolResults = extractAccumulatedToolResults(messages);
  violations = verifier.verify(criteria, "", toolResults);
  coverage = 1 - violations.size() / criteria.size();
  coverageHistory.add(coverage);  // 滑动窗口
  stalled = isFlatlined(history) && coverage < 0.34;
  if (stalled && !wasStalled) {  // edge-triggered
      action = EdpaKernel.toReplanAction(PlanOrAnswerError, feedback, Set.of());
      ctx.pushSteering(action.feedback);  // GlobalReplan.feedback
  }
  wasStalled = stalled;
```

### 3.3 GroundTruthVerifier dispatch

```java
verify(criteria, output, history):
  for criterion in criteria:
      checker = checkers.find(c -> c.matches(criterion));
      if (checker != null) violation = checker.check(criterion, output, history);  // 纯计算
      else keywordCriteria.add(criterion);  // fall through
  if (keywordCriteria.notEmpty) violations += keywordFallback.verify(keywordCriteria);
```

## 4. 代码结构

见 `development.md` §2（autoconfigure/kernel/verification/rail/explore 包）。

## 5. 运行流程

convergence / 验证 / 探索流程见 `process.md` §3-6。

## 6. 配置与使用

配置见 §2.2。下面只讲 DeterministicChecker 注入（⚠ autoconfig 不自动发现）。


### DeterministicChecker 注入（⚠ 不能用 @Bean 自动发现）

当前 autoconfig 用**空 checker** 构造 GroundTruthVerifier，不会收集 `@Bean DeterministicChecker`。宿主必须**覆盖 CriteriaVerifier bean**：

```java
@Bean
public CriteriaVerifier edpaCriteriaVerifier() {
    return new GroundTruthVerifier(List.of(
        new ClaimDeductibleChecker()   // 你的 DeterministicChecker 实现
    ));
}
```

这会覆盖 autoconfig 的默认 `edpaCriteriaVerifier()` bean，使 GroundTruthVerifier 含你的 checker。

### DeterministicChecker 完整可编译示例

```java
import com.openjiuwen.agents.edpa.verification.DeterministicChecker;
import com.openjiuwen.agents.reactrails.types.Violation;
import java.math.BigDecimal;

public class ClaimDeductibleChecker implements DeterministicChecker {

    @Override
    public boolean matches(String criterion) {
        return criterion != null && criterion.contains("85%共担");
    }

    @Override
    public Violation check(String criterion, String output, String decisionHistory) {
        BigDecimal claimAmount = extractAmount(output);
        BigDecimal expected = claimAmount.multiply(new BigDecimal("0.85"));
        if (matchesReported(output, expected)) {
            return null;  // 通过
        }
        return new Violation(criterion, "应赔 " + expected + "（85%共担后），不符");
    }
    // ... extractAmount / matchesReported 是你的领域方法
}
```

## 7. 当前限制

| 限制 | 演进路径 |
|---|---|
| convergence 只追踪工具结果覆盖率 | 可扩展为追踪 LLM 输出质量 |
| DeterministicChecker 只支持文本 criterion | 可扩展结构化 criterion |
| 探索只 LLM 调研 | 可接入离线 GEPA/DSPy（OUT） |

## 8. 对 runtime / 集成方 要求

| 编号 | 要求 |
|---|---|
| R-1 | 宿主必须在 `@Bean AgentHandler` 方法里显式调用 `EdpaRails.registerOnto(agent, props, verifier, explorer)`（官方 demo pattern；agent 无需外露成 Spring bean）。autoconfig 会在启用但零命中时 WARN。 |
| R-2 | ⚠ 当前 autoconfig 不自动发现 DeterministicChecker（用空 checker 构造 GroundTruthVerifier），需宿主手动 `new GroundTruthVerifier(List.of(myChecker))` 注入；宿主声明 own 哪些 criteria。 |
| R-3 | DataFlowObserverRail 已于 MR !77 移除；EDPA 当前不产出 OTel span，可观测性继承 agent-core-ext-react-rails 的 RailTelemetry（SteeringEvent "EXPLORE_FINDINGS" / "CONVERGENCE_STALL"）。 |
| R-4 | criteria 列表必须非空才启用 convergence（空列表 = 不检测）。 |

## 9. 一致性

本文 §2-3 与 FEAT-025 事实要求逐条对应；PEV kernel IFF 映射见 PEV `logical.md` §4.3（EdpaKernel 是 PEV kernel `toReplanAction` 的独立拷贝，仅 dispatch 半，不含 `diagnoseRootCause`）。
