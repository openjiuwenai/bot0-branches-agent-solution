/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.orchestration.gateway;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Inbound EDPA request body posted to
 * {@code /v1/{projectId}/agents/{agentId}/conversations/{cid}} — the openjiuwen EDPA controller
 * contract. Flat snake_case form, e.g.:
 * <pre>{@code
 * {
 *   "role_name": "手机银行",
 *   "input": { "query": "查尾号为4241的卡的余额" },
 *   "agent_id": "fcbcd0ce-...", "role_id": "1", "stream": true,
 *   "conversation_id": "53678ff7-...",
 *   "timeout": "300",
 *   "custom_data": { "inputs": { "query": "...", "intent": "", "wap_userName": "张三" } }
 * }
 * }</pre>
 *
 * <p>Only what the {@link EdpaRequestTranslator} needs is modelled; everything else
 * ({@code role_name}, {@code agent_id}, {@code role_id}, {@code stream}, {@code timeout}, extra
 * {@code custom_data.inputs} entries) is carried but ignored. The whole JSON object is bound to
 * {@link #body} via a delegating {@link JsonCreator}, so unknown top-level fields are tolerated.
 *
 * <p>Query extraction (mirrors the EDPA convention the agent-solution VersatileRequestExtractor reads):
 * <ol>
 *   <li>{@code input.query} (the primary field)</li>
 *   <li>{@code custom_data.inputs.query} (fallback when {@code input.query} is absent/blank)</li>
 * </ol>
 */
public record EdpaRequest(Map<String, Object> body) {

    public EdpaRequest {
        body = body == null ? Map.of() : body;
    }

    /**
     * Delegating creator: bind the whole flat JSON object to {@link #body}.
     *
     * @param body the flat EDPA JSON object
     * @return an {@link EdpaRequest} wrapping the given body
     */
    @JsonCreator
    public static EdpaRequest of(Map<String, Object> body) {
        return new EdpaRequest(body);
    }

    /**
     * EDPA {@code conversation_id} → A2A {@code contextId} (the gateway's default mapping).
     *
     * @return the {@code conversation_id} string, or {@code ""} when absent
     */
    public String conversationId() {
        return str(body.get("conversation_id"));
    }

    /**
     * User utterance, tiered: {@code input.query} first, then {@code custom_data.inputs.query}.
     * Returns {@code ""} when neither is present.
     *
     * @return the user query, or {@code ""} when neither tier carries one
     */
    public String extractQuery() {
        String primary = str(asMap(body.get("input")).get("query"));
        if (notBlank(primary)) {
            return primary;
        }
        Map<String, Object> inputs = asMap(asMap(body.get("custom_data")).get("inputs"));
        String fallback = str(inputs.get("query"));
        return notBlank(fallback) ? fallback : "";
    }

    /**
     * User intent, tiered the same way as {@link #extractQuery()}: {@code input.intent} first, then
     * {@code custom_data.inputs.intent}. Returns {@code ""} when neither is present. Carried into the
     * A2A {@code message.parts[0].text} alongside the query (the plan-agent reads them together as a
     * JSON object, mirroring the agent-solution A2A demo contract).
     *
     * @return the user intent, or {@code ""} when neither tier carries one
     */
    public String extractIntent() {
        String primary = str(asMap(body.get("input")).get("intent"));
        if (notBlank(primary)) {
            return primary;
        }
        Map<String, Object> inputs = asMap(asMap(body.get("custom_data")).get("inputs"));
        String fallback = str(inputs.get("intent"));
        return notBlank(fallback) ? fallback : "";
    }

    /**
     * Optional top-level {@code headers} map carried inside the EDPA body, filtered to the whitelist
     * (case-insensitive). Thin wrapper over {@link #forwardHeaders(Map, Set)} reading from
     * {@code body.headers}; the translator filters the inbound transport headers directly via the
     * static method instead.
     *
     * @param whitelist the header names to forward (case-insensitive)
     * @return the filtered header map, empty when nothing matches
     */
    public Map<String, String> headersToForward(Set<String> whitelist) {
        return forwardHeaders(asMap(body.get("headers")), whitelist);
    }

    /**
     * Filter a header source map to the whitelist (case-insensitive match on key). Returns an empty
     * map when the whitelist is null/empty or the source is null — forwarding is opt-in per header.
     *
     * @param source    the candidate header map to filter (may be null)
     * @param whitelist the header names to keep (case-insensitive; may be null/empty)
     * @return the filtered header map, empty when the whitelist or source is null/empty
     */
    public static Map<String, String> forwardHeaders(Map<String, Object> source, Set<String> whitelist) {
        Map<String, String> out = new LinkedHashMap<>();
        if (whitelist == null || whitelist.isEmpty() || source == null) {
            return out;
        }
        Set<String> lower = new java.util.HashSet<>();
        for (String w : whitelist) {
            if (w != null) {
                lower.add(w.toLowerCase(java.util.Locale.ROOT));
            }
        }
        for (Map.Entry<String, Object> e : source.entrySet()) {
            if (e.getKey() != null && e.getValue() != null
                    && lower.contains(e.getKey().toLowerCase(java.util.Locale.ROOT))) {
                out.put(e.getKey(), String.valueOf(e.getValue()));
            }
        }
        return out;
    }

    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() != null) {
                    out.put(String.valueOf(e.getKey()), e.getValue());
                }
            }
            return out;
        }
        return Map.of();
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
