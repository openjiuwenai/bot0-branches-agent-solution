/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.rail;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.AgentRail;
import com.openjiuwen.core.singleagent.schema.AgentCard;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * agent-core rail priority 排序方向的生态级承重回归（2026-08-15 GEPA 三物种裁决的固化）。
 *
 * <p><b>裁决结论</b>：agent-core（0.1.14.post1）的 callback 排序为<b>降序</b>——priority 值大者
 * 先执行，与注册顺序无关（字节码 {@code Integer.compare(y, x)} 参数交换 + 本测试行为实证双证）。
 * 真实序：UserInputCaptureRail(95) → ExploreRail(90) → PreCompletionChecklistRail(80) →
 * ProactiveConvergenceRail(70) → 默认 50 堆 → SteeringProvisionRail(1) → ObservingRail(MIN_VALUE)。
 *
 * <p><b>承重语义（mutation-RED 契约）</b>：模块内多处 rail 注释与 SteeringProvisionRail 的
 * 机制描述依赖降序方向。若未来 agent-core 升级翻转排序方向（或改为注册序），本测试是唯一
 * 报警者——剥掉方向断言后，95/90/80 的执行序假设全部失效而无人知晓。
 *
 * <p>Method: two probe rails (priority 1 vs 10) registered in both orders on a bare
 * {@link ReActAgent}; invoke throws without an LLM (expected — only the beforeInvoke
 * records matter). Content-IFF assertion: the sequence must equal the descending order
 * in BOTH registration orders (proves priority, not registration, drives execution).
 *
 * @since 2026-08
 */
class RailPriorityOrderTest {

    /** Shared execution-order record; cleared per test method. */
    static final List<String> ORDER = new ArrayList<>();

    /** Probe rail with priority 1. */
    static class ProbeRailA extends AgentRail {
        /**
         * Construct with priority 1.
         */
        ProbeRailA() {
            setPriority(1);
        }

        /**
         * Records its own execution.
         *
         * @param ctx the agent callback context (unused)
         */
        @Override
        public void beforeInvoke(AgentCallbackContext ctx) {
            ORDER.add("A(p1)");
        }
    }

    /** Probe rail with priority 10. */
    static class ProbeRailB extends AgentRail {
        /**
         * Construct with priority 10.
         */
        ProbeRailB() {
            setPriority(10);
        }

        /**
         * Records its own execution.
         *
         * @param ctx the agent callback context (unused)
         */
        @Override
        public void beforeInvoke(AgentCallbackContext ctx) {
            ORDER.add("B(p10)");
        }
    }

    /**
     * Registers A(p1) first, then B(p10) — asserts descending execution regardless of
     * registration order.
     */
    @Test
    void prioritySortsDescendingRegisterLowFirst() {
        ORDER.clear();
        ReActAgent agent = new ReActAgent(AgentCard.builder().name("probe").build());
        agent.registerRail(new ProbeRailA());
        agent.registerRail(new ProbeRailB());
        try {
            agent.invoke("hi", null); // no LLM configured — exception is expected, ignored
        } catch (Exception expected) {
            // we only need the beforeInvoke execution records
        }
        assertThat(ORDER)
                .as("priority 10 must fire before priority 1 (descending), registration order A→B")
                .containsExactly("B(p10)", "A(p1)");
    }

    /**
     * Registers B(p10) first, then A(p1) (reverse control) — the sequence must be identical,
     * proving priority (not registration order) drives execution.
     */
    @Test
    void prioritySortsDescendingRegisterHighFirst() {
        ORDER.clear();
        ReActAgent agent = new ReActAgent(AgentCard.builder().name("probe").build());
        agent.registerRail(new ProbeRailB());
        agent.registerRail(new ProbeRailA());
        try {
            agent.invoke("hi", null); // no LLM configured — exception is expected, ignored
        } catch (Exception expected) {
            // we only need the beforeInvoke execution records
        }
        assertThat(ORDER)
                .as("priority 10 must fire before priority 1 (descending), registration order B→A")
                .containsExactly("B(p10)", "A(p1)");
    }
}
