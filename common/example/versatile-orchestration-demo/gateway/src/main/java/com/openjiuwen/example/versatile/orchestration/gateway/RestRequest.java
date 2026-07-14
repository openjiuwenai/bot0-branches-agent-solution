/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.orchestration.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Outbound REST {@code POST /v1/query} body built by the gateway when
 * {@link GatewayProperties.Protocol#REST} is active. Mirrors the contract documented in the
 * agent-solution {@code agentcore-ext-remote-a2a-tool-demo} README: the runtime's
 * {@code QueryMvcController} stores this raw body whole as {@code ServeRequest.metadata.body}, so
 * the plan-agent's orchestrator receives the same {@code metadata.body.custom_data} it would under
 * A2A — only the transport envelope differs.
 *
 * <p>Field sources (the EDPA envelope rebuilt by {@link EdpaEnvelopeBuilder} already defaulted
 * {@code role_name}/{@code role_id}/{@code timeout}/{@code stream}/{@code conversation_id}/
 * {@code agent_id}/{@code custom_data}, so those are reused verbatim — guaranteeing the A2A and
 * REST bodies carry identical caller-identity):
 * <ul>
 *   <li>{@code conversation_id} / {@code agent_id} / {@code stream} / {@code timeout} /
 *       {@code role_id} / {@code role_name} / {@code custom_data} ← the rebuilt EDPA body;</li>
 *   <li>{@code messages[0].content} ← a JSON object string {@code {"query":...,"intent":...}}
 *       (parallel to the A2A {@code message.parts[0].text});</li>
 *   <li>{@code input} ← {@code {query,intent}} (the two tiers the plan-agent's
 *       {@code EdpaRequest} extractors read);</li>
 *   <li>{@code user_id} / {@code space_id} ← REST-only caller identity from
 *       {@link GatewayProperties} (defaulted here when absent).</li>
 * </ul>
 *
 * <p>No {@code taskId} is emitted — REST resume is routed by {@code conversation_id} (the
 * runtime's shadow-task), so the {@link ResumeStateStore} cache is not consulted.
 *
 * @since 2026-07-08
 */
public final class RestRequest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String DEFAULT_USER_ID = "demo-user";
    private static final String DEFAULT_SPACE_ID = "demo-space";

    private final Map<String, Object> body;

    private RestRequest(Map<String, Object> body) {
        this.body = body;
    }

    /**
     * The loose REST body map, ready for JSON serialization.
     *
     * @return the REST body map
     */
    public Map<String, Object> body() {
        return body;
    }

    /**
     * Build the REST body from the rebuilt EDPA envelope + REST-only properties.
     *
     * @param full       the rebuilt full EDPA envelope
     * @param properties the gateway properties supplying REST-only fields (user/space id)
     * @return the REST request wrapping the assembled body
     */
    public static RestRequest from(EdpaRequest full, GatewayProperties properties) {
        Map<String, Object> edpa = full.body();
        String query = full.extractQuery();
        String intent = full.extractIntent();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("conversation_id", full.conversationId());
        body.put("stream", edpa.getOrDefault("stream", true));
        body.put("user_id", orDefault(properties.userId(), DEFAULT_USER_ID));
        body.put("space_id", orDefault(properties.spaceId(), DEFAULT_SPACE_ID));
        body.put("messages", List.of(message(query, intent)));
        body.put("agent_id", edpa.get("agent_id"));
        // input carries the two tiers the plan-agent reads; extras (e.g. wap_userName) ride in
        // custom_data.inputs below, as under A2A.
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("query", query);
        input.put("intent", intent);
        body.put("input", input);
        body.put("timeout", edpa.get("timeout"));
        body.put("role_id", edpa.get("role_id"));
        body.put("role_name", edpa.get("role_name"));
        body.put("custom_data", edpa.getOrDefault("custom_data", Map.of()));
        return new RestRequest(body);
    }

    /**
     * One user message whose content is the stringified {@code {"query":...,"intent":...}}.
     *
     * @param query  the user query
     * @param intent the user intent
     * @return a single user message map with stringified content
     */
    private static Map<String, Object> message(String query, String intent) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "user");
        msg.put("content", textPayload(query, intent));
        return msg;
    }

    /**
     * Serialize {@code {"query":...,"intent":...}} as the message content (parallel to the A2A
     * {@code parts[0].text}). Deliberately a separate copy of the A2A helper — the two are
     * independent wire contracts that happen to coincide today; changing one must not silently
     * change the other. Falls back to the raw query if serialization fails (never happens for a
     * plain string map).
     *
     * @param query  the user query
     * @param intent the user intent
     * @return the JSON string {@code {"query":...,"intent":...}}, or the raw query on failure
     */
    private static String textPayload(String query, String intent) {
        try {
            Map<String, String> payload = new LinkedHashMap<>();
            payload.put("query", query == null ? "" : query);
            payload.put("intent", intent == null ? "" : intent);
            return MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return query == null ? "" : query;
        }
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
