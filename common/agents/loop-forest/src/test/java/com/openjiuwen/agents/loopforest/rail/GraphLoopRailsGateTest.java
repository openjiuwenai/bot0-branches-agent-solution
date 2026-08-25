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
}
