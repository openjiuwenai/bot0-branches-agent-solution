/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.loopforest.verification;

import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.ModelCallInputs;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ConvergenceRail 承重测试——真实 AgentCallbackContext + ModelCallInputs。
 *
 * <p>承重断言（content-IFF，非"有调用"弱断言）：
 * <ul>
 *   <li>终答（无 tool call）+ 多分支 + 有合格候选 → extra 归因写入胜者 + winner 计数</li>
 *   <li>归因不替换：不请求 forceFinish（e2e 实证 forceFinish 会整体替换 agent 输出）</li>
 *   <li>有 tool call / 单候选 / 全不合格 → 不归因</li>
 * </ul>
 *
 * @since 2026-08
 */
class ConvergenceRailTest {

    // 多行候选（数字不在首行——钉 String.matches 跨行漏判坑）；B 刻意更长（长度评分 → 胜者 b-2）
    private static final String BRANCH_A = "Research complete.\n\nGDP: Netherlands ranks 18th.";

    private static final String BRANCH_B = "Research complete.\n\nJapan ranks 4th in nominal GDP "
            + "(about 4 trillion USD, 2024) and 5th by PPP; Netherlands ranks 18th "
            + "(about 1 trillion USD, 2024).";

    /** 评分：含数字（跨行）且长度 &gt; 50 → 合格，分值 = 长度。 */
    private static final ConvergenceEvaluator EVALUATOR = new ConvergenceEvaluator() {
        @Override
        public Optional<Double> score(String branchId, String result) {
            if (result != null && result.length() > 50
                    && result.chars().anyMatch(Character::isDigit)) {
                return Optional.of((double) result.length());
            }
            return Optional.empty();
        }
    };

    private static AgentCallbackContext newCtx(AssistantMessage response) {
        ModelCallInputs inputs = ModelCallInputs.builder()
                .messages(List.of())
                .response(response)
                .build();
        return AgentCallbackContext.builder()
                .agent(new Object())
                .event(null)
                .inputs(inputs)
                .extra(new HashMap<>())
                .build();
    }

    private static AssistantMessage textAnswer(String content) {
        AssistantMessage am = new AssistantMessage();
        am.setContent(content);
        return am;
    }

    private static AssistantMessage toolCallAnswer() {
        AssistantMessage am = new AssistantMessage();
        am.setToolCalls(List.of(new com.openjiuwen.core.foundation.llm.schema.ToolCall()));
        return am;
    }

    private static List<ConvergenceEvaluator.Candidate> candidates(
            String a, String b) {
        return List.of(
                new ConvergenceEvaluator.Candidate("b-1", a),
                new ConvergenceEvaluator.Candidate("b-2", b));
    }

    @Test
    void finalAnswerWithQualifiedBranchesRecordsWinnerInExtra() {
        ConvergenceRail rail = new ConvergenceRail(EVALUATOR,
                () -> candidates(BRANCH_A, BRANCH_B));
        AgentCallbackContext ctx = newCtx(textAnswer("synthesis answer"));

        rail.afterModelCall(ctx);

        // 归因写 extra：胜者 + 候选数（content-IFF——B 更长，胜者必须是 b-2）
        assertThat(ctx.getExtra())
                .containsEntry(ConvergenceRail.CONVERGENCE_WINNER_KEY, "b-2")
                .containsEntry(ConvergenceRail.CONVERGENCE_CANDIDATES_KEY, 2);
        assertThat(rail.getWinningBranchId()).isEqualTo("b-2");
        assertThat(rail.getConvergenceCount()).isEqualTo(1);
    }

    @Test
    void attributionDoesNotRequestForceFinish() {
        ConvergenceRail rail = new ConvergenceRail(EVALUATOR,
                () -> candidates(BRANCH_A, BRANCH_B));
        AgentCallbackContext ctx = newCtx(textAnswer("synthesis answer"));

        rail.afterModelCall(ctx);

        // 归因不替换——不 forceFinish（终答已产出，替换会丢综合答案）
        assertThat(ctx.hasForceFinishRequest())
                .as("终答时收敛只归因，不得 forceFinish 替换 agent 输出")
                .isFalse();
    }

    @Test
    void toolCallResponseSkipsConvergence() {
        ConvergenceRail rail = new ConvergenceRail(EVALUATOR,
                () -> candidates(BRANCH_A, BRANCH_B));

        rail.afterModelCall(newCtx(toolCallAnswer()));

        assertThat(rail.getWinningBranchId()).isNull();
        assertThat(rail.getConvergenceCount()).isZero();
    }

    @Test
    void singleCandidateSkipsConvergence() {
        ConvergenceRail rail = new ConvergenceRail(EVALUATOR,
                () -> List.of(new ConvergenceEvaluator.Candidate("b-1", BRANCH_A)));
        AgentCallbackContext ctx = newCtx(textAnswer("answer"));

        rail.afterModelCall(ctx);

        assertThat(rail.getWinningBranchId()).isNull();
        assertThat(ctx.getExtra())
                .doesNotContainKey(ConvergenceRail.CONVERGENCE_WINNER_KEY);
    }

    @Test
    void allUnqualifiedCandidatesSkipAttribution() {
        ConvergenceRail rail = new ConvergenceRail(EVALUATOR,
                () -> candidates("no digits here at all", "still no digits"));
        AgentCallbackContext ctx = newCtx(textAnswer("answer"));

        rail.afterModelCall(ctx);

        assertThat(rail.getWinningBranchId()).isNull();
        assertThat(ctx.getExtra())
                .doesNotContainKey(ConvergenceRail.CONVERGENCE_WINNER_KEY);
    }

    @Test
    void nonModelCallInputsIsNoOp() {
        ConvergenceRail rail = new ConvergenceRail(EVALUATOR,
                () -> candidates(BRANCH_A, BRANCH_B));
        AgentCallbackContext ctx = AgentCallbackContext.builder()
                .agent(new Object())
                .event(null)
                .inputs(null)
                .extra(new HashMap<>())
                .build();

        rail.afterModelCall(ctx);

        assertThat(rail.getWinningBranchId()).isNull();
    }

    @Test
    void nullExtraStillRecordsWinner() {
        ConvergenceRail rail = new ConvergenceRail(EVALUATOR,
                () -> candidates(BRANCH_A, BRANCH_B));
        AgentCallbackContext ctx = AgentCallbackContext.builder()
                .agent(new Object())
                .event(null)
                .inputs(ModelCallInputs.builder()
                        .messages(List.of())
                        .response(textAnswer("answer"))
                        .build())
                .extra(null)
                .build();

        rail.afterModelCall(ctx);

        // extra 缺失不吞归因——getter 仍可观测
        assertThat(rail.getWinningBranchId()).isEqualTo("b-2");
    }
}
