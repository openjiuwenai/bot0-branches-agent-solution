/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.replan;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.core.context.ContextStats;
import com.openjiuwen.core.context.ContextWindow;
import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.context.token.TokenCounter;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.BaseMessage;
import com.openjiuwen.core.foundation.llm.schema.SystemMessage;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.foundation.llm.schema.UserMessage;
import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.foundation.tool.schema.ToolInfo;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.ModelCallInputs;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HistoryCompressorRail 承重测试 — mock ModelContext 证 replan 触发上下文压缩。
 *
 * <p>mutation-RED:
 * <ul>
 *   <li>strip setMessages(compact, false) → 上下文保留全量消息 → assertHasSize(4) → RED</li>
 *   <li>strip hasReplan detection → 即使 __replan__ 调用也不压缩 → context unchanged → RED</li>
 *   <li>strip compressor.apply(segment) → summary 空/null → 断言摘要内容 → RED</li>
 * </ul>
 */
class HistoryCompressorRailTest {
    @Test
    void replanTriggeredCompressesMessages() {
        // Context: [System, User(query), Assistant(search), User(result), Assistant(__replan__)]
        List<BaseMessage> initial = new ArrayList<>();
        initial.add(new SystemMessage("You are a helpful assistant."));
        initial.add(new UserMessage("Solve this problem"));
        initial.add(buildAssistantWithToolCall("search_tool", "{\"q\":\"query1\"}"));
        initial.add(new UserMessage("search_tool returned: result1"));
        AssistantMessage replanMsg = buildAssistantWithToolCall(ReplanTool.TOOL_NAME,
                "{\"replan_reason\":\"wrong direction\"}");
        initial.add(replanMsg);

        StubModelContext context = new StubModelContext(initial);

        ModelCallInputs inputs = new ModelCallInputs();
        inputs.setResponse(replanMsg);

        AgentCallbackContext ctx = AgentCallbackContext.builder().agent(new Object()).event(null).inputs(inputs)
                .context(context).build();

        HistoryCompressorRail rail = new HistoryCompressorRail();
        rail.afterModelCall(ctx);
        // After compression: [System, User(query), User(summary), Assistant(__replan__)]
        List<BaseMessage> compressed = context.getMessages();
        assertThat(compressed).as("context must be compressed from 5 to 4 messages").hasSize(4);

        assertThat(compressed.get(0)).as("index 0 must be SystemMessage (preserved)").isInstanceOf(SystemMessage.class);

        assertThat(compressed.get(1)).as("index 1 must be UserMessage(initial query, preserved)")
                .isInstanceOf(UserMessage.class);
        assertThat(compressed.get(1).getContentAsString()).as("initial query content must be preserved")
                .contains("Solve this problem");

        assertThat(compressed.get(2)).as("index 2 must be UserMessage(summary) — compressed segment")
                .isInstanceOf(UserMessage.class);
        assertThat(compressed.get(2).getContentAsString()).as("summary must contain [尝试摘要] prefix").contains("[尝试摘要]");
        assertThat(compressed.get(2).getContentAsString())
                .as("summary must contain the tool-call extracted from the compressed segment").contains("search_tool");

        assertThat(compressed.get(3)).as("index 3 must be the current __replan__ AssistantMessage (preserved)")
                .isSameAs(replanMsg);

        assertThat(rail.lastBoundary(ctx)).as("lastBoundary must be updated to compact.size() - 1 = 3").isEqualTo(3);
        // mutation-RED: strip setMessages(compact, false) → context unchanged (size 5) → RED
    }

    @Test
    void noReplanToolCallNoCompression() {
        List<BaseMessage> initial = new ArrayList<>();
        initial.add(new SystemMessage("system"));
        initial.add(new UserMessage("query"));
        AssistantMessage normalMsg = buildAssistantWithToolCall("other_tool", "{}");
        initial.add(normalMsg);

        StubModelContext context = new StubModelContext(initial);

        ModelCallInputs inputs = new ModelCallInputs();
        // Response is NOT __replan__ — no compression should happen
        AssistantMessage finalAnswer = new AssistantMessage("Here is the answer.");
        inputs.setResponse(finalAnswer);

        AgentCallbackContext ctx = AgentCallbackContext.builder().agent(new Object()).event(null).inputs(inputs)
                .context(context).build();

        HistoryCompressorRail rail = new HistoryCompressorRail();
        rail.afterModelCall(ctx);

        assertThat(context.getMessages()).as("no __replan__ → context unchanged").hasSize(3);
        assertThat(rail.lastBoundary(ctx)).as("no compression → lastBoundary stays 0").isEqualTo(0);
        // mutation-RED: remove hasReplan check → compresses unconditionally → RED
    }

    @Test
    void multipleReplansAccumulativeCompression() {
        // Round 1 context: [System, User(query), Assistant(step1), User(result1), Assistant(__replan__)]
        List<BaseMessage> round1 = new ArrayList<>();
        round1.add(new SystemMessage("system"));
        round1.add(new UserMessage("query"));
        round1.add(buildAssistantWithToolCall("step1_tool", "{\"arg\":\"v1\"}"));
        round1.add(new UserMessage("step1 result"));
        AssistantMessage replan1 = buildAssistantWithToolCall(ReplanTool.TOOL_NAME,
                "{\"replan_reason\":\"stuck at step1\"}");
        round1.add(replan1);

        StubModelContext context = new StubModelContext(round1);

        ModelCallInputs inputs1 = new ModelCallInputs();
        inputs1.setResponse(replan1);
        Map<String, Object> invocationExtra = new LinkedHashMap<>();

        AgentCallbackContext ctx1 = AgentCallbackContext.builder().agent(new Object()).event(null).inputs(inputs1)
                .context(context).extra(invocationExtra).build();

        HistoryCompressorRail rail = new HistoryCompressorRail();
        rail.afterModelCall(ctx1);

        // After round 1: [System, User(query), User(summary1), Assistant(__replan__)]
        assertThat(rail.lastBoundary(ctx1)).isEqualTo(3);
        assertThat(context.getMessages()).hasSize(4);
        assertThat(context.getMessages().get(2).getContentAsString()).as("round 1 summary must contain step1_tool")
                .contains("step1_tool");

        // Round 2: append new messages after compressed context, then fire __replan__ again
        // Current context: [System(0), User(query)(1), User(summary1)(2),
        // Assistant(__replan__)(3)]
        // Append: User(tool-result) at [4], Assistant(new-attempt) at [5],
        // User(result) at [6], Assistant(__replan__) at [7]
        context.messages.add(new UserMessage("round2 step1 result"));
        context.messages.add(buildAssistantWithToolCall("round2_tool", "{\"arg\":\"v2\"}"));
        context.messages.add(new UserMessage("round2 result"));
        AssistantMessage replan2 = buildAssistantWithToolCall(ReplanTool.TOOL_NAME,
                "{\"replan_reason\":\"stuck at round2\"}");
        context.messages.add(replan2);

        ModelCallInputs inputs2 = new ModelCallInputs();
        inputs2.setResponse(replan2);

        AgentCallbackContext ctx2 = AgentCallbackContext.builder().agent(new Object()).event(null).inputs(inputs2)
                .context(context).extra(invocationExtra).build();

        rail.afterModelCall(ctx2);

        // After round 2: [System, User(query), User(summary1), User(summary2), Assistant(__replan__)]
        assertThat(rail.lastBoundary(ctx2)).isEqualTo(4);
        assertThat(context.getMessages()).hasSize(5);

        // Round1 summary preserved at index 2
        assertThat(context.getMessages().get(2).getContentAsString())
                .as("round 1 summary must be preserved after round 2 compression").contains("step1_tool");
        // Round2 compressed the segment (index 3 through 6) → index 3 is now summary2
        assertThat(context.getMessages().get(3).getContentAsString()).as("round 2 summary must contain round2_tool")
                .contains("round2_tool");
        // mutation-RED: reset lastBoundary to 0 on each call → re-compresses from index 1
        // → summary1 lost, round1 segment would be restored (impossible without setMessages) → RED
    }

    @Test
    void customCompressorApplied() {
        List<BaseMessage> initial = new ArrayList<>();
        initial.add(new SystemMessage("system"));
        initial.add(new UserMessage("query"));
        initial.add(buildAssistantWithToolCall("a_tool", "{}"));
        initial.add(new UserMessage("a result"));
        AssistantMessage replanMsg = buildAssistantWithToolCall(ReplanTool.TOOL_NAME, "{\"replan_reason\":\"test\"}");
        initial.add(replanMsg);

        StubModelContext context = new StubModelContext(initial);

        ModelCallInputs inputs = new ModelCallInputs();
        inputs.setResponse(replanMsg);

        AgentCallbackContext ctx = AgentCallbackContext.builder().agent(new Object()).event(null).inputs(inputs)
                .context(context).build();

        HistoryCompressorRail rail = new HistoryCompressorRail(
                segment -> "CUSTOM_SUMMARY: " + segment.size() + " messages compressed");
        rail.afterModelCall(ctx);

        assertThat(context.getMessages()).hasSize(4);
        assertThat(context.getMessages().get(2).getContentAsString())
                .as("custom compressor output must appear in summary message").contains("CUSTOM_SUMMARY");
        assertThat(context.getMessages().get(2).getContentAsString())
                .as("custom compressor counted 2 messages in segment (Assistant+User)")
                .contains("2 messages compressed");
        // mutation-RED: ignore compressor function → default summary → CUSTOM_SUMMARY not present → RED
    }

    @Test
    void compressionBoundaryDoesNotCrossInvocationContexts() {
        HistoryCompressorRail rail = new HistoryCompressorRail();

        List<BaseMessage> firstMessages = new ArrayList<>();
        firstMessages.add(new SystemMessage("system"));
        firstMessages.add(new UserMessage("first query"));
        firstMessages.add(buildAssistantWithToolCall("first_tool", "{}"));
        firstMessages.add(new UserMessage("first result"));
        AssistantMessage firstReplan = buildAssistantWithToolCall(ReplanTool.TOOL_NAME, "{}");
        firstMessages.add(firstReplan);
        StubModelContext firstContext = new StubModelContext(firstMessages);
        ModelCallInputs firstInputs = new ModelCallInputs();
        firstInputs.setResponse(firstReplan);
        AgentCallbackContext firstInvocation = AgentCallbackContext.builder().agent(new Object()).inputs(firstInputs)
                .context(firstContext).build();
        rail.afterModelCall(firstInvocation);

        List<BaseMessage> secondMessages = new ArrayList<>();
        secondMessages.add(new SystemMessage("system"));
        secondMessages.add(new UserMessage("second query"));
        secondMessages.add(buildAssistantWithToolCall("second_tool", "{}"));
        AssistantMessage secondReplan = buildAssistantWithToolCall(ReplanTool.TOOL_NAME, "{}");
        secondMessages.add(secondReplan);
        StubModelContext secondContext = new StubModelContext(secondMessages);
        ModelCallInputs secondInputs = new ModelCallInputs();
        secondInputs.setResponse(secondReplan);
        AgentCallbackContext secondInvocation = AgentCallbackContext.builder().agent(new Object()).inputs(secondInputs)
                .context(secondContext).build();

        rail.afterModelCall(secondInvocation);

        assertThat(secondContext.getMessages().get(2)).as("a fresh invocation must compress from boundary zero")
                .isInstanceOf(UserMessage.class);
        assertThat(secondContext.getMessages().get(2).getContentAsString()).contains("[尝试摘要]").contains("second_tool");
    }

    private static AssistantMessage buildAssistantWithToolCall(String toolName, String args) {
        ToolCall tc = new ToolCall();
        tc.setId("call-" + System.nanoTime());
        tc.setName(toolName);
        tc.setArguments(args);
        AssistantMessage msg = new AssistantMessage();
        msg.setToolCalls(List.of(tc));
        return msg;
    }

    /**
     * Minimal stub ModelContext that stores messages in a simple list.
     * Only implements methods needed by HistoryCompressorRail.afterModelCall:
     * getMessages(), setMessages(List, boolean).
     * All other abstract methods throw UnsupportedOperationException.
     */
    static class StubModelContext extends ModelContext {
        final List<BaseMessage> messages;

        StubModelContext(List<BaseMessage> messages) {
            this.messages = new ArrayList<>(messages);
        }

        @Override
        public int size() {
            return messages.size();
        }

        @Override
        public List<BaseMessage> getMessages(Integer windowSize, boolean isSortDesc) {
            return messages;
        }

        @Override
        public void setMessages(List<BaseMessage> newMessages, boolean isSortDesc) {
            messages.clear();
            messages.addAll(newMessages);
        }

        @Override
        public List<BaseMessage> popMessages(int n, boolean isSortDesc) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public void clearMessages(boolean isSortDesc) {
            messages.clear();
        }

        @Override
        public List<BaseMessage> addMessages(List<BaseMessage> msgs) {
            messages.addAll(msgs);
            return msgs;
        }

        @Override
        public ContextWindow getContextWindow(List<BaseMessage> msgs, List<ToolInfo> tools, Integer windowSize,
                Integer overlap, Map<String, Object> kwargs) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public ContextStats statistic() {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public String sessionId() {
            return "test-session";
        }

        @Override
        public String contextId() {
            return "test-context";
        }

        @Override
        public TokenCounter tokenCounter() {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public Tool reloaderTool() {
            throw new UnsupportedOperationException("stub");
        }
    }
}
