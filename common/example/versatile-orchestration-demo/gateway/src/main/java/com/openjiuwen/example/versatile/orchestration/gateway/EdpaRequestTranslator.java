/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.orchestration.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.TextPart;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Translates an inbound {@link EdpaRequest} into an outbound {@link MessageSendParams}
 * consumed by the a2a-java SDK client.
 *
 * <ul>
 *   <li>{@code message.contextId} ← {@code body.conversation_id}</li>
 *   <li>{@code message.parts[0]} ← a {@link TextPart} carrying the JSON object
 *       {@code {"query":...,"intent":...}} built from {@code input.query}/{@code custom_data.inputs.query}
 *       and the matching intent tiers — this is the domain contract with plan-agent's message parser</li>
 *   <li>{@code message.taskId} ← {@link ResumeStateStore#openTaskId(String)} (only on a resume round)</li>
 *   <li>{@code message.messageId} ← a fresh {@code UUID} (A2A spec requires a non-null id)</li>
 *   <li>{@code metadata.body} ← the full EDPA body verbatim</li>
 *   <li>{@code metadata.headers} ← inbound transport headers filtered to the configured whitelist</li>
 *   <li>{@code metadata.query} ← the inbound URL query params ({@code workspace_id}, {@code type})</li>
 * </ul>
 *
 * @since 2026-07-09
 */
@Component
public class EdpaRequestTranslator {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ResumeStateStore store;
    private final GatewayProperties properties;

    public EdpaRequestTranslator(ResumeStateStore store, GatewayProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    /**
     * Translate an inbound EDPA request into the A2A {@link MessageSendParams} sent to the plan-agent.
     *
     * @param request     the inbound EDPA request to translate
     * @param headers     inbound transport headers (from the posted custom_data payload), whitelist-filtered
     *                    into {@code metadata.headers}
     * @param queryParams inbound URL query params ({@code workspace_id}, {@code type}), carried verbatim
     *                    into {@code metadata.query}
     * @return the A2A message send params for the plan-agent
     */
    public MessageSendParams translate(EdpaRequest request, Map<String, Object> headers,
                                       Map<String, Object> queryParams) {
        String contextId = request.conversationId();
        String query = request.extractQuery();
        String intent = request.extractIntent();
        String taskId = store.openTaskId(contextId).orElse(null);
        Map<String, String> forwarded = EdpaRequest.forwardHeaders(headers, properties.forwardHeaderWhitelist());

        Message.Builder messageBuilder = Message.builder()
                .role(Message.Role.ROLE_USER)
                .messageId(UUID.randomUUID().toString())
                .contextId(contextId)
                .parts(List.of(new TextPart(textPayload(query, intent))));
        if (taskId != null) {
            messageBuilder.taskId(taskId);
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("body", request.body() == null ? Map.of() : request.body());
        metadata.put("headers", forwarded == null ? Map.of() : forwarded);
        metadata.put("query", queryParams == null ? Map.of() : queryParams);

        return MessageSendParams.builder()
                .message(messageBuilder.build())
                .metadata(metadata)
                .build();
    }

    private static String textPayload(String query, String intent) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("query", query == null ? "" : query);
        payload.put("intent", intent == null ? "" : intent);
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return query == null ? "" : query;
        }
    }
}
