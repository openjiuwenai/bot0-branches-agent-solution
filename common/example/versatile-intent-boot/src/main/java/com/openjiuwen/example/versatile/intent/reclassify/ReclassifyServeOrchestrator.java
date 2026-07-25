/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */
package com.openjiuwen.example.versatile.intent.reclassify;

import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import com.openjiuwen.service.spec.spi.ServeOrchestrator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Decorator around {@link ServeOrchestrator} that retries L1 intent
 * recognition with augmented context when L2 returns a
 * {@code VERSATILE_INTENT_AMBIGUOUS} error.
 *
 * <p>Streaming path: each attempt is issued with a fresh
 * {@link ReclassifyObserver}; if the observer reports an ambiguous payload
 * (and the local counter has not exceeded {@code max-reclassify}), a new
 * augmented {@link ServeRequest} is built and the wrapped orchestrator is
 * invoked again. Non-ambiguous errors are forwarded to the downstream
 * observer verbatim.
 *
 * <p>Non-streaming path: the runtime propagates L2 failures as
 * {@link RuntimeException}s (directly or wrapped); the decorator catches
 * {@code RuntimeException}, walks the cause chain via
 * {@link AmbiguousPayloadParser} to find the ambiguous payload, and retries.
 *
 * <p>The counter is a method-local variable — there is no cross-request
 * state. {@code max-reclassify=0} disables retry and produces
 * {@code VERSATILE_INTENT_RECLASSIFY_LIMIT} immediately on the first
 * ambiguous signal.
 *
 * @since 2026-07-24
 */
public class ReclassifyServeOrchestrator implements ServeOrchestrator {
    private static final String LIMIT_CODE = "VERSATILE_INTENT_RECLASSIFY_LIMIT";

    private final ServeOrchestrator wrapped;
    private final ReclassifyProperties properties;

    public ReclassifyServeOrchestrator(ServeOrchestrator wrapped, ReclassifyProperties properties) {
        this.wrapped = Objects.requireNonNull(wrapped, "wrapped");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Override
    public QueryResponse query(ServeRequest request) {
        if (!properties.isEnabled()) {
            return wrapped.query(request);
        }
        int reclassifyCount = 0;
        ServeRequest current = request;
        while (true) {
            QueryResponse response;
            try {
                response = wrapped.query(current);
            } catch (RuntimeException ex) {
                Optional<AmbiguousPayload> ambiguous = AmbiguousPayloadParser.fromThrowable(ex);
                if (ambiguous.isEmpty()) {
                    throw ex;
                }
                reclassifyCount++;
                if (reclassifyCount > properties.getMaxReclassify()) {
                    throw new IllegalStateException(LIMIT_CODE + ": max=" + properties.getMaxReclassify(), ex);
                }
                current = buildAugmentedRequest(request, ambiguous.get());
                continue;
            }
            return response;
        }
    }

    @Override
    public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
        if (!properties.isEnabled()) {
            wrapped.streamQuery(request, observer);
            return;
        }
        int reclassifyCount = 0;
        ServeRequest current = request;
        while (true) {
            ReclassifyObserver probe = new ReclassifyObserver(observer);
            wrapped.streamQuery(current, probe);
            if (!probe.ambiguousTriggered()) {
                return;
            }
            reclassifyCount++;
            if (reclassifyCount > properties.getMaxReclassify()) {
                observer.onError(new IllegalStateException(
                        LIMIT_CODE + ": max=" + properties.getMaxReclassify()));
                return;
            }
            current = buildAugmentedRequest(request, probe.ambiguousPayload().orElseThrow());
        }
    }

    @Override
    public void cancelActive(String conversationId) {
        wrapped.cancelActive(conversationId);
    }

    @Override
    public void resetConversation(String conversationId) {
        wrapped.resetConversation(conversationId);
    }

    private static ServeRequest buildAugmentedRequest(ServeRequest original, AmbiguousPayload payload) {
        ServeRequest augmented = new ServeRequest();
        augmented.setConversationId(original.getConversationId());
        augmented.setUserId(original.getUserId());
        augmented.setSpaceId(original.getSpaceId());
        augmented.setTenantId(original.getTenantId());
        augmented.setStream(original.isStream());
        augmented.setMetadata(new LinkedHashMap<>(original.getMetadata()));
        List<Map<String, Object>> messages = new ArrayList<>(original.getMessages());
        Map<String, Object> assistantMsg = new LinkedHashMap<>();
        assistantMsg.put("role", "assistant");
        assistantMsg.put("content", payload.responseContent());
        messages.add(assistantMsg);
        augmented.setMessages(messages);
        return augmented;
    }
}
