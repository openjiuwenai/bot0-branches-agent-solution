/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.buscaller;

import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Source-side handler that delegates every initial request to the callee Runtime.
 *
 * @since 2026-08-12
 */
final class CallerAgentHandler implements AgentHandler {
    static final String TARGET_AGENT_ID = "target-agent";
    static final String TOOL_CALL_ID = "call-target-runtime";

    @Override
    public QueryResponse query(ServeRequest request) {
        Optional<Object> remoteResult = remoteResult(request);
        if (remoteResult.isPresent()) {
            return response(request, "source runtime received remote result: " + remoteResult.get());
        }
        return new QueryResponse(Map.of("role", "assistant", "_interrupt", interrupt(request)),
                request.getConversationId());
    }

    @Override
    public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
        if (remoteResult(request).isEmpty()) {
            observer.onNext(new QueryChunk(QueryChunk.TYPE_INTERRUPT, interrupt(request)));
            return;
        }
        observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, query(request).getResult()));
        observer.onComplete();
    }

    private static QueryResponse response(ServeRequest request, String content) {
        return new QueryResponse(Map.of("role", "assistant", "content", content,
                "tenantId", request.getTenantId() == null ? "" : request.getTenantId()),
                request.getConversationId());
    }

    private static Map<String, Object> interrupt(ServeRequest request) {
        return Map.of("batchId", "runtime-bus-runtime-e2e", "items", List.of(Map.of(
                "index", 0,
                "toolCallId", TOOL_CALL_ID,
                "toolName", "delegate-target-runtime",
                "message", request.lastUserQuery(),
                "context", Map.of("_interrupt_kind", "a2a_delegate", "agentName", TARGET_AGENT_ID))));
    }

    private static Optional<Object> remoteResult(ServeRequest request) {
        if (request.getMetadata() == null) {
            return Optional.empty();
        }
        Object value = request.getMetadata().get("runtime.remoteToolResults");
        if (!(value instanceof Map<?, ?> results)) {
            return Optional.empty();
        }
        return Optional.ofNullable(results.get(TOOL_CALL_ID));
    }
}
