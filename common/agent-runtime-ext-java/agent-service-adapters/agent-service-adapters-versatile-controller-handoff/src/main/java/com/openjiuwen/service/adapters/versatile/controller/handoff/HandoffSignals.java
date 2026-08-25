/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.controller.handoff;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Upstream not-in-scope signal (二级退回一级, spec 2.2/4.4): a controller handoff message
 * whose type is configured in {@code handoff.signal.handoff-types} produces NO outbound
 * call — the adapter answers its caller with this marker envelope. In a chained
 * invocation (L1&rarr;L2) the envelope rides the normal answer channel back to L1's
 * handoff executor, which detects it and re-runs its own controller for
 * re-recognition; addressed directly, the envelope is the end response and re-routing
 * is the caller's decision.
 *
 * @since 2026-08-19
 */
public final class HandoffSignals {
    /** Envelope discriminator field value. */
    public static final String TYPE_NOT_IN_SCOPE = "versatile_handoff_not_in_scope";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Fallback marker pattern for results prefixed with non-JSON text. */
    private static final Pattern ENVELOPE_TYPE_MARKER = Pattern.compile(
            "\"type\"\\s*:\\s*\"" + TYPE_NOT_IN_SCOPE + "\"");

    private HandoffSignals() {
    }

    /**
     * Builds the marker envelope for a not-in-scope handoff.
     *
     * @param handoff the classified handoff message
     * @return JSON envelope string
     */
    public static String notInScopeEnvelope(IntentHandoff handoff) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", TYPE_NOT_IN_SCOPE);
        payload.put("handoff_type", handoff.handoffType());
        if (handoff.intentId() != null) {
            payload.put("intent", handoff.intentId());
        }
        if (handoff.businessDomain() != null) {
            payload.put("domain", handoff.businessDomain());
        }
        payload.put("message", "request not in this business domain");
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (IOException ex) {
            return "{\"type\":\"" + TYPE_NOT_IN_SCOPE + "\"}";
        }
    }

    /**
     * Detects the marker envelope in a downstream answer.
     *
     * <p>The result may carry more than the envelope: in streaming mode frames emitted
     * before the not-in-scope signal (e.g. partial business answers that bypass
     * result-node-name extraction) ride the same answer channel, and the caller's task
     * result is the concatenation of all business artifacts. The envelope must still
     * win, so this scans every top-level JSON value in the result instead of requiring
     * the whole string to be exactly one envelope object; results prefixed with
     * non-JSON text fall back to a delimited marker match.
     *
     * @param result the downstream {@code RemoteCallOutcome.result} (may be {@code null})
     * @return {@code true} when the result carries the not-in-scope marker
     */
    public static boolean isNotInScope(String result) {
        if (result == null || result.isBlank()) {
            return false;
        }
        try (JsonParser parser = MAPPER.getFactory().createParser(result)) {
            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                if (token != JsonToken.START_OBJECT) {
                    parser.skipChildren();
                    continue;
                }
                JsonNode node = parser.readValueAsTree();
                if (node.isObject() && TYPE_NOT_IN_SCOPE.equals(node.path("type").asText(null))) {
                    return true;
                }
            }
        } catch (IOException ex) {
            return ENVELOPE_TYPE_MARKER.matcher(result).find();
        }
        return false;
    }
}
