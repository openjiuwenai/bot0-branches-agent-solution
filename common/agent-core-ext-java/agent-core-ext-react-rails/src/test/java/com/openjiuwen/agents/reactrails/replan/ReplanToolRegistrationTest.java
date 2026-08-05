/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.replan;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.core.foundation.tool.schema.ToolInfo;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.singleagent.AbilityManager;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.schema.AgentCard;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Blocker B2 承重测试 — ReplanTool 注册真化（visibility + dispatch 双通道）。
 *
 * <p>mock 单测证两条独立通道：
 * <ol>
 *   <li><b>visibility</b>: {@code registerOnto} 后 {@code listToolInfo()} 含 {@code __replan__}
 *       （AbilityManager.tools 走 ToolCard，非 Tool）。mutation-RED: 若回退到 {@code add(new ReplanTool())}
 *       （传 Tool 而非 ToolCard），addSingle 走 "Unknown ability type" 分支 → listToolInfo 空 → RED。</li>
 *   <li><b>dispatch</b>: {@code Runner.resourceMgr().getTool(__replan__)} 返回 ReplanTool 实例
 *       （executeSingleToolCall 解析路径）。mutation-RED: 若 card.id 未设（==null），
 *       ResourceMgr.addTool 存随机 UUID key → getTool(__replan__) 返 null → RED。</li>
 * </ol>
 */
class ReplanToolRegistrationTest {
    @Test
    void registerOntoCardIsVisibleToLlmViaAbilityManager() {
        ReActAgent agent = new ReActAgent(AgentCard.builder().name("reg-visibility").build());

        ReplanTool.registerOnto(agent);

        AbilityManager am = agent.getAbilityManager();
        List<ToolInfo> visible = am.listToolInfo();

        assertThat(visible).as("AbilityManager.listToolInfo must surface __replan__ (card registered, not raw Tool)")
                .anyMatch(t -> ReplanTool.TOOL_NAME.equals(t.getName()));
        // mutation-RED: revert registerOnto to add(new ReplanTool()) → addSingle drops Tool
        //   (Tool not instanceof ToolCard) → listToolInfo empty → RED
    }

    @Test
    void registerOntoExecutableResolvesViaResourceMgrByName() {
        // Use a fresh agent (still global Runner.resourceMgr, but addTool under __replan__)
        ReActAgent agent = new ReActAgent(AgentCard.builder().name("reg-dispatch").build());

        ReplanTool.registerOnto(agent);

        Object resolved = Runner.resourceMgr().getTool(ReplanTool.TOOL_NAME);

        assertThat(resolved).as("Runner.resourceMgr must resolve __replan__ → ReplanTool (id==name invariant)")
                .isInstanceOf(ReplanTool.class);
        // mutation-RED: remove .id(TOOL_NAME) on the card → ResourceMgr stores random UUID
        //   key → getTool(__replan__) returns null → RED
    }
}
