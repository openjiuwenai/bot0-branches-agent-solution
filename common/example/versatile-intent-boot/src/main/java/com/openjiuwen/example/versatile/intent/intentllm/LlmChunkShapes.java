/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.intentllm;

import com.openjiuwen.service.spec.dto.QueryChunk;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Maps an {@link LlmIntentResult} into the runtime's expected QueryChunk /
 * result-map shapes, mirroring the a2a_delegate interrupt and ambiguous answer
 * envelope produced by VersatileResponseExtractor.
 *
 * @since 0.1.0
 */
final class LlmChunkShapes {
    private LlmChunkShapes() {}

    static QueryChunk delegateInterrupt(String agentId, String responseContent,
                                        String userQuery, boolean stream) {
        return new QueryChunk(QueryChunk.TYPE_INTERRUPT,
                delegatePayload(agentId, responseContent, userQuery, stream));
    }

    static Map<String, Object> delegateResult(String agentId, String responseContent,
                                              String userQuery, boolean stream) {
        Map<String, Object> result = assistantResult(responseContent);
        result.put("_interrupt", delegatePayload(agentId, responseContent, userQuery, stream));
        return result;
    }

    static QueryChunk ambiguousChunk(String responseContent, String intentId) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("type", "answer");
        envelope.put("response_content", responseContent);
        envelope.put("intent_id", intentId);
        return new QueryChunk(QueryChunk.TYPE_CHUNK, envelope);
    }

    static Map<String, Object> ambiguousResult(String responseContent, String intentId) {
        Map<String, Object> result = assistantResult(responseContent);
        result.put("intent_id", intentId);
        return result;
    }

    private static Map<String, Object> delegatePayload(String agentId, String responseContent,
                                                       String userQuery, boolean stream) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "__interaction__");
        payload.put("toolCallId", "llm-delegate-" + UUID.randomUUID());
        payload.put("agentName", agentId);
        payload.put("responseContent", responseContent);
        payload.put("resume", false);
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("_interrupt_kind", "a2a_delegate");
        context.put("agentName", agentId);
        context.put("resume", false);
        payload.put("context", context);
        payload.put("message", userQuery);
        payload.put("_stream_mode", stream ? "sse" : "");
        return payload;
    }

    private static Map<String, Object> assistantResult(Object content) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("role", "assistant");
        result.put("content", String.valueOf(content));
        return result;
    }
}