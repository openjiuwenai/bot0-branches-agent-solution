/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.enforcing;

/**
 * Thrown by {@link ToolCallingEnforcingModel} when the underlying
 * {@link com.openjiuwen.core.foundation.llm.model_clients.BaseModelClient} silently
 * discards the tools parameter — a tool-calling bypass.
 *
 * <p>This is a hard runtime exception: the client has been proven unable to forward
 * tool definitions to the LLM API. The application should not attempt further
 * tool-requiring invocations with this client instance.
 *
 * @since 2026-07
 */
public class ToolCallingBypassException extends RuntimeException {

    public ToolCallingBypassException(String message) {
        super(message);
    }

    public ToolCallingBypassException(String message, Throwable cause) {
        super(message, cause);
    }
}
