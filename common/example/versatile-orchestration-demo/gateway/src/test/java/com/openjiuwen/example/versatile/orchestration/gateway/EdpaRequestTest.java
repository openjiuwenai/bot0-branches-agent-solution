/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.orchestration.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Unit tests for the flat EDPA {@link EdpaRequest}: query extraction ({@code input.query} →
 * {@code custom_data.inputs.query} fallback), {@code conversation_id} mapping, and header filtering.
 */
class EdpaRequestTest {
    @Test
    void extractQuery_primaryInputQueryWins() {
        Map<String, Object> body = flat("查余额", "custom-fallback", "ctx-1");

        assertThat(new EdpaRequest(body).extractQuery()).isEqualTo("查余额");
    }

    @Test
    void extractQuery_fallsBackToCustomDataInputsQueryWhenInputQueryBlank() {
        Map<String, Object> body = flat("   ", "deep", "ctx-1");

        assertThat(new EdpaRequest(body).extractQuery()).isEqualTo("deep");
    }

    @Test
    void extractQuery_fallsBackWhenInputAbsent() {
        Map<String, Object> body = flat(null, "deep", "ctx-1");

        assertThat(new EdpaRequest(body).extractQuery()).isEqualTo("deep");
    }

    @Test
    void extractQuery_returnsEmptyWhenBothTiersBlank() {
        Map<String, Object> body = flat("", "", "ctx-1");

        assertThat(new EdpaRequest(body).extractQuery()).isEmpty();
    }

    @Test
    void extractIntent_primaryInputIntentWins() {
        Map<String, Object> body = flat("q", "q", "ctx-1");

        assertThat(new EdpaRequest(body).extractIntent()).isEqualTo("test-intent");
    }

    @Test
    void extractIntent_fallsBackToCustomDataInputsIntentWhenInputBlank() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("conversation_id", "ctx-1");
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("query", "q");
        input.put("intent", "   ");                  // blank → fall through
        body.put("input", input);
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("query", "q");
        inputs.put("intent", "deep-intent");
        body.put("custom_data", Map.of("inputs", inputs));

        assertThat(new EdpaRequest(body).extractIntent()).isEqualTo("deep-intent");
    }

    @Test
    void extractIntent_returnsEmptyWhenBothTiersBlank() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("conversation_id", "ctx-1");
        body.put("input", Map.of("query", "q"));                          // no intent
        body.put("custom_data", Map.of("inputs", Map.of("query", "q")));  // no intent

        assertThat(new EdpaRequest(body).extractIntent()).isEmpty();
    }

    @Test
    void conversationId_readsTopLevelSnakeCaseField() {
        Map<String, Object> body = flat("q", "q", "53678ff7-abc");

        assertThat(new EdpaRequest(body).conversationId()).isEqualTo("53678ff7-abc");
    }

    @Test
    void headersToForward_keepsOnlyWhitelistedKeysCaseInsensitively() {
        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put("x-language", "zh-cn");
        headers.put("x-invoke-mode", "DEBUG");
        headers.put("stream", "true");
        Map<String, Object> body = flat("q", "q", "ctx-1");
        body.put("headers", headers);

        Map<String, String> forwarded = new EdpaRequest(body)
                .headersToForward(java.util.Set.of("X-LANGUAGE"));

        assertThat(forwarded).containsOnlyKeys("x-language");
        assertThat(forwarded.get("x-language")).isEqualTo("zh-cn");
    }

    @Test
    void headersToForward_emptyWhenBodyHasNoHeaders() {
        Map<String, Object> body = flat("q", "q", "ctx-1");

        assertThat(new EdpaRequest(body).headersToForward(java.util.Set.of("x-language"))).isEmpty();
    }

    /**
     * Minimal flat EDPA body with the two query tiers + conversation_id. Pass {@code null} for
     * {@code inputQuery} to omit the {@code input} object's query.
     *
     * @param inputQuery      the {@code input.query} tier value, or {@code null} to omit it
     * @param customDataQuery the {@code custom_data.inputs.query} tier value
     * @param conversationId  the {@code conversation_id} value
     * @return a flat EDPA body map for use in tests
     */
    private static Map<String, Object> flat(String inputQuery, String customDataQuery, String conversationId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("conversation_id", conversationId);
        body.put("role_name", "手机银行");
        body.put("agent_id", "a-1");
        body.put("role_id", "1");
        body.put("stream", true);
        body.put("timeout", "300");

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("intent", "test-intent");
        if (inputQuery != null) {
            input.put("query", inputQuery);
        }
        body.put("input", input);

        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("intent", "test-intent");
        inputs.put("query", customDataQuery);
        Map<String, Object> customData = new LinkedHashMap<>();
        customData.put("inputs", inputs);
        body.put("custom_data", customData);

        return body;
    }
}
