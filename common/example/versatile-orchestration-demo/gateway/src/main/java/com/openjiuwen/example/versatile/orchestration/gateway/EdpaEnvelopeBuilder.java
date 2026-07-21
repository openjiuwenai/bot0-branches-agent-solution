/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.orchestration.gateway;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Rebuilds the full EDPA envelope from the minimal {@code custom_data} payload a caller posts.
 *
 * <p>The inbound body carries only {@code {"inputs":{query,intent, ...fixed}}}; the gateway stamps
 * the fixed caller-identity fields (from {@link GatewayProperties}) and the per-request fields
 * derived from the URL path ({@code conversation_id}, {@code agent_id}), and mirrors
 * {@code inputs.query} into {@code input.query}. The result is the canonical flat EDPA body that
 * {@link EdpaRequestTranslator} then converts to an A2A {@code SendStreamingMessage}.
 *
 * @since 2026-07-08
 */
@Component
public class EdpaEnvelopeBuilder {
    private static final String DEFAULT_ROLE_NAME = "手机银行";
    private static final String DEFAULT_ROLE_ID = "1";
    private static final String DEFAULT_TIMEOUT = "300";

    private final GatewayProperties properties;

    public EdpaEnvelopeBuilder(GatewayProperties properties) {
        this.properties = properties;
    }

    /**
     * build
     *
     * @param inputs inputs
     * @param conversationId conversationId
     * @param agentId agentId
     * @return EdpaRequest
     */
    public EdpaRequest build(Map<String, Object> inputs, String conversationId, String agentId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("role_name", orDefault(properties.roleName(), DEFAULT_ROLE_NAME));
        body.put("input", Map.of("query", str(inputs.get("query"))));
        body.put("agent_id", agentId);
        body.put("role_id", orDefault(properties.roleId(), DEFAULT_ROLE_ID));
        body.put("stream", true);
        body.put("conversation_id", conversationId);
        body.put("timeout", orDefault(properties.timeout(), DEFAULT_TIMEOUT));
        body.put("custom_data", Map.of("inputs", inputs));
        return new EdpaRequest(body);
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
