/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.agentfw;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

public class VersatileResponseExtractor {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final String resultNodeName;
    private boolean completed;
    private boolean failed;
    private String result;
    private String error;

    public VersatileResponseExtractor(String resultNodeName) {
        this.resultNodeName = resultNodeName;
    }

    public List<Event> consumeLine(String line) {
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
            completed = true;
            failed = true;
            error = data;
        }
        if (containsNodeTypeEnd(json)) {
            completed = true;
        }

        List<Event> events = new ArrayList<>(1);
        events.add(new Event(EventType.PASSTHROUGH, data));
        return events;
    }

    public List<Event> finish() {
        List<Event> events = new ArrayList<>(1);
        if (failed) {
            events.add(new Event(EventType.FAILED, error));
            return events;
        }
        if (completed) {
            events.add(new Event(EventType.COMPLETED, result));
            return events;
        }
        events.add(new Event(EventType.INPUT_REQUIRED, null));
        return events;
    }

    public boolean completed() {
        return completed;
    }

    public String result() {
        return result;
    }

    public String error() {
        return error;
    }

    private boolean shouldExtractResult(String rawData, JsonNode json) {
        return hasText(resultNodeName)
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
        } catch (Exception ignored) {
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

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    public enum EventType {
        PASSTHROUGH,
        INPUT_REQUIRED,
        COMPLETED,
        FAILED
    }

    public record Event(EventType type, Object data) {
    }
}
