/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.orchestration.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Unit-tests the EDPA envelope reconstruction: minimal {@code custom_data.inputs} + URL path →
 * the canonical flat EDPA body the openjiuwen controller expects.
 */
class EdpaEnvelopeBuilderTest {
    private static GatewayProperties props(String roleName, String roleId, String timeout) {
        return new GatewayProperties("http://plan-agent", null, Set.of(), roleName, roleId, timeout, null, null);
    }

    private static Map<String, Object> inputs(String query, String intent, String wapUserName) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("query", query);
        inputs.put("intent", intent);
        inputs.put("wap_userName", wapUserName);
        return inputs;
    }

    @Test
    void buildsFullEnvelopeFromMinimalInputsAndPath() {
        EdpaEnvelopeBuilder builder = new EdpaEnvelopeBuilder(props("手机银行", "1", "300"));
        Map<String, Object> inputs = inputs("查尾号为4241的卡的余额", "", "张三");

        EdpaRequest req = builder.build(inputs,
                "53678ff7-8325-46dd-9402-70c031c45408", "fcbcd0ce-73b0-4097-a0cb-6286341f88f6");

        Map<String, Object> body = req.body();
        assertThat(body).containsEntry("role_name", "手机银行");
        assertThat(body).containsEntry("agent_id", "fcbcd0ce-73b0-4097-a0cb-6286341f88f6");
        assertThat(body).containsEntry("role_id", "1");
        assertThat(body).containsEntry("stream", true);
        assertThat(body).containsEntry("conversation_id", "53678ff7-8325-46dd-9402-70c031c45408");
        assertThat(body).containsEntry("timeout", "300");
        // input.query mirrors inputs.query.
        assertThat(((Map<?, ?>) body.get("input")).get("query")).isEqualTo("查尾号为4241的卡的余额");
        // custom_data carries the full inputs untouched.
        Map<?, ?> customInputs = (Map<?, ?>) ((Map<?, ?>) body.get("custom_data")).get("inputs");
        assertThat(customInputs.get("query")).isEqualTo("查尾号为4241的卡的余额");
        assertThat(customInputs.get("intent")).isEqualTo("");
        assertThat(customInputs.get("wap_userName")).isEqualTo("张三");
    }

    @Test
    void fallsBackToDefaultsWhenPropertiesBlank() {
        EdpaEnvelopeBuilder builder = new EdpaEnvelopeBuilder(props(null, "  ", null));
        EdpaRequest req = builder.build(Map.of("query", "hi"), "cid", "aid");

        assertThat(req.body()).containsEntry("role_name", "手机银行");
        assertThat(req.body()).containsEntry("role_id", "1");
        assertThat(req.body()).containsEntry("timeout", "300");
    }

    @Test
    void missingQueryBecomesEmptyInputQuery() {
        EdpaEnvelopeBuilder builder = new EdpaEnvelopeBuilder(props("手机银行", "1", "300"));
        EdpaRequest req = builder.build(Map.of(), "cid", "aid");
        assertThat(((Map<?, ?>) req.body().get("input")).get("query")).isEqualTo("");
    }

    @Test
    void emitsBodyTranslatorCanExtractQueryAndConversationIdFrom() {
        // The rebuilt envelope must round-trip through EdpaRequest's own accessors — that is the shape
        // EdpaRequestTranslator consumes.
        EdpaEnvelopeBuilder builder = new EdpaEnvelopeBuilder(props("手机银行", "1", "300"));
        EdpaRequest req = builder.build(inputs("查余额", "", "张三"), "cid-9", "aid-9");

        assertThat(req.extractQuery()).isEqualTo("查余额");
        assertThat(req.conversationId()).isEqualTo("cid-9");
    }
}
