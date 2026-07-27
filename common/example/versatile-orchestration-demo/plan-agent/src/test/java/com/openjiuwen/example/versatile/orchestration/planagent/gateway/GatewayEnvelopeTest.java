/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.orchestration.planagent.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Locks the gateway envelope field order, placeholder values, and compact UTF-8 JSON bytes.
 *
 * @since 0.2.0
 */
class GatewayEnvelopeTest {
    private final GatewayEnvelope envelope = new GatewayEnvelope(new ObjectMapper());

    @Test
    void envelopeMapHasFixedFieldOrderAndPlaceholders() {
        Map<String, Object> custom = new LinkedHashMap<>();
        custom.put("event", "think_chunk");

        Map<String, Object> result = envelope.envelope(true, "agent-1", "conv-1", "", custom);

        assertThat(result.keySet()).containsExactly(
                "success", "agent_id", "conversation_id", "output", "error",
                "execution_time", "custom_rsp_data");
        assertThat(result).containsEntry("success", true);
        assertThat(result).containsEntry("agent_id", "agent-1");
        assertThat(result).containsEntry("conversation_id", "conv-1");
        assertThat(result).containsEntry("output", "");
        assertThat(result).containsEntry("error", "");
        assertThat(result).containsEntry("execution_time", "");
        assertThat(result).containsEntry("custom_rsp_data", custom);
    }

    @Test
    void envelopeMapNullSafeDefaultsToEmptyStringsAndEmptyCustomData() {
        Map<String, Object> result = envelope.envelope(false, null, null, null, null);

        assertThat(result).containsEntry("agent_id", "");
        assertThat(result).containsEntry("conversation_id", "");
        assertThat(result).containsEntry("error", "");
        assertThat(result).containsEntry("custom_rsp_data", Map.of());
    }

    @Test
    void toJsonIsCompactOrderedAndKeepsUtf8Raw() {
        Map<String, Object> custom = new LinkedHashMap<>();
        custom.put("event", "think_chunk");
        custom.put("content", "用户");

        String json = envelope.toJson(envelope.envelope(true, "agent-1", "conv-1", "", custom));

        // Compact (no spaces), fixed field order, non-ASCII kept raw (not unicode-escaped).
        assertThat(json).isEqualTo("{\"success\":true,\"agent_id\":\"agent-1\","
                + "\"conversation_id\":\"conv-1\",\"output\":\"\",\"error\":\"\","
                + "\"execution_time\":\"\",\"custom_rsp_data\":{\"event\":\"think_chunk\",\"content\":\"用户\"}}");
        assertThat(json).contains("用户");
    }
}
