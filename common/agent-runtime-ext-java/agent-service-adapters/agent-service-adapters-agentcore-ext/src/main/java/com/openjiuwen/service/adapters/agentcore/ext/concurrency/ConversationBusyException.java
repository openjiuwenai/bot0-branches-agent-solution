/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.concurrency;

/**
 * Thrown when a conversation already has an active per-Task agent (DFX-002).
 *
 * <p>Extends {@link IllegalStateException} so existing callers treating it as
 * a generic conflict keep working. The handler layer maps this exception to
 * the stable {@code CONVERSATION_BUSY} failure code for protocol clients;
 * other {@code IllegalStateException}s from agent creation are not mapped and
 * surface as generic execution failures.
 *
 * @since 0.1.2
 */
public class ConversationBusyException extends IllegalStateException {
    /**
     * Creates a busy-conversation conflict.
     *
     * @param message detail message carrying the conversation identifier
     */
    public ConversationBusyException(String message) {
        super(message);
    }
}
