/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.intent.prompt;

import com.openjiuwen.agents.intent.model.IntentPromptConfig;

/**
 * Built-in model instructions for DeepAgent intent routing.
 *
 * @since 0.1.0
 */
public final class DefaultIntentPrompt {
    /**
     * Default description exposed with the intent Tool.
     */
    public static final String TOOL_DESCRIPTION = """
            Match the latest complete user request or subtask to one available handler and execute that handler.
            Pass a self-contained semantic description based on the latest conversation context.
            """;

    /**
     * Default system instructions appended to each model request.
     */
    public static final String ROUTING_INSTRUCTIONS = """
            Use intent_match before choosing a business handler. Build semantic from the latest user request and
            necessary conversation context; do not pass an isolated keyword or unrelated history. Do not call
            intent_match in parallel with another business tool.

            intent_match executes the selected target tool in the same call. Its Tool result is already the target
            tool result, so do not call that target again. After a successful target result, finish the current intent.
            If no intent matches, ask the user to restate the request. If routing fails, follow the returned error.

            When a target tool resumes after asking the user for information, continue the original task if the latest
            input supplies that information. If the latest input instead expresses a new goal, build a new complete
            semantic and call intent_match again rather than ending the turn.
            """;

    private DefaultIntentPrompt() {
    }

    /**
     * Returns the configured Tool description or its built-in default.
     *
     * @param config prompt configuration
     * @return effective Tool description
     */
    public static String toolDescription(IntentPromptConfig config) {
        return configuredOrDefault(config == null ? null : config.toolDescription(), TOOL_DESCRIPTION);
    }

    /**
     * Returns the configured routing instructions or their built-in default.
     *
     * @param config prompt configuration
     * @return effective routing instructions
     */
    public static String routingInstructions(IntentPromptConfig config) {
        return configuredOrDefault(config == null ? null : config.routingInstructions(), ROUTING_INSTRUCTIONS);
    }

    private static String configuredOrDefault(String configured, String defaultValue) {
        return configured == null || configured.isBlank() ? defaultValue.strip() : configured.strip();
    }
}
