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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

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
 * records matter, captured through a FutureTask bridge). Content-IFF assertion: the
 * sequence must equal the descending order in BOTH registration orders (proves
 * priority, not registration, drives execution).
 *
 * @since 2026-08
 */
class RailPriorityOrderTest {
    /** Per-test execution-order record (JUnit creates a fresh instance per method). */
    private final List<String> order = new ArrayList<>();

    /** Probe rail with priority 1, recording into the injected list. */
    static class ProbeRailA extends AgentRail {
        private final List<String> sink;

        /**
         * Construct with priority 1, recording executions into the given sink.
         *
         * @param sink execution-order record (written on each beforeInvoke)
         */
        ProbeRailA(List<String> sink) {
            this.sink = sink;
            setPriority(1);
        }

        /**
         * Records its own execution.
         *
         * @param ctx the agent callback context (unused)
         */
        @Override
        public void beforeInvoke(AgentCallbackContext ctx) {
            sink.add("A(p1)");
        }
    }

    /** Probe rail with priority 10, recording into the injected list. */
    static class ProbeRailB extends AgentRail {
        private final List<String> sink;

        /**
         * Construct with priority 10, recording executions into the given sink.
         *
         * @param sink execution-order record (written on each beforeInvoke)
         */
        ProbeRailB(List<String> sink) {
            this.sink = sink;
            setPriority(10);
        }

        /**
         * Records its own execution.
         *
         * @param ctx the agent callback context (unused)
         */
        @Override
        public void beforeInvoke(AgentCallbackContext ctx) {
            sink.add("B(p10)");
        }
    }

    /**
     * Registers A(p1) first, then B(p10) — asserts descending execution regardless of
     * registration order.
     */
    @Test
    void prioritySortsDescendingRegisterLowFirst() {
        ReActAgent agent = new ReActAgent(AgentCard.builder().name("probe").build());
        agent.registerRail(new ProbeRailA(order));
        agent.registerRail(new ProbeRailB(order));
        invokeExpectingFailure(agent);
        assertThat(order)
                .as("priority 10 must fire before priority 1 (descending), registration order A→B")
                .containsExactly("B(p10)", "A(p1)");
    }

    /**
     * Registers B(p10) first, then A(p1) (reverse control) — the sequence must be identical,
     * proving priority (not registration order) drives execution.
     */
    @Test
    void prioritySortsDescendingRegisterHighFirst() {
        ReActAgent agent = new ReActAgent(AgentCard.builder().name("probe").build());
        agent.registerRail(new ProbeRailB(order));
        agent.registerRail(new ProbeRailA(order));
        invokeExpectingFailure(agent);
        assertThat(order)
                .as("priority 10 must fire before priority 1 (descending), registration order B→A")
                .containsExactly("B(p10)", "A(p1)");
    }

    /**
     * Invokes without an LLM configured through a FutureTask bridge so the expected
     * no-LLM failure surfaces as {@code ExecutionException} — only the already-recorded
     * beforeInvoke executions matter, the failure itself is irrelevant.
     *
     * @param agent the probe agent (two rails registered, no LLM)
     */
    private static void invokeExpectingFailure(ReActAgent agent) {
        FutureTask<Object> invocation = new FutureTask<>(() -> agent.invoke("hi", null));
        invocation.run();
        try {
            invocation.get();
        } catch (InterruptedException | ExecutionException expected) {
            // no LLM configured — both beforeInvoke hooks have already run at this point
        }
    }
}
