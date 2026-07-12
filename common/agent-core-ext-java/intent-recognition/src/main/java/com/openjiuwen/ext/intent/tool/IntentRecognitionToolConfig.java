/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.ext.intent.tool;

/** Resource identity and LLM-visible name for one intent Tool instance. */
public record IntentRecognitionToolConfig(String toolId, String toolName) {
    public static final String DEFAULT_TOOL_NAME = "intent_recognition";

    public IntentRecognitionToolConfig {
        if (toolId == null || toolId.isBlank()) {
            throw new IllegalArgumentException("toolId must not be blank");
        }
        toolId = toolId.trim();
        toolName = toolName == null || toolName.isBlank() ? DEFAULT_TOOL_NAME : toolName.trim();
        if (!toolName.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("toolName must contain only letters, digits, underscores, or hyphens");
        }
    }
}
