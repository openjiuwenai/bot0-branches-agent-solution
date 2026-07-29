/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.intentllm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Objects;

/**
 * Parsed result of an LLM intent-classification call.
 *
 * @since 0.1.0
 */
public record LlmIntentResult(Action action, String intentId, String agentId, String responseContent) {

    public enum Action { CLASSIFY, AMBIGUOUS }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Parses raw LLM JSON output. Falls back to {@link #ambiguous(String)} on
     * malformed JSON, out-of-domain action, or a classify missing agent_id.
     *
     * @param json the raw LLM output
     * @param ambiguousIntentId the configured ambiguous id (used for fallback)
     * @return the parsed result
     */
    public static LlmIntentResult parse(String json, String ambiguousIntentId) {
        String ambId = Objects.requireNonNullElse(ambiguousIntentId, "1");
        if (json == null || json.isBlank()) {
            return ambiguous(ambId);
        }
        try {
            String trimmed = stripCodeFences(json);
            JsonNode root = MAPPER.readTree(trimmed);
            String action = root.path("action").asText("");
            if ("classify".equals(action)) {
                String agentId = root.path("agent_id").asText("");
                if (agentId.isBlank()) {
                    return ambiguous(ambId);
                }
                return new LlmIntentResult(Action.CLASSIFY,
                        root.path("intent_id").asText(""),
                        agentId,
                        root.path("response_content").asText(""));
            }
            return ambiguous(ambId);
        } catch (Exception e) {
            return ambiguous(ambId);
        }
    }

    public static LlmIntentResult ambiguous(String ambiguousIntentId) {
        return new LlmIntentResult(Action.AMBIGUOUS,
                Objects.requireNonNullElse(ambiguousIntentId, "1"),
                null,
                "无法解析意图，需L1重识别");
    }

    private static String stripCodeFences(String json) {
        String s = json.trim();
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            if (firstNewline > 0) {
                s = s.substring(firstNewline + 1);
            }
            if (s.endsWith("```")) {
                s = s.substring(0, s.length() - 3);
            }
        }
        return s.trim();
    }
}