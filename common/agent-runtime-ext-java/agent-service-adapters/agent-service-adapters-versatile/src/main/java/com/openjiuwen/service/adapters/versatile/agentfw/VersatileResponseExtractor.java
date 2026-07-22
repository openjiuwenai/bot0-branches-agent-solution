/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.agentfw;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties;
import com.openjiuwen.service.spec.dto.QueryChunk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Extracts service response chunks from Versatile streaming responses.
 *
 * @since 2026-06-30
 */
final class VersatileResponseExtractor {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final VersatileProperties properties;
    private final IntentAgentResolver agentResolver;
    private boolean isCompleted;
    private boolean hasFailed;
    private final Map<String, String> extractedFields = new LinkedHashMap<>();
    private String error;
    private boolean pendingInterrupt;
    private boolean interruptSignalSeen;
    private String interruptPrompt;
    private String interruptInputRequirement;
    private String interruptResumeToken;

    VersatileResponseExtractor(VersatileProperties properties, IntentAgentResolver agentResolver) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.agentResolver = Objects.requireNonNull(agentResolver, "agentResolver");
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
        if (matchesInterruptSignal(data.get(), json)) {
            interruptSignalSeen = true;
            extractInterruptFields(json.orElse(null));
            return new ArrayList<>();
        }
        if (shouldExtractResult(data.get(), json)) {
            if (properties.getResultExtractions() == null || properties.getResultExtractions().isEmpty()) {
                Optional<String> extracted = extractLegacyText(json.get());
                if (extracted.isPresent()) {
                    extractedFields.put("response_content", extracted.get());
                }
            } else {
                extractResultFields(json.get());
                if (!hasText(extractedFields.get("response_content"))) {
                    hasFailed = true;
                    error = "{\"code\":\"VERSATILE_INTENT_RESULT_CONTRACT\","
                            + "\"reason\":\"missing response_content in three-field result\"}";
                }
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
        if (pendingInterrupt) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("message", interruptPrompt);
            payload.put("input_requirement", interruptInputRequirement);
            payload.put("resume_token", interruptResumeToken);
            return List.of(new QueryChunk(QueryChunk.TYPE_INTERRUPT, payload));
        }
        if (!pendingInterrupt
                && interruptSignalSeenButIncomplete()) {
            return List.of(new QueryChunk(QueryChunk.TYPE_ERROR,
                    "{\"code\":\"VERSATILE_INTENT_INTERRUPT_INCOMPLETE\","
                            + "\"reason\":\"native interrupt missing prompt/input-requirement/resume-token\"}"));
        }
        if (hasFailed) {
            return List.of(new QueryChunk(QueryChunk.TYPE_ERROR, error));
        }
        if (isCompleted && !extractedFields.isEmpty()) {
            if (properties.getResultExtractions() != null && !properties.getResultExtractions().isEmpty()) {
                Optional<Map<String, Object>> envelope = buildThreeFieldEnvelope();
                if (envelope.isPresent()) {
                    return List.of(new QueryChunk(QueryChunk.TYPE_CHUNK, envelope.get()));
                }
                return List.of(new QueryChunk(QueryChunk.TYPE_ERROR,
                        "{\"code\":\"VERSATILE_INTENT_RESULT_CONTRACT\","
                                + "\"reason\":\"missing or invalid three-field result\"}"));
            }
            Map<String, Object> legacy = new LinkedHashMap<>();
            legacy.put("type", "answer");
            legacy.put("output", extractedFields.get("response_content"));
            return List.of(new QueryChunk(QueryChunk.TYPE_CHUNK, legacy));
        }
        if (isCompleted) {
            return List.of();
        }
        return List.of(new QueryChunk(QueryChunk.TYPE_ERROR,
                "{\"code\":\"VERSATILE_STREAM_CLOSED_WITHOUT_TERMINAL\","
                        + "\"reason\":\"no End/exception event\"}"));
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

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private boolean shouldExtractResult(String rawData, Optional<JsonNode> json) {
        String resultNodeName = properties.getResultNodeName();
        return resultNodeName != null
                && !resultNodeName.trim().isEmpty()
                && rawData.contains("\"node_name\":\"" + resultNodeName + "\"")
                && json.filter(JsonNode::isObject).isPresent();
    }

    private boolean matchesInterruptSignal(String rawData, Optional<JsonNode> json) {
        VersatileProperties.Interrupt interrupt = properties.getInterrupt();
        if (interrupt == null || !hasText(interrupt.getSignalMatch())) {
            return false;
        }
        return rawData.contains("\"event\":\"" + interrupt.getSignalMatch() + "\"");
    }

    private void extractInterruptFields(JsonNode json) {
        VersatileProperties.Interrupt interrupt = properties.getInterrupt();
        interruptPrompt = readPath(json, interrupt.getPromptGet());
        interruptInputRequirement = readPath(json, interrupt.getInputRequirementGet());
        interruptResumeToken = readPath(json, interrupt.getResumeTokenGet());
        pendingInterrupt = hasText(interruptPrompt)
                && hasText(interruptInputRequirement)
                && hasText(interruptResumeToken);
    }

    private boolean interruptSignalSeenButIncomplete() {
        return interruptSignalSeen && !pendingInterrupt;
    }

    private static String readPath(JsonNode json, String path) {
        if (json == null || !hasText(path)) {
            return null;
        }
        JsonNode node = json.at(path);
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.isTextual() ? node.asText() : node.toString();
    }

    private Optional<String> extractLegacyText(JsonNode json) {
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

    private void extractResultFields(JsonNode json) {
        for (VersatileProperties.ResultExtraction rule : properties.getResultExtractions()) {
            if (rule == null || !hasText(rule.getMatch()) || !hasText(rule.getGet())) {
                continue;
            }
            JsonNode node = json.at(rule.getGet());
            if (node == null || node.isMissingNode() || node.isNull()) {
                continue;
            }
            String value = node.isTextual() ? node.asText() : node.toString();
            if (hasText(value)) {
                extractedFields.put(rule.getMatch(), value);
            }
        }
    }

    private Optional<Map<String, Object>> buildThreeFieldEnvelope() {
        String responseContent = extractedFields.get("response_content");
        String intentId = extractedFields.get("intent_id");
        String workflowAgentId = extractedFields.get("agent_id");
        if (!hasText(responseContent) || !hasText(intentId)) {
            return Optional.empty();
        }
        String agentId;
        try {
            agentId = agentResolver.resolve(intentId, workflowAgentId)
                    .orElseThrow(() -> new IllegalStateException("VERSATILE_INTENT_AGENT_ID_UNMAPPED"));
        } catch (IllegalStateException ex) {
            return Optional.empty();
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("type", "answer");
        envelope.put("output", responseContent);
        envelope.put("response_content", responseContent);
        envelope.put("intent_id", intentId);
        envelope.put("agent_id", agentId);
        return Optional.of(envelope);
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

    private static Optional<JsonNode> readTree(String data) {
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
