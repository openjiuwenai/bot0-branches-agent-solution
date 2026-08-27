/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.loopforest.rail;

import com.openjiuwen.agents.loopforest.fork.ForkOrchestrator;
import com.openjiuwen.agents.edpa.subagent.SubAgentDispatcher;
import com.openjiuwen.agents.edpa.subagent.SubAgentTool;
import com.openjiuwen.agents.loopforest.observability.TraceForest;
import com.openjiuwen.agents.loopforest.verification.ConvergenceEvaluator;
import com.openjiuwen.agents.loopforest.verification.ConvergenceRail;
import com.openjiuwen.agents.loopforest.verification.VetoContract;
import com.openjiuwen.agents.loopforest.verification.VetoRail;
import com.openjiuwen.core.singleagent.agents.ReActAgent;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Graph-loop 装配门面——单一装配真源（registerOnto 范式）。
 *
 * <p>把 graph-loop 四层组装到 ReActAgent：
 * <ol>
 *   <li><b>纪律层</b>：VetoRail（beforeToolCall 零提及写入拒绝）</li>
 *   <li><b>数据层</b>：TraceForest（树结构轨迹寻址）</li>
 *   <li><b>执行层</b>：fork_subtask 工具 + ForkOrchestrator（SubAgent 分叉→森林登记）</li>
 *   <li><b>收敛层</b>：ConvergenceRail（afterModelCall 跨分支确定性择优）</li>
 * </ol>
 *
 * <p><b>Honest boundary（MVP 边界）</b>：
 * <ul>
 *   <li>分支执行是<b>串行</b>的（host 调 fork_subtask → 同步执行 → 返回结果）——
 *       并行分支需要 SubAgent 并发派发（deferred）</li>
 *   <li>无束宽管理——fork 无限触发（由宿主 BudgetRail 限制轮数/token）</li>
 *   <li>无回滚执行——TraceForest.pathToRoot 只提供寻址，重放属执行层（deferred）</li>
 *   <li>ConvergenceRail 的 BranchResultSupplier 桥接 ForkOrchestrator 的结果 Map——
 *       只包含已完成分支，不含在途分支</li>
 * </ul>
 *
 * <p><b>config-gated</b>（铁律 ⑰）：VetoRail 和 ConvergenceRail 均通过
 * {@code agent.registerRail} 挂载，host 可通过 config 切换启用/禁用。
 *
 * @since 2026-08
 */
public final class GraphLoopRails {

    private GraphLoopRails() {
    }

    /**
     * 装配 graph-loop 四层到 ReActAgent。
     *
     * @param agent  宿主 agent
     * @param config 装配配置（全参数显式，无默认值）
     * @return 装配结果（森林/编排器/各 rail 句柄）
     */
    public static RegistrationSummary registerOnto(ReActAgent agent, GraphLoopConfig config) {
        // ── 数据层 ──
        TraceForest forest = new TraceForest();

        // ── 执行层 ──
        ForkOrchestrator orchestrator = new ForkOrchestrator(forest);
        SubAgentTool forkTool = SubAgentDispatcher.registerOnto(
                agent,
                BudgetRail.FORK_TOOL_NAME,
                config.forkDescription(),
                orchestrator.forkingFromRoot(config.subAgentExecutor()));

        // ── 纪律层 ──
        // 契约非空即挂载（铁律⑰：不硬编码工具名探针——任意写入工具契约均生效）
        VetoRail vetoRail = null;
        if (config.vetoContract() != null && !config.vetoContract().isEmpty()) {
            vetoRail = new VetoRail(config.vetoContract(), config.rejectionMessage());
            agent.registerRail(vetoRail);
        }

        // ── 收敛层 ──
        ConvergenceRail convergenceRail = null;
        if (config.evaluator() != null) {
            final ForkOrchestrator orch = orchestrator;
            convergenceRail = new ConvergenceRail(
                    config.evaluator(),
                    () -> bridgeResults(orch));
            agent.registerRail(convergenceRail);
        }

        return new RegistrationSummary(forest, orchestrator, vetoRail, convergenceRail, forkTool);
    }

    /**
     * 桥接 ForkOrchestrator 的结果 Map → ConvergenceRail 的 Candidate 列表。
     *
     * @param orch ForkOrchestrator
     * @return 候选分支列表
     */
    private static List<ConvergenceEvaluator.Candidate> bridgeResults(ForkOrchestrator orch) {
        return orch.allResults();
    }

    /**
     * 装配结果——持有全部组件句柄（供断言/测试/运行时查询）。
     *
     * @param forest          轨迹森林
     * @param orchestrator    fork 编排器
     * @param vetoRail        Veto rail（可能为 null——config 未配置时）
     * @param convergenceRail 收敛 rail（可能为 null——config 未配置时）
     * @param forkTool        fork 工具
     */
    public record RegistrationSummary(
            TraceForest forest,
            ForkOrchestrator orchestrator,
            VetoRail vetoRail,
            ConvergenceRail convergenceRail,
            SubAgentTool forkTool) {
    }
}
