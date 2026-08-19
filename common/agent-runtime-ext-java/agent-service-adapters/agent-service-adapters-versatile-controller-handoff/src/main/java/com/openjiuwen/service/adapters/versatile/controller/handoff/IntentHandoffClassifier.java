/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.controller.handoff;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.adapters.versatile.controller.handoff.autoconfigure.ControllerHandoffProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Classifies controller SSE/REST output lines: identification strictly precedes the
 * FEAT-002 generic error mapping (fixed in the processing chain, not configurable).
 * Never uses exception-text keywords or natural-language similarity (spec 2.2/3.5).
 *
 * @since 2026-08-19
 */
public class IntentHandoffClassifier {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ControllerHandoffProperties properties;

    public IntentHandoffClassifier(ControllerHandoffProperties properties) {
        this.properties = properties;
    }

    public HandoffClassification classify(String rawLine) {
        if (rawLine == null || rawLine.isBlank()) {
            return HandoffClassification.notHandoff();
        }
        ControllerHandoffProperties.Classify classify = properties.getClassify();
        if (classify == null) {
            return HandoffClassification.notHandoff();
        }
        String data = stripSseDataPrefix(rawLine.trim());
        JsonNode json = readTree(data);
        if (!matchesEventType(rawLine.trim(), json, classify.getEventType())) {
            return HandoffClassification.notHandoff();
        }
        String value = readPath(json, classify.getFieldPath());
        if (value == null || classify.getFieldValue() == null || !classify.getFieldValue().contains(value)) {
            return HandoffClassification.notHandoff();
        }

        ControllerHandoffProperties.Fields fields = properties.getFields();
        List<String> missing = new ArrayList<>();
        String handoffType = requiredValue(json, fields.getHandoffType(), "handoff-type", missing);
        String intentId = requiredValue(json, fields.getIntentId(), "intent-id", missing);
        String businessDomain = requiredValue(json, fields.getBusinessDomain(), "business-domain", missing);
        String targetAgentId = requiredValue(json, fields.getTargetAgentId(), "target-agent-id", missing);
        String dedupKey = readPath(json, fields.getDedupKey()); // optional per spec 2.2
        if (!missing.isEmpty()) {
            return new HandoffClassification(HandoffClassification.Outcome.CONTRACT_VIOLATION, null);
        }
        return new HandoffClassification(HandoffClassification.Outcome.HANDOFF,
                new IntentHandoff(handoffType, intentId, businessDomain, targetAgentId, dedupKey, data));
    }

    private static String requiredValue(JsonNode json, String configuredPath, String label, List<String> missing) {
        String value = readPath(json, configuredPath);
        if (value == null) {
            missing.add(label);
        }
        return value;
    }

    private static boolean matchesEventType(String rawLine, JsonNode json, String eventType) {
        if (eventType == null || eventType.isBlank()) {
            return true;
        }
        if (rawLine.startsWith("event:") && eventType.equals(rawLine.substring("event:".length()).trim())) {
            return true;
        }
        return json != null && json.isObject() && json.hasNonNull("event")
                && eventType.equals(json.get("event").asText());
    }

    private static String readPath(JsonNode json, String path) {
        if (json == null || path == null || path.isBlank()) {
            return null;
        }
        JsonNode node = json.at(path.startsWith("/") ? path : "/" + path.replace('.', '/'));
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.isTextual() ? node.asText() : node.toString();
    }

    private static JsonNode readTree(String data) {
        try {
            return MAPPER.readTree(data);
        } catch (JsonProcessingException ignored) {
            return null;
        }
    }

    private static String stripSseDataPrefix(String trimmed) {
        if (trimmed.startsWith("data:")) {
            return trimmed.substring("data:".length()).trim();
        }
        if (trimmed.contains(":") && !trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return "";
        }
        return trimmed;
    }
}
