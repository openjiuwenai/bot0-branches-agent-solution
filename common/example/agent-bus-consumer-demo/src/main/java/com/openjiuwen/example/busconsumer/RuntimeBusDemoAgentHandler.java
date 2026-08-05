/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.busconsumer;

import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import java.util.List;
import java.util.Map;

/**
 * Deterministic caller/callee handlers for the Runtime-to-Runtime Agent Bus E2E.
 *
 * @since 2026-08-05
 */
final class RuntimeBusDemoAgentHandler implements AgentHandler {
    static final String TARGET_AGENT_ID = "target-agent";
    static final String TOOL_CALL_ID = "call-target-runtime";
    static final String INPUT_REQUIRED_TRIGGER = "request target approval";

    private final boolean caller;

    private RuntimeBusDemoAgentHandler(boolean caller) {
        this.caller = caller;
    }

    static RuntimeBusDemoAgentHandler caller() {
        return new RuntimeBusDemoAgentHandler(true);
    }

    static RuntimeBusDemoAgentHandler callee() {
        return new RuntimeBusDemoAgentHandler(false);
    }

    @Override
    public QueryResponse query(ServeRequest request) {
        if (!caller) {
            if (INPUT_REQUIRED_TRIGGER.equals(request.lastUserQuery())) {
                return inputRequired(request);
            }
            return response(request, "target runtime received: " + request.lastUserQuery());
        }
        Object remoteResult = remoteResult(request);
        if (remoteResult != null) {
            return response(request, "source runtime received remote result: " + remoteResult);
        }
        return new QueryResponse(Map.of("role", "assistant", "_interrupt", interrupt(request)),
                request.getConversationId());
    }

    @Override
    public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
        if (caller && remoteResult(request) == null) {
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

    private static QueryResponse inputRequired(ServeRequest request) {
        return new QueryResponse(Map.of("role", "assistant", "_interrupt", Map.of(
                "message", "Approve target runtime operation?",
                "context", Map.of("_interrupt_kind", "ask_user"))), request.getConversationId());
    }

    private static Map<String, Object> interrupt(ServeRequest request) {
        return Map.of("batchId", "runtime-bus-runtime-e2e", "items", List.of(Map.of(
                "index", 0,
                "toolCallId", TOOL_CALL_ID,
                "toolName", "delegate-target-runtime",
                "message", request.lastUserQuery(),
                "context", Map.of("_interrupt_kind", "a2a_delegate", "agentName", TARGET_AGENT_ID))));
    }

    private static Object remoteResult(ServeRequest request) {
        if (request.getMetadata() == null) {
            return null;
        }
        Object value = request.getMetadata().get("runtime.remoteToolResults");
        if (!(value instanceof Map<?, ?> results)) {
            return null;
        }
        return results.get(TOOL_CALL_ID);
    }
}
