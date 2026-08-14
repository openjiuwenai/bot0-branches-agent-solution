/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.agentfw;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties;
import com.openjiuwen.service.spec.dto.ServeRequest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Extracts Versatile remote request data from service requests.
 *
 * @since 2026-06-30
 */
final class VersatileRequestExtractor {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final VersatileProperties properties;

    VersatileRequestExtractor(VersatileProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    RemoteRequest extract(ServeRequest request) {
        Map<String, Object> sourceBody = mapValue(request.getMetadata().get("body"));
        Map<String, Object> remoteBody = new LinkedHashMap<>(mapValue(sourceBody.get("custom_data")));
        Map<String, Object> inputs = new LinkedHashMap<>(mapValue(remoteBody.get("inputs")));
        SemanticInput semanticInput = extractSemanticInput(request);
        if (hasText(semanticInput.query())) {
            inputs.put("query", semanticInput.query());
        }
        if (hasText(semanticInput.intent())) {
            inputs.put("intent", semanticInput.intent());
        }
        serializeIntents().ifPresent(json -> inputs.put("intents", json));
        serializeMessages(request).ifPresent(json -> inputs.put("messages", json));
        if (!inputs.isEmpty()) {
            remoteBody.put("inputs", inputs);
        }
        fillResumeRequestTemplate(remoteBody, request);

        Map<String, String> headers = new LinkedHashMap<>();
        Map<String, Object> sourceHeaders = mapValue(request.getMetadata().get("headers"));
        Set<String> whitelist = properties.getForwardHeaderWhitelist();
        for (Map.Entry<String, Object> entry : sourceHeaders.entrySet()) {
            if (isForwardHeader(entry.getKey(), whitelist) && entry.getValue() != null) {
                putHeader(headers, entry.getKey(), String.valueOf(entry.getValue()));
            }
        }
        properties.getHeadersTemplate().forEach((key, value) -> putHeader(headers, key, value));

        Map<String, String> params = new LinkedHashMap<>();
        Map<String, Object> sourceParams = mapValue(request.getMetadata().get("query"));
        for (Map.Entry<String, Object> entry : sourceParams.entrySet()) {
            if (entry.getValue() != null) {
                params.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
        }

        String urlTemplate = properties.getUrlTemplate();
        if (!hasText(urlTemplate)) {
            throw new IllegalArgumentException("openjiuwen.service.versatile.url-template must not be blank");
        }
        Object agentId = request.getMetadata().get("agent_id");
        String url = urlTemplate.replace(
                "{conversation_id}", request.getConversationId() != null ? request.getConversationId() : "")
                .replace("{agent_id}", agentId != null ? String.valueOf(agentId) : "");
        return new RemoteRequest(url, headers, params, remoteBody);
    }

    private SemanticInput extractSemanticInput(ServeRequest request) {
        Optional<Object> content = latestUserContent(request);
        Map<String, Object> structuredContent = content.map(this::structuredContent).orElseGet(Map::of);
        String query = stringValue(structuredContent.get("query")).orElse(null);
        if (!hasText(query)) {
            query = request.lastUserQuery();
        }
        String intent = stringValue(structuredContent.get("intent")).orElse(null);
        return new SemanticInput(query, intent);
    }

    private Optional<String> serializeIntents() {
        List<VersatileProperties.Intent> intents = properties.getIntents();
        if (intents == null || intents.isEmpty()) {
            return Optional.empty();
        }
        for (int i = 0; i < intents.size(); i++) {
            VersatileProperties.Intent intent = intents.get(i);
            if (intent == null || !hasText(intent.getId()) || !hasText(intent.getName())) {
                throw new IllegalArgumentException(
                        "VERSATILE_INTENT_CONFIG_MISSING: intents[" + i + "] id/name must be non-blank");
            }
        }
        try {
            return Optional.of(OBJECT_MAPPER.writeValueAsString(intents));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("VERSATILE_INTENT_CONFIG_MISSING: failed to serialize intents", ex);
        }
    }

    private Optional<String> serializeMessages(ServeRequest request) {
        List<Map<String, Object>> messages = request.getMessages();
        boolean required = properties.getMessages() != null && properties.getMessages().isRequired();
        if (messages == null || messages.isEmpty()) {
            if (required) {
                throw new IllegalArgumentException(
                        "VERSATILE_INTENT_INPUT_MISSING: ServeRequest.messages must be non-empty");
            }
            return Optional.empty();
        }
        List<Map<String, String>> serialized = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            Map<String, Object> message = messages.get(i);
            if (message == null) {
                continue;
            }
            Object role = message.get("role");
            Object content = message.get("content");
            if (role == null || String.valueOf(role).isBlank()
                    || content == null || String.valueOf(content).isBlank()) {
                if (required) {
                    throw new IllegalArgumentException(
                            "VERSATILE_INTENT_INPUT_MISSING: messages[" + i + "] role/content must be non-blank");
                }
                continue;
            }
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("role", String.valueOf(role));
            entry.put("content", String.valueOf(content));
            serialized.add(entry);
        }
        if (serialized.isEmpty()) {
            if (required) {
                throw new IllegalArgumentException(
                        "VERSATILE_INTENT_INPUT_MISSING: ServeRequest.messages has no valid role/content entries");
            }
            return Optional.empty();
        }
        try {
            return Optional.of(OBJECT_MAPPER.writeValueAsString(serialized));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("VERSATILE_INTENT_INPUT_MISSING: failed to serialize messages", ex);
        }
    }

    private void fillResumeRequestTemplate(Map<String, Object> remoteBody, ServeRequest request) {
        VersatileProperties.Interrupt interrupt = properties.getInterrupt();
        if (interrupt == null || interrupt.getResumeRequestTemplate() == null) {
            return;
        }
        Map<String, Object> template = interrupt.getResumeRequestTemplate().getBody();
        if (template == null || template.isEmpty()) {
            return;
        }
        Map<String, Object> source = mapValue(request.getMetadata().get("body"));
        Map<String, Object> filled = deepFill(template, source);
        remoteBody.putAll(filled);
    }

    private static Map<String, Object> deepFill(Map<String, Object> template, Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : template.entrySet()) {
            result.put(entry.getKey(), resolveTemplateValue(entry.getValue(), source));
        }
        return result;
    }

    private static Object resolveTemplateValue(Object value, Map<String, Object> source) {
        if (value instanceof String text) {
            return resolvePlaceholders(text, source);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    nested.put(String.valueOf(entry.getKey()),
                            resolveTemplateValue(entry.getValue(), source));
                }
            }
            return nested;
        }
        return value;
    }

    private static String resolvePlaceholders(String text, Map<String, Object> source) {
        String result = text;
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            result = result.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }
        return result;
    }

    private Optional<Object> latestUserContent(ServeRequest request) {
        for (int i = request.getMessages().size() - 1; i >= 0; i--) {
            Map<String, Object> message = request.getMessages().get(i);
            if (message == null) {
                continue;
            }
            Object role = message.get("role");
            if (role != null && "user".equalsIgnoreCase(String.valueOf(role))) {
                return Optional.ofNullable(message.get("content"));
            }
        }
        if (!request.getMessages().isEmpty()) {
            Map<String, Object> message = request.getMessages().get(request.getMessages().size() - 1);
            return message != null ? Optional.ofNullable(message.get("content")) : Optional.empty();
        }
        return Optional.empty();
    }

    private Map<String, Object> structuredContent(Object content) {
        if (content instanceof Map<?, ?> map) {
            return copyMap(map);
        }
        if (content instanceof String text && hasText(text)) {
            String trimmed = text.trim();
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                try {
                    return OBJECT_MAPPER.readValue(trimmed, MAP_TYPE);
                } catch (JsonProcessingException ignored) {
                    return Map.of();
                }
            }
        }
        return Map.of();
    }

    private static boolean isForwardHeader(String headerName, Set<String> whitelist) {
        if (!hasText(headerName) || whitelist == null || whitelist.isEmpty()) {
            return false;
        }
        String normalized = headerName.toLowerCase(Locale.ROOT);
        return whitelist.stream()
                .filter(VersatileRequestExtractor::hasText)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(normalized::equals);
    }

    private static void putHeader(Map<String, String> headers, String headerName, String headerValue) {
        if (!hasText(headerName) || headerValue == null) {
            return;
        }
        headers.keySet().removeIf(existing -> existing.equalsIgnoreCase(headerName));
        headers.put(headerName, headerValue);
    }

    private static Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return copyMap(map);
        }
        return Map.of();
    }

    private static Map<String, Object> copyMap(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return result;
    }

    private static Optional<String> stringValue(Object value) {
        return value != null ? Optional.of(String.valueOf(value)) : Optional.empty();
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private record SemanticInput(String query, String intent) {
    }

    record RemoteRequest(
            String url,
            Map<String, String> headers,
            Map<String, String> params,
            Map<String, Object> body
    ) {
        public RemoteRequest {
            headers = immutableCopy(headers);
            params = immutableCopy(params);
            body = immutableCopy(body);
        }

        private static <K, V> Map<K, V> immutableCopy(Map<K, V> source) {
            return source != null ? Collections.unmodifiableMap(new LinkedHashMap<>(source)) : Map.of();
        }
    }
}
