/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.loopforest.bench;

import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.ModelCallInputs;
import com.openjiuwen.core.singleagent.rail.ToolCallInputs;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BenchContextRail 离线承重——摘要通道/成对逐出/去重（不发 LLM）。
 *
 * @since 2026-08
 */
class BenchContextRailTest {

    private static AgentCallbackContext ctx(com.openjiuwen.core.singleagent.rail.EventInputs inputs, Map<String, Object> extra) {
        return AgentCallbackContext.builder()
                .agent(new Object()).event(null).inputs(inputs).extra(extra).build();
    }

    @Test
    void readToolResultFeedsDigestAndPinAppears() {
        V2BenchE2eTest.BenchContextRail rail = new V2BenchE2eTest.BenchContextRail("SYS", "INJ");
        // ① read_file 工具结果 → afterToolCall 更新摘要
        ToolCallInputs ti = ToolCallInputs.builder()
                .toolName("read_file")
                .toolArgs(Map.of("path", "arm_a5/registry.json"))
                .toolResult(Map.of("path", "arm_a5/registry.json",
                        "text", "# station table\nst-01 primary daily", "truncated", false))
                .build();
        rail.afterToolCall(ctx(ti, new HashMap<>()));

        // ② 下一轮模型调用 → 摘要钉进消息
        ModelCallInputs mi = ModelCallInputs.builder()
                .messages(new java.util.ArrayList<>(List.of(
                        new com.openjiuwen.core.foundation.llm.schema.UserMessage("TASK"))))
                .build();
        rail.beforeModelCall(ctx(mi, new HashMap<>()));
        java.util.List<Object> msgs = mi.getMessages();
        String injected = msgs.stream()
                .map(m -> String.valueOf(m instanceof
                        com.openjiuwen.core.foundation.llm.schema.BaseMessage bm
                        ? bm.getContent() : m))
                .filter(s -> s.startsWith("[File digests]"))
                .findFirst().orElse("");
        assertThat(injected).as("read 后下一轮必须出现摘要钉").isNotBlank();
        assertThat(String.valueOf(injected)).contains("arm_a5/registry.json").contains("st-01");
    }

    @Test
    void digestPinReplacesNotAccumulates() {
        V2BenchE2eTest.BenchContextRail rail = new V2BenchE2eTest.BenchContextRail("SYS", "INJ");
        ToolCallInputs ti = ToolCallInputs.builder().toolName("read_file")
                .toolArgs(Map.of("path", "a.csv"))
                .toolResult(Map.of("path", "a.csv", "text", "content-a", "truncated", false))
                .build();
        rail.afterToolCall(ctx(ti, new HashMap<>()));
        ModelCallInputs mi1 = ModelCallInputs.builder()
                .messages(new java.util.ArrayList<>(List.of(
                        new com.openjiuwen.core.foundation.llm.schema.UserMessage("T"))))
                .build();
        rail.beforeModelCall(ctx(mi1, new HashMap<>()));
        // 第二轮：同样的消息序列（含旧钉）→ 应替换不累积
        ModelCallInputs mi2 = ModelCallInputs.builder()
                .messages(new java.util.ArrayList<>(mi1.getMessages()))
                .build();
        rail.beforeModelCall(ctx(mi2, new HashMap<>()));
        java.util.List<Object> msgs2 = mi2.getMessages();
        long pins = msgs2.stream()
                .map(m -> String.valueOf(m instanceof
                        com.openjiuwen.core.foundation.llm.schema.BaseMessage bm
                        ? bm.getContent() : m))
                .filter(s -> s.startsWith("[File digests]")).count();
        assertThat(pins).as("摘要钉恰好一块（替换不累积）").isEqualTo(1);
    }

@Test
    void midCourseTextRoundTriggersContinuationSteering() {
        V2BenchE2eTest.BenchContextRail rail = new V2BenchE2eTest.BenchContextRail("SYS", "INJ");
        ReAnchorRailTest.CapturingQueue queue = new ReAnchorRailTest.CapturingQueue();
        var am = new com.openjiuwen.core.foundation.llm.schema.AssistantMessage();
        am.setContent("I'll examine the data files to gather station info.");
        ModelCallInputs mi = ModelCallInputs.builder()
                .messages(new java.util.ArrayList<>(List.of(
                        new com.openjiuwen.core.foundation.llm.schema.UserMessage("T"))))
                .response(am)
                .build();
        AgentCallbackContext c = AgentCallbackContext.builder()
                .agent(new Object()).event(null).inputs(mi)
                .extra(new java.util.HashMap<>()).steeringQueue(queue).build();
        rail.afterModelCall(c);
        org.assertj.core.api.Assertions.assertThat(queue.pushed)
                .as("中途文本轮（无 tool_calls 无 DONE）必须触发续跑 steering")
                .hasSize(1);
        org.assertj.core.api.Assertions.assertThat(queue.pushed.get(0)).contains("Continue");
    }
}
