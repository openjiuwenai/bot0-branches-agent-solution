/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.rail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.core.context.ContextStats;
import com.openjiuwen.core.context.ContextWindow;
import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.context.token.TokenCounter;
import com.openjiuwen.core.foundation.llm.schema.BaseMessage;
import com.openjiuwen.core.foundation.llm.schema.SystemMessage;
import com.openjiuwen.core.foundation.llm.schema.UserMessage;
import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.foundation.tool.schema.ToolInfo;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * UserInputCaptureRail bearing tests.
 *
 * <p>承重缺陷 D 治本验证: ExploreTool 依赖 userInput supplier,
 * 此 rail 在 beforeModelCall 填充它。
 *
 * <p>mutation-RED:
 * <ul>
 *   <li>Strip supplier.set() → supplier stays null → ExploreTool gets no_input → RED</li>
 *   <li>Strip captured flag → re-captures every round (harmless but wasteful) → RED</li>
 * </ul>
 *
 * @since 2026-07
 */
class UserInputCaptureRailTest {
    @Test
    void capturesFirstUserMessage() {
        AtomicReference<String> ref = new AtomicReference<>();
        UserInputCaptureRail rail = new UserInputCaptureRail(ref);

        List<BaseMessage> messages = new ArrayList<>();
        messages.add(new SystemMessage("system"));
        messages.add(new UserMessage("分析A股走势"));

        AgentCallbackContext ctx = ctxWithMessages(messages);
        rail.beforeModelCall(ctx);

        assertThat(ref.get()).as("supplier must contain the first UserMessage content").isEqualTo("分析A股走势");
        assertThat(rail.isCaptured()).isTrue();
    }

    @Test
    void capturesWhenNoSystemMessage() {
        AtomicReference<String> ref = new AtomicReference<>();
        UserInputCaptureRail rail = new UserInputCaptureRail(ref);

        List<BaseMessage> messages = new ArrayList<>();
        messages.add(new UserMessage("直接的用户输入"));

        rail.beforeModelCall(ctxWithMessages(messages));

        assertThat(ref.get()).as("must find UserMessage even without SystemMessage prefix").isEqualTo("直接的用户输入");
    }

    @Test
    void doesNotRecaptureAfterFirstSuccess() {
        AtomicReference<String> ref = new AtomicReference<>();
        UserInputCaptureRail rail = new UserInputCaptureRail(ref);

        List<BaseMessage> messages = new ArrayList<>();
        messages.add(new UserMessage("第一次"));

        AgentCallbackContext ctx = ctxWithMessages(messages);
        rail.beforeModelCall(ctx);
        assertThat(ref.get()).isEqualTo("第一次");

        ref.set("被覆盖了");
        rail.beforeModelCall(ctx);

        assertThat(ref.get()).as("second beforeModelCall must NOT overwrite (captured=true)").isEqualTo("被覆盖了");
    }

    @Test
    void nullContextNoCrash() {
        AtomicReference<String> ref = new AtomicReference<>();
        UserInputCaptureRail rail = new UserInputCaptureRail(ref);

        AgentCallbackContext ctx = AgentCallbackContext.builder().agent(new Object()).build();
        ctx.setExtra(new LinkedHashMap<>());

        rail.beforeModelCall(ctx);

        assertThat(ref.get()).as("null context must not crash, supplier stays null").isNull();
        assertThat(rail.isCaptured()).isFalse();
    }

    @Test
    void emptyMessagesNoCrash() {
        AtomicReference<String> ref = new AtomicReference<>();
        UserInputCaptureRail rail = new UserInputCaptureRail(ref);

        rail.beforeModelCall(ctxWithMessages(new ArrayList<>()));

        assertThat(ref.get()).isNull();
        assertThat(rail.isCaptured()).isFalse();
    }

    @Test
    void nullRefRejected() {
        assertThatThrownBy(() -> new UserInputCaptureRail(null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userInputRef");
    }

    @Test
    void priorityIsNinetyFive() {
        UserInputCaptureRail rail = new UserInputCaptureRail(new AtomicReference<>());
        assertThat(rail.getPriority()).as("priority 95 fires before ExploreRail(90) and Checklist(80)").isEqualTo(95);
    }

    private static AgentCallbackContext ctxWithMessages(List<BaseMessage> messages) {
        StubContext ctx = new StubContext(messages);
        AgentCallbackContext callbackCtx = AgentCallbackContext.builder().agent(new Object()).context(ctx).build();
        callbackCtx.setExtra(new LinkedHashMap<>());
        return callbackCtx;
    }

    static class StubContext extends ModelContext {
        final List<BaseMessage> messages;

        StubContext(List<BaseMessage> messages) {
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
            throw new UnsupportedOperationException();
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
            throw new UnsupportedOperationException();
        }

        @Override
        public ContextStats statistic() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String sessionId() {
            return "test";
        }

        @Override
        public String contextId() {
            return "test";
        }

        @Override
        public TokenCounter tokenCounter() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Tool reloaderTool() {
            throw new UnsupportedOperationException();
        }
    }
}
