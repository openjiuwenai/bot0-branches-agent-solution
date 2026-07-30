/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.intentllm;

import com.openjiuwen.core.foundation.llm.Model;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * Calls an OpenAI-compatible LLM via the openjiuwen core {@link Model} abstraction
 * to classify intent. Delegates HTTP, auth, request-body assembly and response
 * parsing to core; this class only wires config and extracts the text content.
 *
 * @since 0.1.0
 */
class LlmIntentClient {
    private final Model model;

    LlmIntentClient(LlmIntentProperties properties) {
        ModelClientConfig clientConfig = ModelClientConfig.builder()
                .clientProvider("OpenAI")
                .apiKey(properties.getApiKey())
                .apiBase(properties.getBaseUrl())
                .timeout(timeoutSeconds(properties))
                .maxRetries(properties.getMaxRetries())
                .verifySsl(true)
                .build();
        ModelRequestConfig requestConfig = ModelRequestConfig.builder()
                .modelName(properties.getModel())
                .temperature(properties.getTemperature())
                .build();
        this.model = new Model(clientConfig, requestConfig);
    }

    String complete(List<Map<String, Object>> messages) {
        AssistantMessage resp = model.invokeAsync(messages, null, null, null, null, null, null, null, null, null)
                .onErrorMap(e -> new IllegalStateException("LLM call failed: " + e.getMessage(), e))
                .block();
        Object content = resp.getContent();
        return content != null ? content.toString() : "";
    }

    private static double timeoutSeconds(LlmIntentProperties properties) {
        long millis = properties.getTimeout() == null ? 30_000L : properties.getTimeout().toMillis();
        return BigDecimal.valueOf(millis)
                .divide(BigDecimal.valueOf(1000L), 3, RoundingMode.HALF_UP)
                .doubleValue();
    }
}
