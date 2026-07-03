/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.agentcoreext.deepa;

import com.openjiuwen.service.spec.spi.AgentHandler;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies Agent A runtime classpath isolation.
 *
 * @since 2026-07-03
 */
class RuntimeIsolationTest {
    @Test
    void agentBApplicationIsNotOnAgentAClasspath() {
        assertThat(classExists("com.openjiuwen.example.agentcoreext.deepb.AgentBDeepAgentApplication"))
                .isFalse();
    }

    @Test
    void agentADeclaresExactlyOneAgentHandlerFactoryMethod() {
        long handlerFactoryMethods = java.util.Arrays.stream(AgentADeepAgentApplication.class.getDeclaredMethods())
                .filter(method -> AgentHandler.class.isAssignableFrom(method.getReturnType()))
                .count();

        assertThat(handlerFactoryMethods).isEqualTo(1);
    }

    @Test
    void agentAHasNoEmbeddedA2AClientMain() {
        assertThat(classExists("com.openjiuwen.example.agentcoreext.deepa.a2a.AgentCoreExtA2AClientMain"))
                .isFalse();
    }

    @Test
    void agentAUsesOpenAiCompatibleDeepSeekConfiguration() {
        DeepAgentLlmProperties properties = configuredProperties();

        Map<String, Object> backend = properties.backendConfig();
        Map<String, Object> model = properties.modelConfig();

        assertThat(backend)
                .containsEntry("provider", "OpenAI")
                .containsEntry("api_key", "test-key")
                .containsEntry("api_base", "https://api.deepseek.com");
        assertThat(model).containsEntry("model", "deepseek-chat");
        assertThat(properties.getSystemPrompt())
                .contains("agent-b")
                .contains("remoteInput")
                .contains("shadow task/resume")
                .contains("不要再次调用 agent-b");
    }

    @Test
    void agentARequiresApiKey() {
        DeepAgentLlmProperties properties = configuredProperties();
        properties.setApiKey("");

        assertThatThrownBy(properties::requireConfigured)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("api-key");
    }

    private static boolean classExists(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }

    private static DeepAgentLlmProperties configuredProperties() {
        DeepAgentLlmProperties properties = new DeepAgentLlmProperties();
        properties.setApiKey("test-key");
        properties.setTimeout(Duration.ofSeconds(10));
        return properties;
    }
}
