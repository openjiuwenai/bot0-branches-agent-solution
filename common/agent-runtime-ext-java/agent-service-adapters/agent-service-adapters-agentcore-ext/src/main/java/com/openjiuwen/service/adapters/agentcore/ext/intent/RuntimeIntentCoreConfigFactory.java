/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.intent;

import com.openjiuwen.agents.intent.model.IntentPromptConfig;
import com.openjiuwen.agents.intent.model.IntentSuiteConfig;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Converts Runtime properties to the Core-owned suite configuration. */
public final class RuntimeIntentCoreConfigFactory {
    private RuntimeIntentCoreConfigFactory() {
    }

    public static IntentSuiteConfig create(RuntimeIntentProperties properties) {
        Objects.requireNonNull(properties, "properties");
        RuntimeIntentProperties.MatchProperties match = Objects.requireNonNull(properties.getMatch(), "intent.match");
        RuntimeIntentProperties.IntentPromptProperties prompt = Objects.requireNonNull(properties.getPrompt(),
                "intent.prompt");
        Map<String, Object> options = properties.getExtensionOptions() == null
                ? Map.of()
                : new LinkedHashMap<>(properties.getExtensionOptions());
        return IntentSuiteConfig.builder().matchThreshold(match.getThreshold())
                .prompt(new IntentPromptConfig(prompt.getToolDescription(), prompt.getRoutingInstructions()))
                .extensionOptions(options).build();
    }
}
