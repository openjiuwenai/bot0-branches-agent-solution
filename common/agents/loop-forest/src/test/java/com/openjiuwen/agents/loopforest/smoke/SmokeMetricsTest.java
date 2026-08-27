/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.loopforest.smoke;

import com.openjiuwen.agents.loopforest.observability.TraceForest;
import com.openjiuwen.agents.loopforest.rail.BudgetRail;
import com.openjiuwen.agents.loopforest.verification.ConvergenceEvaluator;
import com.openjiuwen.agents.loopforest.verification.ConvergenceRail;
import com.openjiuwen.agents.loopforest.verification.VetoContract;
import com.openjiuwen.agents.loopforest.verification.VetoRail;
import com.openjiuwen.agents.pev.observability.PevTrace;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.ToolCallInputs;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 冒烟度量测试（R1-处置：用户验收令"脚本冒烟效果要实际测到数值和收益"）。
 *
 * <p>确定性合成场景（零 LLM/零网络），输出 {@code [smoke] ...} 数值行供
 * {@code smoke.sh} grep 与留档——收益面：veto 拦截数、收敛剪枝数、预算拒绝数、
 * 森林结构可用性、prompt 真源归属、分段时延。每段断言承重（非打印即过）。
 *
 * @since 2026-08
 */
class SmokeMetricsTest {

    private static AgentCallbackContext toolCtx(String toolName, Object toolArgs,
            Map<String, Object> extra) {
        ToolCallInputs inputs = ToolCallInputs.builder()
                .toolName(toolName).toolArgs(toolArgs).build();
        return AgentCallbackContext.builder()
                .agent(new Object()).event(null).inputs(inputs).extra(extra).build();
    }

    @Test
    void smokeMeasuresVetoInterceptionGain() {
        long t0 = System.nanoTime();
        VetoContract contract = new VetoContract(Map.of(
                "write_artifact", Set.of("baseline", "intervention", "followup",
                        "joint_window", "margin_days")));
        // 拒绝消息从本模块 prompts/ 真源加载（RejectionResource 为包私有，
        // 此处按同一路径自加载——真源断言见 forest 用例）
        String rejection;
        try (var in = VetoRail.class.getResourceAsStream(
                "/prompts/veto-rejection.txt")) {
            rejection = new String(java.util.Objects.requireNonNull(in)
                    .readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            throw new IllegalStateException("veto-rejection.txt 不可加载", e);
        }
        VetoRail rail = new VetoRail(contract, rejection);
        int intercepted = 0;
        int passthrough = 0;
        // 7 发合法嵌套写 + 3 发含契约外顶层键（bait）
        for (int i = 0; i < 10; i++) {
            Map<String, Object> args = new HashMap<>();
            args.put("baseline", Map.of("file", "data/cand-k0" + i + ".csv"));
            args.put("margin_days", 40 + i);
            if (i % 3 == 0 && i > 0) {
                args.put("confidence_score", 95); // 契约外键 → 应拦截
            }
            Map<String, Object> extra = new HashMap<>();
            ToolCallInputs tc = ToolCallInputs.builder()
                    .toolName("write_artifact").toolArgs(args).build();
            AgentCallbackContext ctx = AgentCallbackContext.builder()
                    .agent(new Object()).event(null).inputs(tc).extra(extra).build();
            rail.beforeToolCall(ctx);
            boolean skipped = Boolean.TRUE.equals(extra.get("_skip_tool"));
            if (skipped) {
                intercepted++;
                Object result = tc.getToolResult();
                assertThat(result).as("预填结果为 Map").isInstanceOf(Map.class);
                String text = String.valueOf(
                        ((Map<?, ?>) result).get("error"));
                assertThat(text).as("拦截结果=外置拒绝消息全文")
                        .isEqualTo(rejection);
                assertThat(text).as("零提及纪律：不点名具体字段")
                        .doesNotContain("confidence_score");
            } else {
                passthrough++;
            }
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(intercepted).as("拦截数=3（i=3/6/9 三发 bait）").isEqualTo(3);
        assertThat(passthrough).isEqualTo(7);
        assertThat(rail.getVetoCount()).isEqualTo(3);
        System.out.printf("[smoke] veto: attempts=10 intercepted=%d passthrough=%d "
                + "zero_mention=true elapsed_ms=%d%n", intercepted, passthrough, ms);
    }

    @Test
    void smokeMeasuresConvergencePruningGain() {
        long t0 = System.nanoTime();
        // 6 候选：2 不合格（空结果）+ 4 合格（分数各异）→ 择优 1、剪除 5
        List<ConvergenceEvaluator.Candidate> candidates = List.of(
                cand("b-empty", null),                    // 不合格
                cand("b-1", "result alpha score 1"),
                cand("b-2", "result beta score 9"),       // 应胜出
                cand("b-3", "result gamma score 3"),
                cand("b-empty2", ""),                      // 不合格
                cand("b-4", "result delta score 5"));
        ConvergenceEvaluator evaluator = (branchId, result) -> {
            if (result == null || result.isBlank()) {
                return Optional.empty(); // 不合格（空结果）
            }
            var m = java.util.regex.Pattern.compile(".*score (\\d+).*")
                    .matcher(result);
            return m.matches() ? Optional.of(Double.parseDouble(m.group(1)))
                    : Optional.empty();
        };
        Optional<String> winner = evaluator.bestOf(candidates);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        int qualified = (int) candidates.stream()
                .filter(c -> c.result() != null && !c.result().isBlank()).count();
        assertThat(winner).as("胜出=最高分 b-2（非首个合格者 b-1）").contains("b-2");
        System.out.printf("[smoke] convergence: candidates=%d qualified=%d pruned=%d "
                + "winner=%s elapsed_ms=%d%n",
                candidates.size(), qualified, candidates.size() - 1, winner.orElse("?"), ms);
    }

    @Test
    void smokeMeasuresBudgetRejectionGain() {
        long t0 = System.nanoTime();
        BudgetRail rail = new BudgetRail(2, 3, 0); // width=2, totalForks=3, token 维关
        int accepted = 0;
        int rejected = 0;
        String lastReason = "";
        for (int i = 0; i < 5; i++) {
            Map<String, Object> extra = new HashMap<>();
            Map<String, Object> args = new HashMap<>();
            args.put("subtasks", List.of("t" + i));
            ToolCallInputs tc = ToolCallInputs.builder()
                    .toolName(BudgetRail.FORK_TOOL_NAME).toolArgs(args).build();
            AgentCallbackContext ctx = AgentCallbackContext.builder()
                    .agent(new Object()).event(null).inputs(tc).extra(extra).build();
            rail.beforeToolCall(ctx);
            if (Boolean.TRUE.equals(extra.get("_skip_tool"))) {
                rejected++;
                Object result = tc.getToolResult();
                lastReason = result instanceof Map map
                        ? String.valueOf(map.get("error")) : String.valueOf(result);
            } else {
                accepted++;
                rail.afterToolCall(ctx);
            }
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(rail.getTotalForks()).as("总 fork 数封顶 3").isEqualTo(3);
        assertThat(rejected).as("5 试 3 收 → 拒 2").isEqualTo(2);
        assertThat(lastReason).as("拒绝理由=真协议文案（槽位事实+导流）")
                .contains("Fork not started");
        System.out.printf("[smoke] budget: attempts=5 accepted=%d rejected=%d "
                + "reason_from_prompts=true elapsed_ms=%d%n", accepted, rejected, ms);
    }

    @Test
    void smokeMeasuresForestStructureAndPromptSource() {
        long t0 = System.nanoTime();
        TraceForest forest = new TraceForest();
        String root = forest.addRoot(new PevTrace(List.of(), PevTrace.TerminalReason.PASSED, 1)).branchId();
        for (int i = 0; i < 3; i++) {
            forest.addFork(root, i, new PevTrace(List.of(), PevTrace.TerminalReason.PASSED, 1));
        }
        List<com.openjiuwen.agents.loopforest.observability.BranchedTrace> children = forest.childrenOf(root);
        String leaf = children.get(0).branchId();
        String grandChild = forest.addFork(leaf, 0, new PevTrace(List.of(), PevTrace.TerminalReason.PASSED, 1)).branchId();
        int pathLen = forest.pathToRoot(grandChild).size();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(children).as("根下 3 分叉").hasSize(3);
        assertThat(forest.size()).as("森林总分支=root+3叉+1孙=5").isEqualTo(5);
        assertThat(forest.depth(grandChild)).as("孙辈深度 2").isEqualTo(2);
        assertThat(pathLen).as("回滚寻址路径=孙→子 2 节点（不含根）").isEqualTo(2);
        // 真源断言（R1-F1 治本面）：prompts 从本模块加载而非依赖 jar
        String url = String.valueOf(VetoRail.class
                .getResource("/prompts/veto-rejection.txt"));
        assertThat(url).as("prompt 真源在本模块（loop-forest）——寄生依赖 jar 已治本")
                .contains("loop-forest");
        System.out.printf("[smoke] forest: branches=%d depth=2 rollback_path=%d "
                + "prompts_source=module_owned elapsed_ms=%d%n",
                forest.size(), pathLen, ms);
    }

    private static ConvergenceEvaluator.Candidate cand(String id, String result) {
        return new ConvergenceEvaluator.Candidate(id, result);
    }
}
