/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.intent.model;

/**
 * Independent model-visible descriptions for intent routing.
 *
 * @param toolDescription description of the intent tool
 * @param routingInstructions system instructions controlling intent routing
 *
 * @since 0.1.0
 */
public record IntentPromptConfig(String toolDescription, String routingInstructions) {
    /**
     * Returns a config that uses the built-in prompt values.
     *
     * @return default prompt config
     */
    public static IntentPromptConfig defaults() {
        return new IntentPromptConfig("", "");
    }
}
