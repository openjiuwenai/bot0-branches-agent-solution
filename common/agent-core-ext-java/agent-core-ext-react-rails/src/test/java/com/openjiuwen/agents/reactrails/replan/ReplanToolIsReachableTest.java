/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.replan;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.ModelCallInputs;
import com.openjiuwen.core.singleagent.schema.AgentCard;

import org.junit.jupiter.api.Test;

/**
 * issue-#16 Phase1 B 承重测试 — {@link ReplanTool#isReachable(AgentCallbackContext)} 双向 IFF。
 *
 * <p>rail 在注入 "{@code call __replan__}" prompt 前用此探针决定是否提工具名：注册了才提，
 * 没注册则 fallback（tool-agnostic），消灭"prompt 引导 LLM 调不存在的工具"silent 悬空。
 *
 * <p>三向覆盖：
 * <ol>
 *   <li><b>registerOnto 后 → true</b>（visibility 通道：listToolInfo 含 {@code __replan__}）。
 *       mutation-RED: 剥 isReachable 的 listToolInfo 检查 → 恒 false → RED。</li>
 *   <li><b>未 registerOnto → false</b>（issue #16 的 silent 悬空场景，探针必须返 false 触发 fallback）。
 *       mutation-RED: isReachable 硬编码 return true → RED。</li>
 *   <li><b>mock ctx（非 BaseAgent agent）→ false</b>（诚实降级，生产 registerRail 永远传 BaseAgent）。</li>
 * </ol>
 *
 * <p><b>诚实边界</b>：isReachable 只探 visibility 通道（per-agent listToolInfo，稳定）。
 * dispatch 通道（Runner.resourceMgr 全局单例）由 registerOnto 原子保证，不纳入探针（全局
 * resourceMgr 跨测试残留污染风险，且 registerOnto 已使 dispatch 与 visibility 同生共死）。
 * 误用 add(card) 漏 addTool 的 dispatch 盲区见 ReplanTool 类 javadoc 文档警告。
 *
 * @since 2026-07
 */
class ReplanToolIsReachableTest {
    @Test
    void isReachableTrueAfterRegisterOnto() {
        ReActAgent agent = new ReActAgent(AgentCard.builder().name("reach-yes").build());
        ReplanTool.registerOnto(agent);
        AgentCallbackContext ctx = ctxFor(agent);

        assertThat(ReplanTool.isReachable(ctx))
                .as("registerOnto → __replan__ visible in AbilityManager → isReachable true")
                .isTrue();
        // mutation-RED: strip the listToolInfo anyMatch in isReachable → returns false → RED
    }

    @Test
    void isReachableFalseWhenNotRegistered() {
        ReActAgent agent = new ReActAgent(AgentCard.builder().name("reach-no").build());
        // No registerOnto → __replan__ not in AbilityManager → the issue-#16 silent-dangle scenario.
        AgentCallbackContext ctx = ctxFor(agent);

        assertThat(ReplanTool.isReachable(ctx))
                .as("no registerOnto → isReachable false (must trigger tool-agnostic fallback, not silent dangle)")
                .isFalse();
        // mutation-RED: hardcode isReachable to return true → this isFalse RED
    }

    @Test
    void isReachableFalseForMockCtxWithoutRealAgent() {
        // Mock/test ctx carries a bare Object as agent (not a BaseAgent) — isReachable must
        // honestly return false rather than NPE on the cast. Production registerRail always
        // passes a BaseAgent (AgentCallbackManager.registerRail offset 155 instanceof, javap-proven).
        AgentCallbackContext ctx = AgentCallbackContext.builder().agent(new Object())
                .inputs(new ModelCallInputs()).build();

        assertThat(ReplanTool.isReachable(ctx))
                .as("mock ctx (non-BaseAgent agent) → isReachable false (honest degradation, no NPE)")
                .isFalse();
    }

    private static AgentCallbackContext ctxFor(ReActAgent agent) {
        return AgentCallbackContext.builder().agent(agent).inputs(new ModelCallInputs()).build();
    }
}
