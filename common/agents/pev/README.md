# pev — PEV agent 开发指南

> PEV = **P**lan → **E**xecute → **V**erify → **D**iagnose → **D**ispatch。
> 一个让 agent 学会"先诊断为什么失败、再决定做什么"的执行-验证-自愈闭环，
> 建在 agent-core-java 的 `BaseAgent` 上。

本指南讲**怎么用 PEV 开发你自己的 agent**——从 30 秒上手到 5 个典型应用场景。

---

## 目录

- [30 秒上手](#30-秒上手)
- [典型应用](#典型应用)
  - [A. 纯 LLM 推理（无工具）](#a-纯-llm-推理无工具)
  - [B. 单工具调用](#b-单工具调用)
  - [C. 多步骤工具链](#c-多步骤工具链)
  - [D. 业务场景：理赔复审](#d-业务场景理赔复审)
  - [E. 设备故障降级](#e-设备故障降级)
- [组件 SPI 详解](#组件-spi-详解)
- [认知 Rail（横切）](#认知-rail横切)
- [测试约定](#测试约定)
- [模块结构 + 依赖](#模块结构--依赖)

---

## 30 秒上手

最小 PEV agent = 一个 `PEVAgent` + 三个 lambda（Planner / Executor / Verifier）：

```java
import com.openjiuwen.agents.pev.agent.PEVAgent;
import com.openjiuwen.agents.pev.agent.PevComponents;
import com.openjiuwen.agents.pev.kernel.NodeResult;
import com.openjiuwen.agents.pev.kernel.PevKernel;
import com.openjiuwen.core.singleagent.schema.AgentCard;

import java.util.List;
import java.util.Map;
import java.util.Set;

PEVAgent agent = new PEVAgent(
        AgentCard.builder().build(),

        // Plan：把任务拆成节点（这里单步）
        (PevComponents.Planner) in -> new PevComponents.Plan(in,
                List.of(new PevComponents.PlanNode("answer", in))),

        // Execute：执行节点（这里直接给固定结果）
        (PevComponents.Executor) nodes -> Map.of("answer", new NodeResult.Success("42")),

        // Verify：判定结果（这里固定 PASS）
        (PevComponents.Verifier) (in, r) -> new PevKernel.VerifyResult(true, Set.of(), "ok")
);

Object output = agent.invoke("生命的意义是什么？", null);
// output = "answer: 42"
```

**核心心智模型**：你只需实现三个阶段（Plan/Execute/Verify），PEVAgent 自动跑 `Plan → Execute → Verify → Diagnose → Dispatch` 闭环——verify 过了就出结果；没过就诊断根因（DeviceFailure/PerceptionUnreliable/PlanOrAnswerError）→ dispatch（重试/重规划/降级）。

---

## 典型应用

下面 5 个场景覆盖从简单到完整。每个都给代码骨架 + "它证了什么"。完整可跑版本见 `src/test/java/.../pev/e2e/`。

### A. 纯 LLM 推理（无工具）

**场景**：纯知识性任务，不需要工具，LLM 一步推理出答案。

```java
// Planner：单节点，描述 = 任务本身
PevComponents.Planner planner = in -> new PevComponents.Plan(in,
        List.of(new PevComponents.PlanNode("reason", in)));

// Executor：调 LLM 做推理（这里用你的 LLM 客户端）
PevComponents.Executor executor = nodes -> Map.of(
        "reason", new NodeResult.Success(llm.chat(nodes.get(0).description())));

// Verifier：调 LLM 判 PASS/FAIL
PevComponents.Verifier verifier = (in, r) -> {
    String out = ((NodeResult.Success) r.get("reason")).value().toString();
    String verdict = llm.chat("判断回答是否满足要求：" + in + "\n回答：" + out + "\n只回 PASS 或 FAIL");
    boolean pass = verdict.toUpperCase().contains("PASS") && !verdict.toUpperCase().contains("FAIL");
    return new PevKernel.VerifyResult(pass, pass ? Set.of() : Set.of("reason"), verdict);
};

PEVAgent agent = new PEVAgent(AgentCard.builder().build(), planner, executor, verifier);
Object out = agent.invoke("用一句话解释什么是 Plan-Execute-Verify 模式。", null);
```

**它证了什么**：Plan→Execute→Verify 闭环 + LLM 数据通道（execute 的 LLM 产出 + verify 的 LLM 判定）。verify 不通过时会走 Diagnose→Dispatch（LocalReplan 重做）。见 `PEVAgentRealLlmE2eTest`。

---

### B. 单工具调用

**场景**：任务需要调一个工具拿数据，LLM 规划 → 工具执行 → 验证。

```java
// 你的工具（plain Java Function）
Map<String, Function<Map<String,Object>, String>> tools = Map.of(
        "getClaimInfo", args -> lookupClaim(String.valueOf(args.get("caseNo"))));

PevComponents.Planner planner = new LlmPlanner(llm, Map.of(
        "getClaimInfo", "查询理赔案件信息。参数：caseNo。"));   // LLM 产计划，工具名注入 prompt

PevComponents.Executor executor = new ToolBackedExecutor(llm, tools); // 自动派发到工具

PevComponents.Verifier verifier = new LlmVerifier(llm);               // LLM 判 PASS/FAIL

PEVAgent agent = new PEVAgent(AgentCard.builder().build(), planner, executor, verifier);
Object out = agent.invoke("查询案件 CLM-2026-REDUCE 的信息。", null);
```

**它证了什么**：LLM 规划出 TOOL_CALL 节点 → ToolBackedExecutor 按"节点描述里的工具名"派发 → 工具返回数据 → verify。见 `StraightThroughE2eTest`。

> **关键**：`ToolBackedExecutor` 按 **longest-match** 把节点描述里提到的工具名匹配到注册的工具；匹配不上就走 LLM 推理。所以 Planner 的 prompt 要让 LLM 在 TOOL_CALL 节点的 description 里**写明工具名**。

---

### C. 多步骤工具链

**场景**：任务需要多个工具依次/并行执行（DAG 规划）。PEV 串行执行节点，逐个产出结果，最后 verify 整体。

```java
Map<String, Function<Map<String,Object>, String>> tools = Map.of(
        "getTemperature", args -> "25°C",
        "getHumidity",    args -> "60%",
        "summarizeWeather", args -> "晴，25°C，湿度60%");

PevComponents.Planner planner = new LlmPlanner(llm, toolDescriptions(tools));
PevComponents.Executor executor = new ToolBackedExecutor(llm, tools);
PevComponents.Verifier verifier = new LlmVerifier(llm);

PEVAgent agent = new PEVAgent(AgentCard.builder().build(), planner, executor, verifier);
Object out = agent.invoke("分别获取北京的温度和湿度，然后总结天气。", null);
// LLM 规划出 3 节点：getTemperature / getHumidity / summarizeWeather
// executor 依次执行，verify 判总结是否涵盖了温湿度
```

**它证了什么**：多节点 DAG 规划 + 顺序执行 + 整体验证。见 `MultiStepToolsE2eTest`。

> **诚实边界**：当前 Executor 串行执行节点（按 Plan 顺序）。真并行（BSP/Pregel 拓扑）需升级 Executor——当前是"顺序 + verify"语义，不是"DAG 并行"。

---

### D. 业务场景：理赔复审

**场景**：真实业务——理赔审核，多个专业工具（查案 / 理算 / 反欺诈 / 免赔 / 大额复核），LLM 规划完整复审流程，verify 判结论是否正确。

```java
// 5 个理赔工具（确定性 fixture，业务语义）
Map<String, Function<Map<String,Object>, String>> claimTools = ClaimTools.all();
// getClaimInfo / assessLiability / scoreFraudRisk / calcDeductible / authorizePayment

PevComponents.Planner planner = new LlmPlanner(llm, ClaimTools.descriptions());
PevComponents.Executor executor = new ToolBackedExecutor(llm, claimTools);
PevComponents.Verifier verifier = new LlmVerifier(llm);

PEVAgent agent = new PEVAgent(AgentCard.builder().build(), planner, executor, verifier);
String task = "审核 CLM-2026-REDUCE 标准理赔：核实案件、理算、欺诈风险，"
        + "计算赔付额并判断大额复核，给出结论。";
Object out = agent.invoke(task, null);
// LLM 规划 ~5 节点 → 依次调 5 工具 → verify 判结论
```

**它证了什么**：完整业务闭环（规划 + 多工具 + 验证）+ 对对抗场景的鲁棒性。见 `ClaimsAdjudicationE2eTest` / `AdversarialCatchE2eTest`。

> **对抗变体**：换 `CLM-2026-ADVERSARY` fixture（材料诱导 100% 全额，但正确结论是减赔 85%）→ 测 verify 能否抓住错误决定。LLM 行为不确定，软观察（不强断 verify 必 FAIL）。

---

### E. 设备故障降级

**场景**：工具执行失败（超时/异常）→ PEV 诊断 DeviceFailure → AcceptPartial 诚实降级（不盲目重试坏掉的设备）。

```java
// 一个"总是抛异常"的工具（模拟设备故障）
Map<String, Function<Map<String,Object>, String>> brokenTool = Map.of(
        "fetchData", args -> { throw new RuntimeException("connection timeout"); });

PevComponents.Executor executor = new ToolBackedExecutor(llm, brokenTool);
// ... planner + verifier 同上

PEVAgent agent = new PEVAgent(AgentCard.builder().build(), planner, executor, verifier);
Object out = agent.invoke("用 fetchData 获取数据。", null);

// ToolBackedExecutor 把工具异常映射成 NodeResult.DeviceFailure
// → PevKernel.diagnoseRootCause 判 DeviceFailure
// → toReplanAction 返 AcceptPartial（永不重试坏设备）
// → agent 诚实降级终止，输出含 [DeviceFailure] 标记
```

**它证了什么**：PEV 的核心价值——**类型驱动的诚实自愈**。DeviceFailure 不盲目重试（坏设备重跑还错），直接 AcceptPartial 降级。这是 PEV 区别于"无脑重试"的关键。见 `DeviceFailureDegradesE2eTest`（**硬绿，确定性，不依赖 LLM**）。

> **三种根因 → 三种动作**（IFF 契约）：
> - `DeviceFailure`（工具/infra 坏）→ `AcceptPartial`（永不重试）
> - `PerceptionUnreliable`（verifier 自己崩）→ `AcceptPartial`（别盲信它的 FAILED）
> - `PlanOrAnswerError`（内容错）→ `LocalReplan`（≤2 节点）/ `GlobalReplan`（>2 或空）

---

## 组件 SPI 详解

三个阶段都是接口（`PevComponents`），你注入实现。生产用 LLM-backed，测试用 mock。

### Planner — 任务 → 计划

```java
public interface Planner {
    Plan plan(String userInput);
}
public record Plan(String goal, List<PlanNode> nodes) {}
public record PlanNode(String id, description) {}
```

- **LlmPlanner**（test-scope 参考）：调 LLM 产 JSON 计划，解析成 `Plan`。健壮：解析失败 fallback 单 LLM_CALL 节点。
- **自定义**：你可以写规则式 Planner（模板匹配）、或接 agent-core-java 的 `DefaultPlanner`。

### Executor — 节点 → 结果

```java
public interface Executor {
    Map<String, NodeResult> execute(List<PlanNode> nodes);
}
```

返回 `nodeId → NodeResult`。`NodeResult` 是 sealed 3 态：
- `Success(value)` — 正常完成
- `DeviceFailure(nodeId, error, isTimeout)` — 工具/infra 故障（**触发 AcceptPartial 降级**）
- `VerifierFailure(nodeId, reason)` — 验证器判失败

- **ToolBackedExecutor**（test-scope 参考）：按节点描述里的工具名派发；工具异常 → `DeviceFailure`；无工具匹配 → LLM 推理。

### Verifier — 结果 → 判定

```java
public interface Verifier {
    PevKernel.VerifyResult verify(String userInput, Map<String, NodeResult> completed);
}
public record VerifyResult(boolean passed, Set<String> failedNodes, String feedback, boolean parseFailure) {}
```

- `passed=true` → PEV 终止出结果。
- `passed=false` + `failedNodes` → 进 Diagnose→Dispatch。
- `parseFailure=true`（verifier 自己崩/返 null）→ `PerceptionUnreliable` → `AcceptPartial`。

---

## 认知 Rail（横切）

PEVAgent 在 4 阶段边界 `fireCallbackEvent`，任何 `AgentRail`（agent-core-java）经 `registerRail` 可观测 PEV 各阶段——横切 rail 与 PEV 主循环共存：

```java
import com.openjiuwen.agents.pev.rail.CriteriaVerificationRail;
import com.openjiuwen.agents.pev.rail.RootCauseRail;

PEVAgent agent = new PEVAgent(card, planner, executor, verifier);

// 注册认知 rail（横切，不影响 PEV 主 dispatch）
agent.registerRail(new CriteriaVerificationRail(Set.of("配置建议", "风险引用"))); // 终态二次 criteria 校验
agent.registerRail(new RootCauseRail());                                     // 设备故障 observability

Object out = agent.invoke(task, null);

// rail 在 PEV 各阶段被触发（plan/execute/verify/terminal），记录观测
// CriteriaVerificationRail.lastVerified() / RootCauseRail.deviceFailureCount()
```

**内置 rail**：
- `CriteriaVerificationRail`（afterInvoke）— 对最终输出做 success-criteria 关键字校验，defense-in-depth。
- `RootCauseRail`（afterToolCall）— 累积 DeviceFailure 节点，observability。

**自定义 rail**：继承 `AgentRail`，覆写 `afterModelCall` / `afterToolCall` / `afterInvoke` 等钩子，`registerRail` 注册。rail 是**观察者 + 横切**（不驱动 PEV 的 dispatch，那是 kernel 决策函数的职责）。

---

## 测试约定

PEV 用**诚实分层**（承重铁律）：

| 层 | 证什么 | 怎么写 |
|----|--------|--------|
| **mock 承重**（src/test，硬断言）| 控制流（dispatch 各分支走对）| mock Planner/Executor/Verifier，断言 invoke 走对路径 + mutation-RED |
| **real LLM e2e**（src/test/e2e，软观察）| 数据通道（真 LLM 在环）| 真 LLM 组件，requireEnv gate，软断言（output 非空/含预期），不强断 LLM 具体措辞 |

```java
// mock 承重示例：DeviceFailure → AcceptPartial 不重试
@Test void deviceFailure_acceptPartial_terminatesWithoutRetry() {
    AtomicInteger execCount = new AtomicInteger();
    PevComponents.Executor exec = nodes -> {
        execCount.incrementAndGet();
        return Map.of("A", new NodeResult.DeviceFailure("A", "timeout", true));
    };
    PEVAgent agent = new PEVAgent(card(), planner, exec, verifier);
    agent.invoke("do A", null);
    assertThat(execCount.get()).isEqualTo(1); // AcceptPartial 不重试，executor 只调一次
    // mutation-RED: 剥 AcceptPartial terminal → executor 被重调 → execCount>1 → RED
}
```

real LLM e2e 见 `src/test/java/.../pev/e2e/`（LlmClient + LlmPlanner + ToolBackedExecutor + LlmVerifier + 6 场景测试，跨模型验证过 GLM-5.2 + Qwen3.5-flash）。

---

## 模块结构 + 依赖

```
src/main/java/com/openjiuwen/agents/pev/
├── kernel/   决策核心（零 base 耦合，可被其他 agent 模式复用）
│   ├── RootCause.java       sealed 3态：为什么 verify 失败
│   ├── ReplanAction.java    sealed 3态：做什么
│   ├── NodeResult.java      sealed 3态：节点终态分类
│   └── PevKernel.java       diagnoseRootCause + toReplanAction 纯函数
├── agent/    PEV agent 本体
│   ├── PEVAgent.java        extends BaseAgent，invoke 跑 PEV 闭环
│   └── PevComponents.java   Planner/Executor/Verifier SPI
└── rail/     认知 rail
    ├── CriteriaVerificationRail.java
    └── RootCauseRail.java
src/test/java/.../pev/e2e/   real LLM e2e 参考（LlmClient/LlmPlanner/ToolBackedExecutor/LlmVerifier/ClaimTools）
```

**依赖**：`agent-core-java`（0.1.12-jdk17，`BaseAgent` / `AgentRail` / `AgentCallbackContext`）；Java 17（继承父工程 release，使用 sealed 类型）。

**构建**：
```bash
mvn -pl common/agent-runtime-ext-java/agent-patterns/pev -am test
```

**配置**：`PEVAgent.configure(new PEVAgent.PevConfig(maxRetries))` — maxRetries 控 verify 循环上限（默认 2）。

---

## 设计要点（为什么这样）

- **三层 sealed 类型**（RootCause/ReplanAction/NodeResult）：编译器当防火墙——删 dispatch 的 case arm → 编译红，不是运行时漏分支。
- **纯函数诊断**（`PevKernel.diagnoseRootCause`）：用确定性信号判因，不问 LLM"你觉得为什么失败"（LLM 会编）。
- **IFF dispatch 契约**：DeviceFailure/PerceptionUnreliable → 永不重试（AcceptPartial）；PlanOrAnswerError → 永不降级（LocalReplan/GlobalReplan）。
- **Rail seam**：PEV 主 dispatch 不变，横切能力（criteria 校验、故障观测、budget 等）通过 rail 组合，不耦合进主循环。
- **kernel 零 base 耦合**：`kernel/` 只依赖 java.base，可被其他 agent 模式（如未来的 EDPA）直接复用 diagnose+dispatch。
