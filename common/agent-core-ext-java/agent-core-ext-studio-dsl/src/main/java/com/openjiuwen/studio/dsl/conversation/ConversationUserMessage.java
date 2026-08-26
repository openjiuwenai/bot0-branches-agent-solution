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

    public ConversationUserMessage() {}

    public ConversationUserMessage(String content) {
        super(content);
    }

    public ConversationUserMessage(String content, boolean enableHistory) {
        super(content);
        this.enableHistory = enableHistory;
    }

    public Boolean getEnableHistory() {
        return enableHistory;
    }

    public void setEnableHistory(Boolean enableHistory) {
        this.enableHistory = enableHistory;
    }
}
