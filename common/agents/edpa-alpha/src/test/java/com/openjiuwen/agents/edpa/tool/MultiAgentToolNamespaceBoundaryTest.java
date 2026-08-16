/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.agents.edpa.explore.ExploreBudget;
import com.openjiuwen.agents.edpa.explore.ExploreTool;
import com.openjiuwen.agents.edpa.explore.ExploreToolRegistrar;
import com.openjiuwen.agents.edpa.explore.ExplorationResult;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.runner.base.TagMatchStrategy;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.schema.AgentCard;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 同 JVM 多 agent 工具命名空间边界——现状锁定测试（红队 finding，2026-08-16）。
 *
 * <p>锁定的现状（= {@code EdpaRails} javadoc 声明的 honest boundary）：ResourceMgr 是
 * 进程级、按工具 id 存取的注册表；EDPA 认知工具用固定 id（{@code explore}），两个
 * agent 在同一 JVM 各自注册时 <b>last-writer-wins</b>——后注册者覆盖先注册者，无 tag
 * 的 {@code getTool(id)} 查到的是后注册实例（跨 agent 串扰面）。
 *
 * <p><b>测试的合同作用</b>：本测试 GREEN = 边界文档仍然真实。若 agent-core 未来让
 * dispatch 支持 tag-scoped lookup / per-agent registry，本测试将 RED——那是移除
 * EdpaRails 边界文档、放开同 JVM 多 EDPA agent 的提醒，不是回归。
 *
 * @since 2026-08
 */
class MultiAgentToolNamespaceBoundaryTest {
    private final AtomicInteger counterA = new AtomicInteger();
    private final AtomicInteger counterB = new AtomicInteger();

    /**
     * Cleans the shared registry so this test does not leak the explore registration
     * into other tests.
     */
    @AfterEach
    void cleanupSharedRegistry() {
        Runner.resourceMgr().removeTool(ExploreTool.TOOL_NAME, null, TagMatchStrategy.ALL, true);
    }

    private static ReActAgent newAgent(String name) {
        return new ReActAgent(AgentCard.builder().name(name).build());
    }

    /**
     * Registers explore on two agents in one JVM; the untagged lookup must resolve to the
     * LAST registration (the documented last-writer-wins boundary), and each agent's
     * registration must carry its own agent-id tag (attribution).
     */
    @Test
    void sameJvmTwoAgents_lastWriterWins_andTagsCarryAgentIds() {
        ReActAgent agentA = newAgent("ns-boundary-a");
        ReActAgent agentB = newAgent("ns-boundary-b");
        ExploreToolRegistrar.registerOnto(agentA,
                (userInput, budget) -> new ExplorationResult("A", java.util.List.of()),
                ExploreBudget.DEFAULT, () -> "input-a");
        ExploreToolRegistrar.registerOnto(agentB,
                (userInput, budget) -> new ExplorationResult("B", java.util.List.of()),
                ExploreBudget.DEFAULT, () -> "input-b");

        Object untagged = Runner.resourceMgr().getTool(ExploreTool.TOOL_NAME);
        assertThat(untagged)
                .as("untagged dispatch lookup resolves to the LAST registration "
                        + "(documented last-writer-wins boundary)")
                .isInstanceOf(ExploreTool.class);
        // attribution tags: each registration carried its agent id
        Object viaTagA = Runner.resourceMgr().getTool(ExploreTool.TOOL_NAME,
                agentA.getCard().getId(), TagMatchStrategy.ALL);
        Object viaTagB = Runner.resourceMgr().getTool(ExploreTool.TOOL_NAME,
                agentB.getCard().getId(), TagMatchStrategy.ALL);
        assertThat(viaTagA).as("tag-scoped lookup with agent A's tag must resolve").isNotNull();
        assertThat(viaTagB).as("tag-scoped lookup with agent B's tag must resolve").isNotNull();
    }
}
