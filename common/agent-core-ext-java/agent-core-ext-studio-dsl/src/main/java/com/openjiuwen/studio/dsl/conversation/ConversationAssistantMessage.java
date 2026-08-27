/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.conversation;

import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;

/**
 * Assistant message with {@code enable_history} flag (Python {@code ConversationAssistantMessage}).
 *
 * @since 2026-08-26
 */

public class ConversationAssistantMessage extends AssistantMessage {
    private Boolean enableHistory = true;

    /**
     * ConversationAssistantMessage.
     *
     * @since 0.1.0
     *
     * @return result
     */

    public ConversationAssistantMessage() {}

    /**
     * ConversationAssistantMessage.
     *
     * @param content content
     * @since 0.1.0
     */

    public ConversationAssistantMessage(String content) {
        super(content);
    }

    /**
     * ConversationAssistantMessage.
     *
     * @param content content
     * @param enableHistory enableHistory
     * @since 0.1.0
     */

    public ConversationAssistantMessage(String content, boolean enableHistory) {
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
