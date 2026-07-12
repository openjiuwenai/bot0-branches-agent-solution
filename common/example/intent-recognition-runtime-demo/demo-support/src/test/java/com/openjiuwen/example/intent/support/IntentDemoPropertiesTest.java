/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.intent.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.core.foundation.llm.schema.ModelConfig;
import com.openjiuwen.core.retrieval.common.RerankerConfig;
import org.junit.jupiter.api.Test;

class IntentDemoPropertiesTest {
    @Test
    void mapsApplicationPropertiesToAgentCoreConfigurations() {
        IntentDemoProperties properties = configuredProperties();

        RerankerConfig reranker = properties.toRerankerConfig();
        ModelConfig model = properties.toModelConfig();

        assertThat(reranker.getApiBase()).isEqualTo("https://reranker.example/v1");
        assertThat(reranker.getApiKey()).isEqualTo("reranker-key");
        assertThat(reranker.getModelName()).isEqualTo("reranker-model");
        assertThat(reranker.getTimeout()).isEqualTo(15.0);
        assertThat(reranker.getExtraBody()).containsEntry("truncate", true);
        assertThat(model.modelProvider()).isEqualTo("OpenAI");
        assertThat(model.modelInfo().getApiBase()).isEqualTo("https://chat.example/v1");
        assertThat(model.modelInfo().getApiKey()).isEqualTo("chat-key");
        assertThat(model.modelInfo().getModelName()).isEqualTo("chat-model");
        assertThat(model.modelInfo().isVerifySsl()).isFalse();
    }

    @Test
    void reportsExactMissingConfigurationProperty() {
        IntentDemoProperties properties = configuredProperties();
        properties.getReranker().setApiBase(" ");

        assertThatThrownBy(properties::requireConfigured).isInstanceOf(IllegalStateException.class)
                .hasMessage("openjiuwen.demo.intent.reranker.api-base is required");
    }

    static IntentDemoProperties configuredProperties() {
        IntentDemoProperties properties = new IntentDemoProperties();
        properties.getLlm().setProvider("OpenAI");
        properties.getLlm().setApiKey("chat-key");
        properties.getLlm().setApiBase("https://chat.example/v1");
        properties.getLlm().setModelName("chat-model");
        properties.getLlm().setSslVerify(false);
        properties.getLlm().setTimeoutSeconds(120);
        properties.getReranker().setApiKey("reranker-key");
        properties.getReranker().setApiBase("https://reranker.example/v1");
        properties.getReranker().setModelName("reranker-model");
        properties.getReranker().setTimeoutSeconds(15.0);
        properties.getReranker().setExtraBody(java.util.Map.of("truncate", true));
        return properties;
    }
}
