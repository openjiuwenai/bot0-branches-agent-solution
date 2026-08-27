/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.conversation;

import com.openjiuwen.core.foundation.llm.schema.BaseMessage;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads {@code enable_history} from context messages (Python {@code getattr(msg, "enable_history", True)}).
 *
 * @since 2026-08-26
 */

public final class MessageHistorySupport {
    private MessageHistorySupport() {}

    /**
     * readEnableHistory.
     *
     * @param msg msg
     * @return result
     * @since 0.1.0
     */

    public static boolean readEnableHistory(BaseMessage msg) {
        if (msg == null) {
        return true;
    }
        if (msg instanceof ConversationUserMessage user && user.getEnableHistory() != null) {
            return user.getEnableHistory();
        }
        if (msg instanceof ConversationAssistantMessage assistant && assistant.getEnableHistory() != null) {
            return assistant.getEnableHistory();
        }
        Map<String, Object> metadata = msg.getMetadata();
        if (metadata != null) {
            Object raw = metadata.get("enable_history");
            if (raw == null) {
                raw = metadata.get("enableHistory");
            }
            if (raw instanceof Boolean b) {
                return b;
            }
            if (raw != null) {
                return Boolean.parseBoolean(String.valueOf(raw));
            }
        }
        return true;
    }

    /**
     * Python {@code get_latest_chat_history} turn dict.
     *
     * @param msg msg
     * @return result
     * @since 0.1.0
     */

    public static Map<String, Object> toHistoryTurn(BaseMessage msg) {
        Map<String, Object> turn = new LinkedHashMap<>();
        turn.put("role", msg.getRole() == null ? "" : msg.getRole());
        turn.put("content", msg.getContentAsString());
        turn.put("enable_history", readEnableHistory(msg));
        return turn;
    }
}
