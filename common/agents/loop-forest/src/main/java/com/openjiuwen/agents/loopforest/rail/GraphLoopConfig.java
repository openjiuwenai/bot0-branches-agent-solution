/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.loopforest.rail;

import com.openjiuwen.agents.edpa.subagent.SubAgentExecutor;
import com.openjiuwen.agents.loopforest.verification.ConvergenceEvaluator;
import com.openjiuwen.agents.loopforest.verification.VetoContract;

import java.util.Collections;

/**
 * Graph-loop 装配配置——一次构造，全参数显式（无隐式默认）。
 *
 * <p>所有字段由宿主提供，本类不做任何默认值填充（避免「死配置」——铁律 ⑰）。
 *
 * @param vetoContract      VetoRail 写入契约（工具名 → 允许字段白名单）
 * @param rejectionMessage VetoRail 拒绝消息（外置资源加载）
 * @param evaluator        ConvergenceRail 收敛评估器（确定性，零 LLM）
 * @param subAgentExecutor fork 子任务的实际执行逻辑
 * @param forkDescription  fork_subtask 工具的 LLM 可见描述（外置资源）
 * @since 2026-08
 */
public record GraphLoopConfig(
        VetoContract vetoContract,
        String rejectionMessage,
        ConvergenceEvaluator evaluator,
        SubAgentExecutor subAgentExecutor,
        String forkDescription) {

    /**
     * 最小配置（骨架档：空 Veto 契约不挂 veto、恒空评估器不挂收敛——只做
     * 分叉记录；fork 描述取默认文案）。
     *
     * @param executor 子任务执行逻辑
     * @return 最小配置（空契约 + 恒空评估器 + 默认 fork 描述）
     */
    public static GraphLoopConfig minimal(SubAgentExecutor executor) {
        return new GraphLoopConfig(
                new VetoContract(Collections.emptyMap()),
                "veto",
                new ConvergenceEvaluator() {
                    @Override
                    public java.util.Optional<Double> score(String branchId, String result) {
                        return java.util.Optional.empty();
                    }
                },
                executor,
                "Fork a sub-task exploration");
    }
}
