/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.intent;

import com.openjiuwen.agents.intent.api.IntentResultFunction;
import com.openjiuwen.agents.intent.model.CustomIntentRegistration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Resolves result-function Bean names in Runtime intent configuration.
 *
 * @since 0.1.0
 */
public final class RuntimeConfiguredIntentsFactory {
    private RuntimeConfiguredIntentsFactory() {
    }

    /**
     * Resolves configured custom and fallback intents against Spring result-function Beans.
     *
     * @param properties
     *            Runtime intent properties
     * @param resultFunctions
     *            available result-function Beans by name
     *
     * @return resolved static intent registrations
     */
    public static RuntimeConfiguredIntents create(RuntimeIntentProperties properties,
            Map<String, IntentResultFunction> resultFunctions) {
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(resultFunctions, "resultFunctions");
        List<CustomIntentRegistration> customIntents = new ArrayList<>();
        Map<String, RuntimeIntentProperties.CustomIntentProperties> configured = properties.getCustomIntents();
        if (configured != null) {
            configured.forEach((id, value) -> customIntents.add(customIntent(id, value, resultFunctions)));
        }
        RuntimeIntentProperties.FallbackProperties fallbackProperties = properties.getFallback();
        CustomIntentRegistration fallback = fallbackProperties == null ? null
                : fallback(fallbackProperties, resultFunctions);
        return new RuntimeConfiguredIntents(customIntents, fallback);
    }

    private static CustomIntentRegistration customIntent(String id,
            RuntimeIntentProperties.CustomIntentProperties properties, Map<String, IntentResultFunction> functions) {
        String normalizedId = requireText(id, "custom intent id");
        if (properties == null) {
            throw new IllegalArgumentException("custom intent configuration is missing: " + normalizedId);
        }
        String description = requireText(properties.getDescription(), "custom intent description: " + normalizedId);
        IntentResultFunction function = function(properties.getResultFunctionBean(), functions, normalizedId);
        return new CustomIntentRegistration(normalizedId, description, function);
    }

    private static CustomIntentRegistration fallback(RuntimeIntentProperties.FallbackProperties properties,
            Map<String, IntentResultFunction> functions) {
        String id = requireText(properties.getId(), "fallback id");
        return new CustomIntentRegistration(id, "", function(properties.getResultFunctionBean(), functions, id));
    }

    private static IntentResultFunction function(String beanName, Map<String, IntentResultFunction> functions,
            String intentId) {
        String normalizedBeanName = requireText(beanName, "result function Bean for intent " + intentId);
        IntentResultFunction function = functions.get(normalizedBeanName);
        if (function == null) {
            throw new IllegalArgumentException("IntentResultFunction Bean not found: " + normalizedBeanName);
        }
        return function;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
