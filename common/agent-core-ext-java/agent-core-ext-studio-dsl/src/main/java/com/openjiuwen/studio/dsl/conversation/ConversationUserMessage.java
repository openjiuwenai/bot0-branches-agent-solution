/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.conversation;

import com.openjiuwen.core.foundation.llm.schema.UserMessage;

/**
 * User message with {@code enable_history} flag (Python {@code ConversationUserMessage}).
 *
 * @since 2026-08-26
 */

public class ConversationUserMessage extends UserMessage {
    private Boolean enableHistory = true;

    /**
     * ConversationUserMessage.
     *
     * @since 0.1.0
     *
     * @return result
     */

    public ConversationUserMessage() {}

    /**
     * ConversationUserMessage.
     *
     * @param content content
     * @since 0.1.0
     */

    public ConversationUserMessage(String content) {
        super(content);
    }

    /**
     * ConversationUserMessage.
     *
     * @param content content
     * @param enableHistory enableHistory
     * @since 0.1.0
     */

    public ConversationUserMessage(String content, boolean enableHistory) {
        super(content);
        this.enableHistory = enableHistory;
    }

    /**
     * getEnableHistory.
     *
     * @return result
     * @since 0.1.0
     */

    public Boolean getEnableHistory() {
        return enableHistory;
    }

    /**
     * setEnableHistory.
     *
     * @param enableHistory enableHistory
     * @since 0.1.0
     */

    public void setEnableHistory(Boolean enableHistory) {
        this.enableHistory = enableHistory;
    }
}
