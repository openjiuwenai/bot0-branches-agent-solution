/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.pev.rail;

import com.openjiuwen.agents.pev.agent.PEVAgent;
import com.openjiuwen.agents.pev.agent.PevComponents;
import com.openjiuwen.agents.pev.kernel.NodeResult;
import com.openjiuwen.agents.pev.kernel.PevKernel;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 承重测试：注册到 PEVAgent 的 Beta 认知 rail 经 rail seam（fireCallbackEvent）观测到 PEV 各阶段。
 * 证 rail 与 PEV 主循环的横切共存（SYN 的 S9 组合范式）。
 */
class PEVAgentRailsTest {
    // ==================== CriteriaVerificationRail 观测最终输出 ====================
    @Test
    void criteriaRailVerifiesOutputContainingCriteriaKeywords() {
        CriteriaVerificationRail rail = new CriteriaVerificationRail(Set.of("result"));
        PevComponents.Planner planner = in -> new PevComponents.Plan("g",
                List.of(new PevComponents.PlanNode("A", "do A")));
        PevComponents.Executor executor = nodes -> Map.of("A", new NodeResult.Success("a-result"));
        PevComponents.Verifier verifier = (in, r) -> new PevKernel.VerifyResult(true, Set.of(), "ok");

        PEVAgent agent = new PEVAgent(AgentCard.builder().build(), planner, executor, verifier);
        agent.registerRail(rail);
        agent.invoke("do A", null);

        assertThat(rail.lastVerified()).isTrue();
        assertThat(rail.lastUnmet()).isEmpty();
        // mutation-RED: 剥 afterInvoke → lastVerified 永 false → RED
    }

    @Test
    void criteriaRailFlagsMissingCriteriaAsUnmet() {
        CriteriaVerificationRail rail = new CriteriaVerificationRail(Set.of("missing-keyword"));
        PevComponents.Planner planner = in -> new PevComponents.Plan("g",
                List.of(new PevComponents.PlanNode("A", "do A")));
        PevComponents.Executor executor = nodes -> Map.of("A", new NodeResult.Success("a-result"));
        PevComponents.Verifier verifier = (in, r) -> new PevKernel.VerifyResult(true, Set.of(), "ok");

        PEVAgent agent = new PEVAgent(AgentCard.builder().build(), planner, executor, verifier);
        agent.registerRail(rail);
        agent.invoke("do A", null);

        assertThat(rail.lastVerified()).isFalse();
        assertThat(rail.lastUnmet()).containsExactly("missing-keyword");
    }

    // ==================== RootCauseRail 观测 DeviceFailure ====================

    @Test
    void rootCauseRailObservesDeviceFailureFromExecuteSuperstep() {
        RootCauseRail rail = new RootCauseRail();
        PevComponents.Planner planner = in -> new PevComponents.Plan("g",
                List.of(new PevComponents.PlanNode("A", "do A")));
        PevComponents.Executor executor = nodes -> Map.of("A", new NodeResult.DeviceFailure("A", "timeout", true));
        PevComponents.Verifier verifier = (in, r) -> new PevKernel.VerifyResult(false, Set.of("A"), "A failed");

        PEVAgent agent = new PEVAgent(AgentCard.builder().build(), planner, executor, verifier);
        agent.registerRail(rail);
        agent.invoke("do A", null);

        assertThat(rail.deviceFailureCount()).isEqualTo(1);
        assertThat(rail.deviceFailedNodes()).containsExactly("A");
        // mutation-RED: 剥 afterToolCall 的 DeviceFailure 收集 → count==0 → RED
    }

    @Test
    void rootCauseRailNoDeviceFailureOnHappyPath() {
        RootCauseRail rail = new RootCauseRail();
        PevComponents.Planner planner = in -> new PevComponents.Plan("g",
                List.of(new PevComponents.PlanNode("A", "do A")));
        PevComponents.Executor executor = nodes -> Map.of("A", new NodeResult.Success("ok"));
        PevComponents.Verifier verifier = (in, r) -> new PevKernel.VerifyResult(true, Set.of(), "ok");

        PEVAgent agent = new PEVAgent(AgentCard.builder().build(), planner, executor, verifier);
        agent.registerRail(rail);
        agent.invoke("do A", null);

        assertThat(rail.deviceFailureCount()).isEqualTo(0);
        assertThat(rail.deviceFailedNodes()).isEmpty();
    }
}
