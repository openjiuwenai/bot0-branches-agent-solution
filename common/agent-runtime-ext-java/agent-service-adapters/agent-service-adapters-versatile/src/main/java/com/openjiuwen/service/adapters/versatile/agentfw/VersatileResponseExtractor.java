/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.agentfw;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.spec.dto.QueryChunk;

import java.util.ArrayList;
import java.util.List;

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
        String data = stripSsePrefix(line);
        if (data == null || data.isBlank()) {
            return new ArrayList<>();
        }

        JsonNode json = readTree(data);
        if (shouldExtractResult(data, json)) {
            String extracted = extractResult(json);
            if (extracted != null) {
                result = extracted;
                return new ArrayList<>();
            }
        }

        if (hasTextField(json, "event", "exception")) {
            isCompleted = true;
            hasFailed = true;
            error = data;
        }
        if (containsNodeTypeEnd(json)) {
            isCompleted = true;
        }

        return List.of(new QueryChunk(QueryChunk.TYPE_CHUNK, data));
    }

    List<QueryChunk> finish() {
        if (hasFailed) {
            return List.of(new QueryChunk(QueryChunk.TYPE_ERROR, error));
        }
        if (isCompleted) {
            return List.of(new QueryChunk(QueryChunk.TYPE_ANSWER, result));
        }
        return List.of(new QueryChunk(QueryChunk.TYPE_INTERRUPT, null));
    }

    private boolean shouldExtractResult(String rawData, JsonNode json) {
        return resultNodeName != null
                && !resultNodeName.trim().isEmpty()
                && rawData.contains("\"node_name\":\"" + resultNodeName + "\"")
                && json != null
                && json.isObject();
    }

    private String extractResult(JsonNode json) {
        JsonNode resultData = json.at("/custom_rsp_data/data");
        if (resultData.isMissingNode() || resultData.isNull()) {
            resultData = json.get("data");
        }
        if (resultData == null || resultData.isMissingNode() || resultData.isNull()) {
            return null;
        }
        JsonNode nodeType = resultData.get("node_type");
        JsonNode text = resultData.get("text");
        if (nodeType != null && "QA".equals(nodeType.asText()) && text != null && !text.asText().isBlank()) {
            return text.asText();
        }
        return null;
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

    private JsonNode readTree(String data) {
        try {
            return OBJECT_MAPPER.readTree(data);
        } catch (JsonProcessingException ignored) {
            return null;
        }
    }

    private String stripSsePrefix(String line) {
        String trimmed = line.trim();
        if (trimmed.startsWith("data:")) {
            return trimmed.substring("data:".length()).trim();
        }
        if (trimmed.contains(":") && !trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return null;
        }
        return trimmed;
    }
}
