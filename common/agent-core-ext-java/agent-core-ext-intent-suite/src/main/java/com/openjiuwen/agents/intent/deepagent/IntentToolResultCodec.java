/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.intent.deepagent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.agents.intent.model.IntentDecision;
import com.openjiuwen.agents.intent.model.ReturnAction;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Encodes suite decisions as model-visible Tool results.
 *
 * @since 0.1.0
 */
public final class IntentToolResultCodec {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * Encodes a decision that does not invoke another Tool.
     *
     * @param decision suite decision
     * @return JSON Tool result
     */
    public String encode(IntentDecision decision) {
        Objects.requireNonNull(decision, "decision");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", decision.status().name());
        if (decision.intentId() != null) {
            payload.put("intentId", decision.intentId());
        }
        if (decision.action() instanceof ReturnAction returnAction) {
            payload.put("result", returnAction.result());
        }
        if (decision.message() != null) {
            payload.put("message", decision.message());
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("intent result is not JSON serializable", exception);
        }
    }
}
