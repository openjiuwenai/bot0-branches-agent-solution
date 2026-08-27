/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.loopforest.rail;

import com.openjiuwen.agents.edpa.subagent.SubAgentExecutor;
import com.openjiuwen.agents.loopforest.verification.VetoContract;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.schema.AgentCard;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GraphLoopRails 装配门双向测试（铁律⑰：config-gated 路径必须证开关活，
 * 非死配置）——4-lens 审查（Lens C）发现旧门硬编码 covers("write_artifact")
 * 探针：write_report 等任意其他契约会静默不挂 vetoRail。
 *
 * @since 2026-08
 */
class GraphLoopRailsGateTest {

    private static ReActAgent newAgent() {
        return new ReActAgent(AgentCard.builder().name("gate-test").build());
    }

    private static GraphLoopConfig config(VetoContract contract) {
        SubAgentExecutor noop = (userInput, subGoal) -> "done";
        return new GraphLoopConfig(contract, "rejection", null, noop, "Fork a sub-task");
    }

    @Test
    void anyNonEmptyContractMountsVetoRail() {
        // 旧实现：covers("write_artifact") 硬编码探针 → write_report 契约静默不挂载
        ReActAgent agent = newAgent();
        GraphLoopRails.RegistrationSummary summary = GraphLoopRails.registerOnto(agent,
                config(new VetoContract(Map.of("write_report",
                        Set.of("findings", "sources", "verdict")))));

        assertThat(summary.vetoRail())
                .as("非空契约（任意写入工具）必须挂载 VetoRail——铁律⑰双向之 true 侧")
                .isNotNull();
    }

    @Test
    void emptyContractMountsNoVetoRail() {
        ReActAgent agent = newAgent();
        GraphLoopRails.RegistrationSummary summary = GraphLoopRails.registerOnto(agent,
                config(new VetoContract(Map.of())));

        assertThat(summary.vetoRail())
                .as("空契约不挂载——铁律⑰双向之 false 侧（fail-open）")
                .isNull();
    }

    @Test
    void blankWhitelistEntryCountsAsEmpty() {
        // 白名单为空集的条目 = 无契约（covers 语义一致性）
        ReActAgent agent = newAgent();
        GraphLoopRails.RegistrationSummary summary = GraphLoopRails.registerOnto(agent,
                config(new VetoContract(Map.of("write_report", Set.of()))));

        assertThat(summary.vetoRail()).isNull();
    }

    // ═══ R1-F6(bearing) 处置：convergence gate 双向——原只证 null 侧 ═══

    private static GraphLoopConfig configWithEvaluator(
            com.openjiuwen.agents.loopforest.verification.ConvergenceEvaluator ev) {
        SubAgentExecutor noop = (userInput, subGoal) -> "done";
        return new GraphLoopConfig(
                new VetoContract(Map.of("write_report", Set.of("findings"))),
                "rejection", ev, noop, "Fork a sub-task");
    }

    @Test
    void nonNullEvaluatorMountsConvergenceRail() {
        ReActAgent agent = newAgent();
        var ev = new com.openjiuwen.agents.loopforest.verification
                .ConvergenceEvaluator() {
            @Override
            public java.util.Optional<Double> score(String branchId, String result) {
                return java.util.Optional.empty();
            }
        };
        GraphLoopRails.RegistrationSummary summary = GraphLoopRails.registerOnto(agent,
                configWithEvaluator(ev));
        assertThat(summary.convergenceRail())
                .as("评估器非空必须挂载 ConvergenceRail——铁律⑰双向之 true 侧")
                .isNotNull();
        assertThat(summary.vetoRail()).as("契约同时生效（两 gate 独立）").isNotNull();
    }

    @Test
    void nullEvaluatorMountsNoConvergenceRail() {
        ReActAgent agent = newAgent();
        GraphLoopRails.RegistrationSummary summary = GraphLoopRails.registerOnto(agent,
                configWithEvaluator(null));
        assertThat(summary.convergenceRail())
                .as("评估器为空不挂载——铁律⑰双向之 false 侧")
                .isNull();
    }


    // ═══ R4-1 处置：minimal() 语义锁定（mutation-RED——恒空评估器回退即红）═══

    @Test
    void minimalConfigMountsNoConvergenceRail() {
        ReActAgent agent = newAgent();
        GraphLoopRails.RegistrationSummary summary = GraphLoopRails.registerOnto(
                agent, GraphLoopConfig.minimal((userInput, subGoal) -> "done"));
        assertThat(summary.convergenceRail())
                .as("minimal()=null 评估器走 null-gate——真不挂收敛"
                        + "（R3-2 语义；恒空评估器回退会挂载 → 本断言 RED）")
                .isNull();
        assertThat(summary.vetoRail())
                .as("minimal()=空契约走 isEmpty-gate——不挂 veto").isNull();
    }
}
