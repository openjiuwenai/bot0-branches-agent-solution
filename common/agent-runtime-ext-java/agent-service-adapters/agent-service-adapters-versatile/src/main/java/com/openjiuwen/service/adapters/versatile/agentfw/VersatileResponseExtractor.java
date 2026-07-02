/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.agentfw;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.spec.dto.QueryChunk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Extracts service response chunks from Versatile streaming responses.
 *
 * @since 2026-06-30
 */
final class VersatileResponseExtractor {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final String resultNodeName;
    private boolean isCompleted;
    private boolean hasFailed;
    private String result;
    private String error;

    VersatileResponseExtractor(String resultNodeName) {
        this.resultNodeName = resultNodeName;
    }

    List<QueryChunk> consumeLine(String line) {
        if (line == null || line.isBlank()) {
            return List.of();
        }
        Optional<String> data = stripSsePrefix(line);
        if (data.isEmpty() || data.get().isBlank()) {
            return new ArrayList<>();
        }

        Optional<JsonNode> json = readTree(data.get());
        if (shouldExtractResult(data.get(), json)) {
            Optional<String> extracted = extractResult(json.get());
            if (extracted.isPresent()) {
                result = extracted.get();
            }
            return new ArrayList<>();
        }

        if (hasTextField(json.orElse(null), "event", "exception")) {
            isCompleted = true;
            hasFailed = true;
            error = data.get();
        }
        if (json.filter(this::containsNodeTypeEnd).isPresent()) {
            isCompleted = true;
        }

        return List.of(new QueryChunk(QueryChunk.TYPE_CHUNK, data.get()));
    }

    List<QueryChunk> finish() {
        if (hasFailed) {
            return List.of(new QueryChunk(QueryChunk.TYPE_ERROR, error));
        }
        if (isCompleted && result != null) {
            return List.of(new QueryChunk(QueryChunk.TYPE_CHUNK, answerEnvelope(result)));
        }
        if (isCompleted) {
            return List.of();
        }
        return List.of(new QueryChunk(QueryChunk.TYPE_INTERRUPT, null));
    }

    static Optional<String> answerText(Object data) {
        if (!(data instanceof Map<?, ?> envelope) || !"answer".equals(envelope.get("type"))) {
            return Optional.empty();
        }
        Object output = envelope.get("output");
        if (output != null && !String.valueOf(output).isBlank()) {
            return Optional.of(String.valueOf(output));
        }
        return Optional.empty();
    }

    private static Map<String, Object> answerEnvelope(String output) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("type", "answer");
        envelope.put("output", output);
        return envelope;
    }

    private boolean shouldExtractResult(String rawData, Optional<JsonNode> json) {
        return resultNodeName != null
                && !resultNodeName.trim().isEmpty()
                && rawData.contains("\"node_name\":\"" + resultNodeName + "\"")
                && json.filter(JsonNode::isObject).isPresent();
    }

    private Optional<String> extractResult(JsonNode json) {
        JsonNode resultData = json.at("/custom_rsp_data/data");
        if (resultData.isMissingNode() || resultData.isNull()) {
            resultData = json.get("data");
        }
        if (resultData == null || resultData.isMissingNode() || resultData.isNull()) {
            return Optional.empty();
        }
        JsonNode nodeType = resultData.get("node_type");
        JsonNode text = resultData.get("text");
        if (nodeType != null && "QA".equals(nodeType.asText()) && text != null && !text.asText().isBlank()) {
            return Optional.of(text.asText());
        }
        return Optional.empty();
    }

    private boolean containsNodeTypeEnd(JsonNode json) {
        if (json == null) {
            return false;
        }
        if (hasTextField(json, "node_type", "End")) {
            return true;
        }
        if (json.isObject() || json.isArray()) {
            for (JsonNode child : json) {
                if (containsNodeTypeEnd(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasTextField(JsonNode json, String fieldName, String expected) {
        if (json == null || !json.isObject()) {
            return false;
        }
        JsonNode field = json.get(fieldName);
        return field != null && expected.equals(field.asText());
    }

    private Optional<JsonNode> readTree(String data) {
        try {
            return Optional.of(OBJECT_MAPPER.readTree(data));
        } catch (JsonProcessingException ignored) {
            return Optional.empty();
        }
    }

    private Optional<String> stripSsePrefix(String line) {
        String trimmed = line.trim();
        if (trimmed.startsWith("data:")) {
            return Optional.of(trimmed.substring("data:".length()).trim());
        }
        if (trimmed.contains(":") && !trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return Optional.empty();
        }
        return Optional.of(trimmed);
    }
}
