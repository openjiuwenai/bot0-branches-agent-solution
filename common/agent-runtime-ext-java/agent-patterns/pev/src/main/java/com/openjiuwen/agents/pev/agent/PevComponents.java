/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.pev.agent;

import com.openjiuwen.agents.pev.kernel.NodeResult;
import com.openjiuwen.agents.pev.kernel.PevKernel;

import java.util.List;
import java.util.Map;

/**
 * PEV 阶段组件 SPI — Planner / Executor / Verifier 注入式接口。
 *
 * <p>注入式设计让单测可 mock 三阶段（控制流证据：mock 每个 stage 返回特定结果 → 断言 dispatch 走对分支），
 * 生产实现可接 LLM/工具/真 verifier。
 *
 * @since 2026-07
 */
public final class PevComponents {

    private PevComponents() {
    }

    /**
     * 单个执行节点（Plan 的组成单元）。
     *
     * @since 2026-07
     */
    public record PlanNode(String id, String description) {
    }

    /**
     * Plan 阶段产物：目标 + 节点列表。
     */
    public record Plan(String goal, List<PlanNode> nodes) {
    }

    /**
     * Plan 阶段：NL 目标 → Plan。
     */
    public interface Planner {
        /**
         * Build an execution plan for the user input.
         *
         * @param userInput user request text
         * @return planned goal and execution nodes
         */
        Plan plan(String userInput);
    }

    /**
     * Execute 阶段：执行节点，返回 nodeId → {@link NodeResult}。
     */
    public interface Executor {
        /**
         * Execute plan nodes.
         *
         * @param nodes nodes to execute
         * @return node id to execution result
         */
        Map<String, NodeResult> execute(List<PlanNode> nodes);
    }

    /**
     * Verify 阶段：评估执行结果，返回结构化判定（{@link PevKernel.VerifyResult}）。
     */
    public interface Verifier {
        /**
         * Verify completed node results.
         *
         * @param userInput original user request
         * @param completed completed node results
         * @return structured verification result
         */
        PevKernel.VerifyResult verify(String userInput, Map<String, NodeResult> completed);
    }
}
