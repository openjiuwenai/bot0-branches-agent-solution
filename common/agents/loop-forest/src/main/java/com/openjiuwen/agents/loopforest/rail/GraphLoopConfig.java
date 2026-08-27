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
     * 最小配置（骨架档：空 Veto 契约走 isEmpty-gate 不挂 veto、null 评估器
     * 走 null-gate 不挂收敛——只做分叉记录；fork 描述取默认文案。
     * 注意：恒空（非 null）评估器仍会挂载 ConvergenceRail——勿用恒空替代 null）。
     *
     * @param executor 子任务执行逻辑
     * @return 最小配置（空契约 + null 评估器 + 默认 fork 描述）
     */
    public static GraphLoopConfig minimal(SubAgentExecutor executor) {
        // R3-2 修正：null evaluator 走 GraphLoopRails 的 null-gate（真不挂
        // 收敛）——旧版恒空评估器非 null，ConvergenceRail 仍被构造挂载，
        // "不挂收敛"的 javadoc 契约为假（gate 测试同款 null 约定）
        return new GraphLoopConfig(
                new VetoContract(Collections.emptyMap()),
                "veto",
                null,
                executor,
                "Fork a sub-task exploration");
    }
}
