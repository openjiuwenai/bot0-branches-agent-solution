/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.agentfw;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties;
import com.openjiuwen.service.spec.dto.ServeRequest;

import java.util.Collections;
import java.util.LinkedHashMap;
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
        SemanticInput semanticInput = extractSemanticInput(request);
        Map<String, Object> sourceBody = mapValue(request.getMetadata().get("body"));
        Map<String, Object> remoteBody = new LinkedHashMap<>(mapValue(sourceBody.get("custom_data")));
        Map<String, Object> inputs = new LinkedHashMap<>(mapValue(remoteBody.get("inputs")));
        if (hasText(semanticInput.query())) {
            inputs.put("query", semanticInput.query());
        }
        if (hasText(semanticInput.intent())) {
            inputs.put("intent", semanticInput.intent());
        }
        if (!inputs.isEmpty()) {
            remoteBody.put("inputs", inputs);
        }

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

        String url = resolveUrlTemplate(semanticInput.intent()).replace(
                "{conversation_id}", request.getConversationId() != null ? request.getConversationId() : "");
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

    private String resolveUrlTemplate(String intent) {
        if (hasText(intent)) {
            for (VersatileProperties.Endpoint endpoint : properties.getEndpoints()) {
                if (endpoint != null && intent.equals(endpoint.getIntent()) && hasText(endpoint.getUrlTemplate())) {
                    return endpoint.getUrlTemplate();
                }
            }
        }
        if (!hasText(properties.getUrlTemplate())) {
            throw new IllegalArgumentException("openjiuwen.service.versatile.url-template must not be blank");
        }
        return properties.getUrlTemplate();
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
