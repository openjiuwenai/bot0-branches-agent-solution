/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.testsupport;

import com.openjiuwen.core.context.ContextStats;
import com.openjiuwen.core.context.ContextWindow;
import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.context.token.TokenCounter;
import com.openjiuwen.core.foundation.llm.schema.BaseMessage;
import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.foundation.tool.schema.ToolInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Minimal {@link ModelContext} for extractor parity tests (no Mockito). */
public final class StubModelContext extends ModelContext {
    private final List<BaseMessage> messages;

    public StubModelContext(BaseMessage... messages) {
        this.messages = new ArrayList<>(List.of(messages));
    }

    public StubModelContext(List<BaseMessage> messages) {
        this.messages = new ArrayList<>(messages);
    }

    @Override
    public int size() {
        return messages.size();
    }

    @Override
    public List<BaseMessage> getMessages(Integer windowSize, boolean withHistory) {
        return List.copyOf(messages);
    }

    @Override
    public void setMessages(List<BaseMessage> newMessages, boolean withHistory) {
        messages.clear();
        messages.addAll(newMessages);
    }

    @Override
    public List<BaseMessage> popMessages(int size, boolean withHistory) {
        throw new UnsupportedOperationException("stub");
    }

    @Override
    public void clearMessages(boolean withHistory) {
        messages.clear();
    }

    @Override
    public List<BaseMessage> addMessages(List<BaseMessage> msgs) {
        messages.addAll(msgs);
        return msgs;
    }

    @Override
    public ContextWindow getContextWindow(
            List<BaseMessage> systemMessages,
            List<ToolInfo> tools,
            Integer windowSize,
            Integer dialogueRound,
            Map<String, Object> kwargs) {
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
