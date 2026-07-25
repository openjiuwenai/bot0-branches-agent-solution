/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */
package com.openjiuwen.example.versatile.intent.reclassify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.spec.dto.QueryResponse;

import java.util.Map;
import java.util.Optional;

/**
 * Extracts an {@link AmbiguousPayload} from either:
 * <ul>
 *   <li>a {@link QueryResponse} whose {@code content} carries a JSON envelope
 *       with {@code intent_id} matching the configured ambiguous id (the
 *       primary signal path — L2 adapter returns a normal answer chunk whose
 *       envelope traverses HTTP / gateway / batch coordinator unchanged), or</li>
 *   <li>a {@link Throwable}'s message walking its cause chain (legacy path
 *       retained for callers that still surface ambiguous as an exception).</li>
 * </ul>
 *
 * <p>The {@code fromThrowable} path scans each cause's message for the
 * {@code VERSATILE_INTENT_AMBIGUOUS} marker. The parser tolerates prefix/suffix
 * text around the JSON object and tracks string literals and escape sequences
 * so braces inside string values do not confuse the scan.
 *
 * @since 2026-07-24
 */
public final class AmbiguousPayloadParser {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String MARKER = "VERSATILE_INTENT_AMBIGUOUS";

    private AmbiguousPayloadParser() {
    }

    /**
     * Inspects a {@link QueryResponse} for an ambiguous answer envelope.
     *
     * <p>The response's {@code result.content} must be a JSON string with an
     * {@code intent_id} field matching {@code ambiguousIntentId}. The
     * {@code response_content} is extracted from either the top-level
     * {@code response_content} field or the nested {@code payload.content}
     * field (the mock gateway wraps business text under {@code payload.content}).
     *
     * @param response          the query response to inspect, may be {@code null}
     * @param ambiguousIntentId the configured ambiguous intent id (e.g. "1")
     * @return the parsed payload, or empty if the response is not ambiguous
     */
    public static Optional<AmbiguousPayload> fromQueryResponse(QueryResponse response, String ambiguousIntentId) {
        if (response == null || response.getResult() == null) {
            return Optional.empty();
        }
        Object content = (response.getResult() instanceof Map<?, ?> m) ? m.get("content") : null;
        if (!(content instanceof String s) || s.isBlank()) {
            return Optional.empty();
        }
        return fromContentString(s, ambiguousIntentId);
    }

    /**
     * Inspects a chunk's data for an ambiguous answer envelope.
     *
     * <p>Used by the streaming-path observer. Accepts either a {@link Map}
     * envelope directly or a JSON string envelope.
     *
     * @param data              the chunk data
     * @param ambiguousIntentId the configured ambiguous intent id
     * @return the parsed payload, or empty if the data is not an ambiguous envelope
     */
    public static Optional<AmbiguousPayload> fromChunkData(Object data, String ambiguousIntentId) {
        if (data instanceof Map<?, ?> map) {
            return fromEnvelopeMap(map, ambiguousIntentId);
        }
        if (data instanceof String s && !s.isBlank()) {
            return fromContentString(s, ambiguousIntentId);
        }
        return Optional.empty();
    }

    /**
     * Walks the throwable's cause chain and parses the first message
     * containing the ambiguous marker.
     *
     * @param throwable the throwable to inspect, may be {@code null}
     * @return the parsed payload, or empty if no ambiguous marker is found
     *         or the JSON cannot be parsed
     */
    public static Optional<AmbiguousPayload> fromThrowable(Throwable throwable) {
        if (throwable == null) {
            return Optional.empty();
        }
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                Optional<AmbiguousPayload> parsed = parseMessage(message);
                if (parsed.isPresent()) {
                    return parsed;
                }
            }
            current = current.getCause();
        }
        return Optional.empty();
    }

    private static Optional<AmbiguousPayload> fromContentString(String s, String ambiguousIntentId) {
        try {
            JsonNode root = MAPPER.readTree(s);
            if (root == null || !root.isObject()) {
                return Optional.empty();
            }
            return fromEnvelopeNode(root, ambiguousIntentId);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private static Optional<AmbiguousPayload> fromEnvelopeMap(Map<?, ?> map, String ambiguousIntentId) {
        Object intentIdRaw = map.get("intent_id");
        if (!(intentIdRaw instanceof String intentId) || intentId.isBlank()) {
            return Optional.empty();
        }
        if (!intentId.equals(ambiguousIntentId)) {
            return Optional.empty();
        }
        String responseContent = map.get("response_content") instanceof String rc ? rc : "";
        if (responseContent.isBlank() && map.get("payload") instanceof Map<?, ?> payload) {
            responseContent = payload.get("content") instanceof String pc ? pc : "";
        }
        return Optional.of(new AmbiguousPayload(intentId, responseContent, ambiguousIntentId));
    }

    private static Optional<AmbiguousPayload> fromEnvelopeNode(JsonNode node, String ambiguousIntentId) {
        JsonNode intentIdNode = node.path("intent_id");
        if (!intentIdNode.isTextual()) {
            return Optional.empty();
        }
        String intentId = intentIdNode.asText();
        if (!intentId.equals(ambiguousIntentId)) {
            return Optional.empty();
        }
        String responseContent = textOrEmpty(node.get("response_content"));
        if (responseContent.isEmpty()) {
            responseContent = textOrEmpty(node.path("payload").path("content"));
        }
        return Optional.of(new AmbiguousPayload(intentId, responseContent, ambiguousIntentId));
    }

    private static Optional<AmbiguousPayload> parseMessage(String message) {
        int markerIdx = message.indexOf(MARKER);
        if (markerIdx < 0) {
            return Optional.empty();
        }
        // The marker typically appears as a string value inside the JSON
        // object (e.g. {"code":"VERSATILE_INTENT_AMBIGUOUS",...}), so we must
        // find the open-brace that *encloses* it, not the first one after it.
        int[] bounds = findEnclosingObject(message, markerIdx);
        if (bounds == null) {
            return Optional.empty();
        }
        String json = message.substring(bounds[0], bounds[1] + 1);
        try {
            JsonNode node = MAPPER.readTree(json);
            if (node == null || !node.isObject()) {
                return Optional.empty();
            }
            JsonNode code = node.get("code");
            if (code == null || !MARKER.equals(code.asText())) {
                return Optional.empty();
            }
            String intentId = textOrEmpty(node.get("intent_id"));
            String responseContent = textOrEmpty(node.get("response_content"));
            String ambiguousIntentId = textOrEmpty(node.get("ambiguous_intent_id"));
            return Optional.of(new AmbiguousPayload(intentId, responseContent, ambiguousIntentId));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    /**
     * Scans the message forward, tracking string literals and escape
     * sequences, to locate the JSON object that encloses the marker at
     * {@code markerIdx}. Returns the inclusive {@code [openBrace, closeBrace]}
     * indices, or {@code null} if no enclosing object is found.
     *
     * <p>The scan walks every top-level (depth-0) {@code {} ... {@code }}
     * pair; the first pair whose span contains the marker is returned. This
     * correctly handles prefix/suffix text and braces that appear inside
     * string values.
     *
     * @param text       the message text
     * @param markerIdx  the index of the ambiguous marker
     * @return a two-element array {@code [start, end]} or {@code null}
     */
    private static int[] findEnclosingObject(String text, int markerIdx) {
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        int openIdx = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (c == '\\') {
                escape = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == '{') {
                if (depth == 0) {
                    openIdx = i;
                }
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && openIdx >= 0) {
                    if (markerIdx >= openIdx && markerIdx <= i) {
                        return new int[] {openIdx, i};
                    }
                    openIdx = -1;
                }
            }
        }
        return null;
    }

    private static String textOrEmpty(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "";
        }
        return node.isTextual() ? node.asText() : node.toString();
    }
}
