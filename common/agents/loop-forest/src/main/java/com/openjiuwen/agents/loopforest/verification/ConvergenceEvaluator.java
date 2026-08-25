/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.loopforest.verification;

import java.util.List;
import java.util.Optional;

/**
 * 收敛评估器 SPI——跨分支择优的确定性裁决接口。
 *
 * <p>实现方（宿主）提供具体的评估逻辑。零 LLM——收敛评估必须是确定性信号
 * （GLH-1 禁区 2：模型弱时 LLM-judge 更弱）。
 *
 * <p>与 {@link CriteriaVerifier} 的关系：CriteriaVerifier 判定单分支的
 * pass/fail；本接口在多个 pass 分支中选最优（或判定全部 fail）。
 *
 * @since 2026-08
 */
public interface ConvergenceEvaluator {

    /**
     * 评估一个分支的质量（确定性，零 LLM）。
     *
     * @param branchId 分支标识
     * @param result   分支的结果文本
     * @return 质量分数（越高越好）；无法评估返回 empty（视为不合格）
     */
    Optional<Double> score(String branchId, String result);

    /**
     * 从多个候选中选出最优分支。
     *
     * <p>默认实现：取 score 最高者。实现方可覆盖（如带 tie-breaking 规则）。
     *
     * @param candidates 候选分支（branchId + result）
     * @return 最优分支的 branchId；全部不合格返回 empty
     */
    default Optional<String> bestOf(List<Candidate> candidates) {
        String bestId = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Candidate c : candidates) {
            Optional<Double> s = score(c.branchId(), c.result());
            if (s.isPresent() && s.get() > bestScore) {
                bestScore = s.get();
                bestId = c.branchId();
            }
        }
        return Optional.ofNullable(bestId);
    }

    /**
     * 候选分支（branchId + 结果文本）。
     *
     * @param branchId 分支标识
     * @param result   分支结果
     */
    record Candidate(String branchId, String result) {
    }
}
