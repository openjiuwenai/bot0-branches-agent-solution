/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.intentllm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import java.time.Duration;

/**
 * Tests for {@link LlmIntentProperties} defaults and validation.
 *
 * @since 0.1.0
 */
class LlmIntentPropertiesTest {
    @Test
    void defaultsAreSane() {
        LlmIntentProperties p = new LlmIntentProperties();
        assertThat(p.isEnabled()).isFalse();
        assertThat(p.getBaseUrl()).isEqualTo("https://api.openai.com/v1");
        assertThat(p.getModel()).isEqualTo("gpt-4o-mini");
        assertThat(p.getTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(p.getTemperature()).isEqualTo(0.0);
        assertThat(p.getMaxRetries()).isEqualTo(1);
        assertThat(p.getDomain()).isNull();
    }

    @Test
    void validatePassesWhenConfigured() {
        LlmIntentProperties p = new LlmIntentProperties();
        p.setApiKey("k");
        p.setBaseUrl("https://api.example.com/v1");
        p.setModel("m");
        p.validate(); // no exception
    }

    @Test
    void validateFailsWithoutApiKey() {
        LlmIntentProperties p = new LlmIntentProperties();
        p.setBaseUrl("u");
        p.setModel("m");
        assertThatThrownBy(p::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("openjiuwen.example.intent-llm.api-key");
    }
}