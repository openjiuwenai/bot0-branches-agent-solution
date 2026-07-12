/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.ext.intent.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import java.util.Objects;

/** Shared result encoding and failure-isolation helpers for framework adapters. */
public final class IntentResultEncoders {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private IntentResultEncoders() {
    }

    public static <T> JsonNode encodeOrFallback(IntentResultEncoder<T> encoder, IntentRecognitionResult<T> result) {
        try {
            JsonNode encoded = Objects.requireNonNull(encoder, "encoder must not be null")
                    .encode(Objects.requireNonNull(result, "result must not be null"));
            return Objects.requireNonNull(encoded, "encoder result must not be null");
        } catch (RuntimeException exception) {
            return fallback(IntentRecognitionReason.RESULT_ENCODING_FAILED);
        }
    }

    public static ObjectNode fallback(IntentRecognitionReason reason) {
        if (reason == IntentRecognitionReason.MATCHED) {
            throw new IllegalArgumentException("fallback reason must not be MATCHED");
        }
        ObjectNode envelope = OBJECT_MAPPER.createObjectNode();
        envelope.put("matched", false);
        envelope.putNull("target");
        envelope.put("reason", reason.name());
        return envelope;
    }

    public static Map<String, Object> toMap(JsonNode node) {
        return OBJECT_MAPPER.convertValue(node, MAP_TYPE);
    }
}
