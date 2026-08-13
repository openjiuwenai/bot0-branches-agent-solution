/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.buscallee;

import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Deterministic target-side handler for Runtime and agent-client E2E scenarios.
 *
 * @since 2026-08-12
 */
final class CalleeAgentHandler implements AgentHandler {
    static final String INPUT_REQUIRED_TRIGGER = "request target approval";
    static final String CLIENT_VERIFICATION_INPUT_TRIGGER = "Please calculate 1+1 through Agent B.";
    static final String CLIENT_TOOL_SEQUENCE_CONVERSATION = "conv-stream-1";
    static final int TARGET_STREAM_CHUNKS = 6;
    static final long TARGET_STREAM_DELAY_MILLIS = 300L;

    private static final String INTERRUPT_META = "_interrupt";

    private final int streamChunks;
    private final long streamDelayMillis;

    private CalleeAgentHandler(int streamChunks, long streamDelayMillis) {
        this.streamChunks = streamChunks;
        this.streamDelayMillis = streamDelayMillis;
    }

    static CalleeAgentHandler standard() {
        return new CalleeAgentHandler(TARGET_STREAM_CHUNKS, TARGET_STREAM_DELAY_MILLIS);
    }

    static CalleeAgentHandler withStream(int streamChunks, long streamDelayMillis) {
        return new CalleeAgentHandler(streamChunks, streamDelayMillis);
    }

    @Override
    public QueryResponse query(ServeRequest request) {
        if (CLIENT_TOOL_SEQUENCE_CONVERSATION.equals(request.getConversationId())) {
            return clientToolSequenceResponse(request);
        }
        if (requiresUserInput(request.lastUserQuery())) {
            return inputRequired(request);
        }
        return response(request, "target runtime received: " + request.lastUserQuery());
    }

    @Override
    public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
        if (CLIENT_TOOL_SEQUENCE_CONVERSATION.equals(request.getConversationId())) {
            streamClientToolSequence(request, observer);
            return;
        }
        if (requiresUserInput(request.lastUserQuery())) {
            observer.onNext(new QueryChunk(QueryChunk.TYPE_INTERRUPT, inputInterrupt()));
            return;
        }
        streamTargetResponse(request, observer);
    }

    private QueryResponse clientToolSequenceResponse(ServeRequest request) {
        String previousToolName = previousInterruptToolName(request).orElse("");
        if ("readPage".equals(previousToolName)) {
            return new QueryResponse(Map.of("role", "assistant", "_interrupt",
                    clientToolInterrupt("call-submitorder-1", "submitOrder", "please submit the order")),
                    request.getConversationId());
        }
        return response(request, "order submitted successfully");
    }

    private void streamClientToolSequence(ServeRequest request, QueryStreamObserver observer) {
        Optional<String> previousToolName = previousInterruptToolName(request);
        if (previousToolName.isEmpty()) {
            observer.onNext(new QueryChunk(QueryChunk.TYPE_INTERRUPT,
                    clientToolInterrupt("call-readpage-1", "readPage", "please read the page")));
            return;
        }
        if (previousToolName.filter("readPage"::equals).isPresent()) {
            observer.onNext(new QueryChunk(QueryChunk.TYPE_INTERRUPT,
                    clientToolInterrupt("call-submitorder-1", "submitOrder", "please submit the order")));
            return;
        }
        observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK,
                response(request, "order submitted successfully").getResult()));
        observer.onComplete();
    }

    private void streamTargetResponse(ServeRequest request, QueryStreamObserver observer) {
        for (int index = 1; index <= streamChunks; index++) {
            if (observer.isCancelled()) {
                return;
            }
            observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK,
                    response(request, "target stream chunk " + index + "/" + streamChunks + ": "
                            + request.lastUserQuery()).getResult()));
            if (index < streamChunks && !pause(observer)) {
                return;
            }
        }
        observer.onComplete();
    }

    private boolean pause(QueryStreamObserver observer) {
        try {
            Thread.sleep(streamDelayMillis);
            return true;
        } catch (InterruptedException interrupted) {
            observer.onError(interrupted);
            return false;
        }
    }

    private static boolean requiresUserInput(String input) {
        return INPUT_REQUIRED_TRIGGER.equals(input) || CLIENT_VERIFICATION_INPUT_TRIGGER.equals(input);
    }

    private static Optional<String> previousInterruptToolName(ServeRequest request) {
        if (request.getMetadata() == null) {
            return Optional.empty();
        }
        Object value = request.getMetadata().get(INTERRUPT_META);
        if (!(value instanceof Map<?, ?> interrupt)) {
            return Optional.empty();
        }
        return interrupt.get("toolName") instanceof String toolName ? Optional.of(toolName) : Optional.empty();
    }

    private static Map<String, Object> clientToolInterrupt(String toolCallId, String toolName, String message) {
        Map<String, Object> arguments = switch (toolName) {
            case "readPage" -> Map.of("pageId", "page-1");
            case "submitOrder" -> Map.of("orderId", "order-1");
            default -> Map.of();
        };
        return Map.of(
                "toolCallId", toolCallId,
                "toolName", toolName,
                "message", message,
                "context", Map.of("_interrupt_kind", "client_tool", "arguments", arguments));
    }

    private static QueryResponse response(ServeRequest request, String content) {
        return new QueryResponse(Map.of("role", "assistant", "content", content + echoSuffix(request),
                "tenantId", request.getTenantId() == null ? "" : request.getTenantId()),
                request.getConversationId());
    }

    private static String echoSuffix(ServeRequest request) {
        StringBuilder suffix = new StringBuilder();
        if (request.getMetadata() != null) {
            Object attrs = request.getMetadata().get("attributes");
            if (attrs instanceof Map<?, ?> attrMap && attrMap.get("traceId") instanceof String traceId
                    && !traceId.isBlank()) {
                suffix.append("[trace=").append(traceId).append("]");
            }
            if (request.getMetadata().get("agentId") instanceof String agentId && !agentId.isBlank()) {
                suffix.append("[agent=").append(agentId).append("]");
            }
        }
        return suffix.toString();
    }

    private static QueryResponse inputRequired(ServeRequest request) {
        return new QueryResponse(Map.of("role", "assistant", "_interrupt", inputInterrupt()),
                request.getConversationId());
    }

    private static Map<String, Object> inputInterrupt() {
        return Map.of("message", "Approve target runtime operation?",
                "context", Map.of("_interrupt_kind", "user_input"));
    }
}
