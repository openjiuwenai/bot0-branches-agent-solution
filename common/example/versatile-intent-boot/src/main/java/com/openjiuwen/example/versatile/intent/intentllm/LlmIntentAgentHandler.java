/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.intentllm;

import com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AgentHandler variant that classifies intent by calling an LLM directly,
 * producing a2a_delegate / ambiguous shapes compatible with the runtime.
 *
 * @since 0.1.0
 */
public class LlmIntentAgentHandler implements AgentHandler {
    private static final Logger log = LoggerFactory.getLogger(LlmIntentAgentHandler.class);

    private final LlmIntentProperties properties;
    private final String ambiguousIntentId;
    private final LlmIntentClient client;
    private final LlmIntentPromptBuilder promptBuilder;
    private final Map<String, List<Map<String, Object>>> conversationHistory = new ConcurrentHashMap<>();

    public LlmIntentAgentHandler(LlmIntentProperties properties, VersatileProperties versatile) {
        this(properties, versatile, new LlmIntentClient(properties),
                new LlmIntentPromptBuilder(versatile, properties));
    }

    LlmIntentAgentHandler(LlmIntentProperties properties, VersatileProperties versatile,
                          LlmIntentClient client, LlmIntentPromptBuilder promptBuilder) {
        this.properties = properties;
        this.ambiguousIntentId = versatile.getAmbiguousIntentId() == null
                ? "1" : versatile.getAmbiguousIntentId();
        this.client = client;
        this.promptBuilder = promptBuilder;
    }

    @Override
    public QueryResponse query(ServeRequest request) {
        String cid = conversationKey(request.getConversationId());
        List<Map<String, Object>> turns = appendUserTurn(cid, request);
        log.info("LlmIntent query conversation_id={} history={}",
                request.getConversationId(), turns.size());
        LlmIntentResult result = classify(turns);
        String userQuery = request.lastUserQuery() != null ? request.lastUserQuery() : "";
        Map<String, Object> resultMap = switch (result.action()) {
            case CLASSIFY -> LlmChunkShapes.delegateResult(
                    result.agentId(), result.responseContent(), userQuery, false);
            case AMBIGUOUS -> LlmChunkShapes.ambiguousResult(
                    result.responseContent(), ambiguousIntentId);
        };
        return new QueryResponse(resultMap, request.getConversationId());
    }

    @Override
    public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
        String cid = conversationKey(request.getConversationId());
        List<Map<String, Object>> turns = appendUserTurn(cid, request);
        log.info("LlmIntent streamQuery conversation_id={} history={}",
                request.getConversationId(), turns.size());
        try {
            LlmIntentResult result = classify(turns);
            String userQuery = request.lastUserQuery() != null ? request.lastUserQuery() : "";
            QueryChunk chunk = switch (result.action()) {
                case CLASSIFY -> LlmChunkShapes.delegateInterrupt(
                        result.agentId(), result.responseContent(), userQuery, true);
                case AMBIGUOUS -> LlmChunkShapes.ambiguousChunk(
                        result.responseContent(), ambiguousIntentId);
            };
            observer.onNext(chunk);
            observer.onComplete();
        } catch (IllegalStateException e) {
            log.error("LlmIntent streamQuery failed conversation_id={}",
                    request.getConversationId(), e);
            observer.onError(e);
        }
    }

    private LlmIntentResult classify(List<Map<String, Object>> turns) {
        List<Map<String, Object>> messages = promptBuilder.buildConversation(turns);
        String raw = client.complete(messages);
        return LlmIntentResult.parse(raw, ambiguousIntentId);
    }

    /**
     * Appends the current user query to the per-conversation history so the LLM
     * receives prior inputs when classifying a follow-up message.
     *
     * @param cid the conversation key (never blank)
     * @param request the inbound serve request carrying the latest user query
     * @return the full ordered turn list for this conversation
     */
    private List<Map<String, Object>> appendUserTurn(String cid, ServeRequest request) {
        String query = request.lastUserQuery() != null ? request.lastUserQuery() : "";
        List<Map<String, Object>> turns = conversationHistory.computeIfAbsent(cid,
                k -> Collections.synchronizedList(new ArrayList<>()));
        if (!query.isBlank()) {
            turns.add(Map.of("role", "user", "content", query));
        }
        return turns;
    }

    private static String conversationKey(String conversationId) {
        return conversationId == null || conversationId.isBlank() ? "" : conversationId;
    }

    @Override
    public void clearSession(String conversationId) {
        String cid = conversationKey(conversationId);
        if (!cid.isEmpty()) {
            conversationHistory.remove(cid);
        }
    }
}
