/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.orchestration.planagent.gateway;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Typed view of the platform gateway request envelope, bound from the custom-rest
 * {@code Context.body()} map via {@code objectMapper.convertValue(body, GatewayRequest.class)}.
 *
 * <p>{@code ignoreUnknown = true} tolerates fields the adapter does not consume. Each underscored
 * wire field name is bound explicitly via {@code @JsonProperty}; the Java component names stay
 * camelCase.
 *
 * @param roleName        caller role label (e.g. {@code "MobileClient"})
 * @param input           the {@code input} object; only {@code query} is consumed
 * @param agentId         agent id (body; path-variable fallback applied in the adapter)
 * @param roleId          opaque role id
 * @param stream          streaming flag; null means "stream"
 * @param conversationId  conversation id (body; path-variable fallback applied in the adapter)
 * @param timeout         opaque timeout hint
 * @param customData      the {@code custom_data} object; {@code inputs} carried as an opaque map
 * @since 0.2.0
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GatewayRequest(
        @JsonProperty("role_name") String roleName,
        Input input,
        @JsonProperty("agent_id") String agentId,
        @JsonProperty("role_id") String roleId,
        Boolean stream,
        @JsonProperty("conversation_id") String conversationId,
        String timeout,
        @JsonProperty("custom_data") CustomData customData) {

    /** The {@code input} object; only {@code query} is consumed. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Input(String query) {
    }

    /** The {@code custom_data} object; {@code inputs} is carried as an opaque map. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CustomData(Map<String, Object> inputs) {
    }
}
