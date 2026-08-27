/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.testsupport;

import com.openjiuwen.core.context.ContextStats;
import com.openjiuwen.core.context.ContextWindow;
import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.context.token.TokenCounter;
import com.openjiuwen.core.foundation.llm.schema.BaseMessage;
import com.openjiuwen.core.foundation.tool.schema.ToolInfo;
import com.openjiuwen.core.foundation.tool.Tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Minimal {@link ModelContext} for extractor parity tests (no Mockito).
 *
 * @since 0.1.0 (2026)
 */

public final class StubModelContext extends ModelContext {
    private final List<BaseMessage> messages;

    public StubModelContext(BaseMessage... messages) {
        this.messages = new ArrayList<>(List.of(messages));
    }
    public StubModelContext(List<BaseMessage> messages) {
        this.messages = new ArrayList<>(messages);
    }

    /**
     * size.
     *
     * @return result
     * @since 0.1.0
     */

    @Override
    public int size() {
        return messages.size();
    }

    /**
     * getMessages.
     *
     * @param windowSize windowSize
     * @param withHistory withHistory
     * @return result
     * @since 0.1.0
     */

    @Override
    public List<BaseMessage> getMessages(Integer windowSize, boolean withHistory) {
        return List.copyOf(messages);
    }

    /**
     * setMessages.
     *
     * @param newMessages newMessages
     * @param withHistory withHistory
     * @since 0.1.0
     */

    @Override
    public void setMessages(List<BaseMessage> newMessages, boolean withHistory) {
        messages.clear();
        messages.addAll(newMessages);
    }

    /**
     * popMessages.
     *
     * @param size size
     * @param withHistory withHistory
     * @return result
     * @since 0.1.0
     */

    @Override
    public List<BaseMessage> popMessages(int size, boolean withHistory) {
        throw new UnsupportedOperationException("stub");
    }

    /**
     * clearMessages.
     *
     * @param withHistory withHistory
     * @since 0.1.0
     */

    @Override
    public void clearMessages(boolean withHistory) {
        messages.clear();
    }

    /**
     * addMessages.
     *
     * @param msgs msgs
     * @return result
     * @since 0.1.0
     */

    @Override
    public List<BaseMessage> addMessages(List<BaseMessage> msgs) {
        messages.addAll(msgs);
        return msgs;
    }

    /**
     * getContextWindow.
     *
     * @param systemMessages systemMessages
     * @param tools tools
     * @param windowSize windowSize
     * @param dialogueRound dialogueRound
     * @param kwargs kwargs
     * @return result
     * @since 0.1.0
     */

    @Override
    public ContextWindow getContextWindow(
            List<BaseMessage> systemMessages,
            List<ToolInfo> tools,
            Integer windowSize,
            Integer dialogueRound,
            Map<String, Object> kwargs) {
        throw new UnsupportedOperationException("stub");
    }

    /**
     * statistic.
     *
     * @return result
     * @since 0.1.0
     */

    @Override
    public ContextStats statistic() {
        throw new UnsupportedOperationException("stub");
    }

    /**
     * sessionId.
     *
     * @return result
     * @since 0.1.0
     */

    @Override
    public String sessionId() {
        return "test-session";
    }

    /**
     * contextId.
     *
     * @return result
     * @since 0.1.0
     */

    @Override
    public String contextId() {
        return "test-context";
    }

    /**
     * tokenCounter.
     *
     * @return result
     * @since 0.1.0
     */

    @Override
    public TokenCounter tokenCounter() {
        throw new UnsupportedOperationException("stub");
    }

    /**
     * reloaderTool.
     *
     * @return result
     * @since 0.1.0
     */

    @Override
    public Tool reloaderTool() {
        throw new UnsupportedOperationException("stub");
    }
}
