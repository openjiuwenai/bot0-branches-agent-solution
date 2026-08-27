/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.loopforest.fork;

import com.openjiuwen.agents.loopforest.observability.BranchedTrace;
import com.openjiuwen.agents.edpa.subagent.SubAgentDispatcher;
import com.openjiuwen.agents.edpa.subagent.SubAgentExecutor;
import com.openjiuwen.agents.pev.observability.PevTrace;
import com.openjiuwen.agents.loopforest.observability.TraceForest;

import com.openjiuwen.agents.loopforest.verification.ConvergenceEvaluator;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Fork 编排器——将 SubAgent 分叉结果登记到 {@link TraceForest}（graph 的执行引擎）。
 *
 * <p>组合现有 {@link SubAgentDispatcher} 模式：fork_subtask 工具注册沿用 SubAgentTool，
 * 但执行经过本类包装——子任务完成后自动在森林中创建 {@link BranchedTrace}。
 *
 * <p><b>最小 graph 闭环</b>（MVP）：
 * <ol>
 *   <li>宿主 agent 的 LLM 调用 fork_subtask(branchBrief)</li>
 *   <li>本类的 tracked executor 执行子任务（委托给实际 SubAgentExecutor）</li>
 *   <li>完成后在 TraceForest.addFork 创建分支（parentBranchId + forkPhaseIndex）</li>
 *   <li>结果返回给宿主——森林持有完整分支结构</li>
 * </ol>
 *
 * <p><b>Honest boundary</b>：
 * <ul>
 *   <li>MVP 的分支 trace 是结果投影（非子 agent 的真实 PevTrace）——当子 agent
 *       是 PEVAgent 时，未来应接 PevTraceSink 获取完整轨迹</li>
 *   <li>不做束宽管理/预算控制——fork 无限触发（由宿主的 BudgetRail 限制）</li>
 *   <li>不做收敛评估——ConvergenceRail 是独立组件（Step 4）</li>
 * </ul>
 *
 * @since 2026-08
 */
public final class ForkOrchestrator {

    private final TraceForest forest;

    /** 分支结果暂存：branchId → result（供 ConvergenceRail 消费）。 */
    private final Map<String, String> branchResults = new ConcurrentHashMap<>();

    /**
     * 构造 ForkOrchestrator。
     *
     * @param forest 轨迹森林（由宿主创建并持有）
     */
    public ForkOrchestrator(TraceForest forest) {
        this.forest = forest;
    }

    /**
     * 创建一个追踪分叉的 SubAgentExecutor。
     *
     * <p>用法：
     * <pre>
     * ForkOrchestrator orch = new ForkOrchestrator(forest);
     * SubAgentExecutor exec = orch.forking(parentBranchId, forkPhase,
     *         (userInput, subGoal) -> actualExecution(userInput, subGoal));
     * SubAgentDispatcher.registerOnto(agent, "fork_subtask", "Fork a sub-task",
     *         exec, userInputSupplier);
     * </pre>
     *
     * @param parentBranchId 父分支 ID
     * @param forkPhaseIndex 父 trace 中的分叉点索引
     * @param delegate 实际执行逻辑
     * @return 包装后的 executor（执行后自动登记到森林）
     */
    public SubAgentExecutor forking(String parentBranchId, int forkPhaseIndex,
            SubAgentExecutor delegate) {
        return (userInput, subGoal) -> {
            String result = delegate.execute(userInput, subGoal);
            BranchedTrace branch = forest.addFork(parentBranchId, forkPhaseIndex,
                    resultTrace(result, subGoal));
            branchResults.put(branch.branchId(), result);
            return result;
        };
    }

    /**
     * 创建根分支的 tracked executor（从根分叉）。
     *
     * @param delegate 实际执行逻辑
     * @return 包装后的 executor
     */
    public SubAgentExecutor forkingFromRoot(SubAgentExecutor delegate) {
        // 根分支需要先存在（R1-F6：字段追踪替代死守卫——findRootId 复用既有根）
        String rootId = findRootId();
        if (rootId == null) {
            rootId = forest.addRoot(resultTrace("root", "root")).branchId();
            this.rootBranchId = rootId;
        }
        String finalRootId = rootId;
        return forking(finalRootId, 0, delegate);
    }

    /**
     * 获取指定分支的结果文本。
     *
     * @param branchId 分支 ID
     * @return 结果文本；无结果返回 null
     */
    public String resultOf(String branchId) {
        return branchResults.get(branchId);
    }

    /**
     * 获取全部分支结果（供 ConvergenceRail 消费）。
     *
     * @return 候选列表（branchId + result）
     */
    public List<ConvergenceEvaluator.Candidate> allResults() {
        // 按 branchId 排序——CHM 迭代序不定，平分时 bestOf 取首个会随哈希序漂移
        return branchResults.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new ConvergenceEvaluator.Candidate(e.getKey(), e.getValue()))
                .collect(Collectors.toList());
    }

    /**
     * 获取森林（供 ConvergenceRail 导航）。
     *
     * @return 关联的 TraceForest
     */
    public TraceForest forest() {
        return forest;
    }

    /**
     * 查找森林中的根分支 ID。
     *
     * @return 第一个根分支的 ID；无根返回 null
     */
    /** 根分支 ID（addRoot 时记账——R1-F6：替代两臂皆 null 的死守卫）。 */
    private String rootBranchId;

    private String findRootId() {
        return rootBranchId; // null=尚未建根（首次调用 forkingFromRoot 时建）
    }

    /**
     * 从子任务结果构造 PevTrace（MVP 投影——非真实 PEV 执行轨迹）。
     *
     * @param result 子任务结果文本
     * @param subGoal 子任务目标
     * @return 最小 PevTrace
     */
    private static PevTrace resultTrace(String result, String subGoal) {
        return new PevTrace(
                List.of(),
                result == null || result.isBlank()
                        ? PevTrace.TerminalReason.INCONCLUSIVE
                        : PevTrace.TerminalReason.PASSED,
                1);
    }
}
