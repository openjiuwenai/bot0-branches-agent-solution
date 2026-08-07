/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.intent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.agents.intent.api.IntentResultFunction;
import com.openjiuwen.agents.intent.model.IntentSuiteConfig;
import com.openjiuwen.agents.intent.model.ReturnAction;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/** Tests conversion and Bean resolution for Runtime intent configuration. */
class RuntimeIntentConfigurationTest {
    @Test
    void resolvesCustomAndFallbackFunctionsByBeanName() {
        RuntimeIntentProperties properties = new RuntimeIntentProperties();
        properties.getMatch().setThreshold(0.7D);
        properties.getPrompt().setToolDescription("custom tool prompt");
        properties.getPrompt().setRoutingInstructions("custom routing prompt");
        properties.setExtensionOptions(new LinkedHashMap<>(Map.of("tenant", "bank")));
        RuntimeIntentProperties.CustomIntentProperties custom = new RuntimeIntentProperties.CustomIntentProperties();
        custom.setDescription("calculate locally");
        custom.setResultFunctionBean("calculatorIntent");
        properties.setCustomIntents(new LinkedHashMap<>(Map.of("calculator", custom)));
        RuntimeIntentProperties.FallbackProperties fallback = new RuntimeIntentProperties.FallbackProperties();
        fallback.setId("retry");
        fallback.setResultFunctionBean("retryIntent");
        properties.setFallback(fallback);
        IntentResultFunction calculator = context -> new ReturnAction("42");
        IntentResultFunction retry = context -> new ReturnAction("retry");

        IntentSuiteConfig coreConfig = RuntimeIntentCoreConfigFactory.create(properties);
        RuntimeConfiguredIntents configured = RuntimeConfiguredIntentsFactory.create(properties,
                Map.of("calculatorIntent", calculator, "retryIntent", retry));

        assertThat(coreConfig.matchThreshold()).isEqualTo(0.7D);
        assertThat(coreConfig.prompt().toolDescription()).isEqualTo("custom tool prompt");
        assertThat(coreConfig.prompt().routingInstructions()).isEqualTo("custom routing prompt");
        assertThat(coreConfig.extensionOptions()).containsEntry("tenant", "bank");
        assertThat(configured.customIntents()).singleElement().satisfies(intent -> {
            assertThat(intent.id()).isEqualTo("calculator");
            assertThat(intent.resultFunction()).isSameAs(calculator);
        });
        assertThat(configured.fallback().id()).isEqualTo("retry");
        assertThat(configured.fallback().resultFunction()).isSameAs(retry);
    }

    @Test
    void failsFastForIncompleteCustomOrFallbackBeanConfiguration() {
        RuntimeIntentProperties properties = new RuntimeIntentProperties();
        RuntimeIntentProperties.CustomIntentProperties custom = new RuntimeIntentProperties.CustomIntentProperties();
        custom.setDescription("custom");
        custom.setResultFunctionBean("missing");
        properties.setCustomIntents(Map.of("custom", custom));

        assertThatThrownBy(() -> RuntimeConfiguredIntentsFactory.create(properties, Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("missing");

        properties.setCustomIntents(Map.of());
        RuntimeIntentProperties.FallbackProperties fallback = new RuntimeIntentProperties.FallbackProperties();
        properties.setFallback(fallback);
        assertThatThrownBy(() -> RuntimeConfiguredIntentsFactory.create(properties, Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("result function Bean");
    }
}
