/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */
package com.openjiuwen.example.versatile.intent.reclassify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;

/**
 * Extracts an {@link AmbiguousPayload} from a {@link Throwable}'s message by
 * walking its cause chain. Each cause's message is scanned for the
 * {@code VERSATILE_INTENT_AMBIGUOUS} marker; when found, the surrounding JSON
 * object is parsed.
 *
 * <p>The parser tolerates prefix/suffix text around the JSON object (the
 * runtime wraps remote-agent failure messages with contextual prefixes). It
 * scans the message for the JSON object that encloses the marker — tracking
 * string literals and escape sequences so braces inside string values do not
 * confuse the scan — and requires that object to be valid JSON containing
 * the four payload fields.
 *
 * @since 2026-07-24
 */
public final class AmbiguousPayloadParser {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String MARKER = "VERSATILE_INTENT_AMBIGUOUS";

    private AmbiguousPayloadParser() {
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
