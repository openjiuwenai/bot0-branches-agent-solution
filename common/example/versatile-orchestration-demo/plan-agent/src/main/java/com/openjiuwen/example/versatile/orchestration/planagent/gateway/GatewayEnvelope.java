/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.orchestration.planagent.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the gateway-style response envelope and serializes it to the compact, UTF-8
 * (non-ASCII unescaped) JSON string carried as each SSE {@code data:} line.
 *
 * <p>Envelope shape (mirrors the reference {@code CustomRestDemoAdapter#envelope}):
 * {@code {success, agent_id, conversation_id, output, error, execution_time, custom_rsp_data}}.
 * Field order is fixed by a {@link LinkedHashMap}, so serialized bytes are stable across runs.
 *
 * <p>{@link #envelope} returns the {@link Map} (for blocking JSON responses, where Spring
 * picks the content type). {@link #toJson} returns the pre-serialized compact string used for
 * byte-exact SSE frames, where we must control every byte.
 *
 * @since 0.2.0
 */
public final class GatewayEnvelope {
    private static final String FALLBACK_JSON =
            "{\"success\":false,\"agent_id\":\"\",\"conversation_id\":\"\",\"output\":\"\","
                    + "\"error\":\"envelope_serialization_failed\",\"execution_time\":\"\","
                    + "\"custom_rsp_data\":{}}";

    private final ObjectMapper objectMapper;

    public GatewayEnvelope(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Build the envelope as an ordered map.
     *
     * @param success        the success flag
     * @param agentId        echoed agent id ("" if absent)
     * @param conversationId echoed conversation id ("" if absent)
     * @param error          error message ("" when none)
     * @param customRspData  per-event payload under {@code custom_rsp_data} (empty map if null)
     * @return a new ordered map; never null
     */
    public Map<String, Object> envelope(boolean success, String agentId, String conversationId, String error,
                                        Object customRspData) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", success);
        body.put("agent_id", agentId == null ? "" : agentId);
        body.put("conversation_id", conversationId == null ? "" : conversationId);
        body.put("output", "");
        body.put("error", error == null ? "" : error);
        body.put("execution_time", "");
        body.put("custom_rsp_data", customRspData == null ? Map.of() : customRspData);
        return body;
    }

    /**
     * Serialize an envelope map to compact JSON (no spaces, non-ASCII raw). On the near-impossible
     * serialization failure of a plain map, return a minimal valid envelope so the framework still
     * emits one valid frame instead of throwing {@code adapter_execution_failed}.
     *
     * @param envelope the envelope map
     * @return compact JSON string
     */
    public String toJson(Map<String, Object> envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException ignored) {
            return FALLBACK_JSON;
        }
    }
}
