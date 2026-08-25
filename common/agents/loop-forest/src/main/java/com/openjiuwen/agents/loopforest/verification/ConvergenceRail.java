/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.loopforest.verification;

import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.AgentRail;
import com.openjiuwen.core.singleagent.rail.ModelCallInputs;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 收敛 rail——当模型产出最终答案时，跨全部分支做确定性择优（graph 的收敛层）。
 *
 * <p>Hook {@code afterModelCall}：当模型产出 final answer（无 tool call）时，
 * 从 {@link BranchResultSupplier} 收集全部分支结果，用 {@link ConvergenceEvaluator}
 * 选出最优分支。择优结果<b>归因不替换</b>——写入 ctx extra（{@link #CONVERGENCE_WINNER_KEY}），
 * 不 forceFinish（终答已产出，forceFinish 会整体替换 agent 输出、丢失综合答案）。
 *
 * <p><b>与 VetoRail 的关系</b>：VetoRail 管过程纪律（每分支不跑偏），
 * 本 rail 管终态收敛（跨分支选最优）。两者互补。
 *
 * <p><b>零 LLM</b>：收敛评估走 {@link ConvergenceEvaluator}（确定性），
 * 不依赖 LLM 判断（GLH-1 禁区 2）。
 *
 * <p><b>单分支退化</b>：当只有一个分支（根分支）时，本 rail 不做择优——
 * 直接放行（单分支即最终答案，无需收敛）。
 *
 * @since 2026-08
 */
public class ConvergenceRail extends AgentRail {

    /** extra 归因 key：胜出分支 ID。 */
    public static final String CONVERGENCE_WINNER_KEY = "convergence_winner";

    /** extra 归因 key：候选分支数。 */
    public static final String CONVERGENCE_CANDIDATES_KEY = "convergence_candidates";

    private final ConvergenceEvaluator evaluator;

    private final BranchResultSupplier branchSupplier;

    private String winningBranchId;

    private int convergenceCount;

    /**
     * 构造 ConvergenceRail。
     *
     * @param evaluator      收敛评估器（确定性，零 LLM）
     * @param branchSupplier 分支结果提供者（从 TraceForest/ForkOrchestrator 获取）
     */
    public ConvergenceRail(ConvergenceEvaluator evaluator, BranchResultSupplier branchSupplier) {
        this.evaluator = evaluator;
        this.branchSupplier = branchSupplier;
    }

    @Override
    public void afterModelCall(AgentCallbackContext ctx) {
        Object inputsObj = ctx.getInputs();
        if (!(inputsObj instanceof ModelCallInputs inputs)) {
            return;
        }
        // 只在 final answer（无 tool call）时触发
        if (hasToolCalls(inputs)) {
            return;
        }
        // 收集全部分支结果
        List<ConvergenceEvaluator.Candidate> candidates = branchSupplier.allResults();
        if (candidates.size() <= 1) {
            return; // 单分支或零分支——无需收敛
        }
        // 确定性择优
        Optional<String> best = evaluator.bestOf(candidates);
        if (best.isPresent()) {
            winningBranchId = best.get();
            convergenceCount++;
            // 归因不替换：终答已产出——forceFinish 会整体替换 agent 输出，
            // 丢掉宿主的综合答案（e2e 实证）。胜者写 extra 供宿主/测试消费。
            Map<String, Object> extra = ctx.getExtra();
            if (extra != null) {
                extra.put(CONVERGENCE_WINNER_KEY, winningBranchId);
                extra.put(CONVERGENCE_CANDIDATES_KEY, candidates.size());
            }
        }
        // 无最优（全部不合格）→ 不归因，让 BudgetRail/正常终止处理
    }

    /**
     * 获取胜出分支 ID。
     *
     * @return 胜出分支 ID；未收敛返回 null
     */
    public String getWinningBranchId() {
        return winningBranchId;
    }

    /**
     * 获取收敛次数。
     *
     * @return 收敛计数
     */
    public int getConvergenceCount() {
        return convergenceCount;
    }

    private static boolean hasToolCalls(ModelCallInputs inputs) {
        Object response = inputs.getResponse();
        return response instanceof AssistantMessage am
                && am.getToolCalls() != null
                && !am.getToolCalls().isEmpty();
    }

    /**
     * 分支结果提供者 SPI——从 TraceForest / ForkOrchestrator 收集全部已完成的分支结果。
     *
     * <p>宿主实现此接口，桥接 ForkOrchestrator 的结果存储到本 rail。
     */
    @FunctionalInterface
    public interface BranchResultSupplier {

        /**
         * 获取全部已完成的分支结果。
         *
         * @return 候选分支列表（branchId + result）
         */
        List<ConvergenceEvaluator.Candidate> allResults();
    }
}
