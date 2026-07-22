/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.a2a;

import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCall;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Test/dev {@link RemoteAgentCaller} that invokes a target {@link AgentHandler}
 * bean directly (no HTTP). Consumes {@link RemoteAgentCall#responseContent()}
 * to append an assistant message to the forwarded {@link ServeRequest#messages}.
 *
 * <p>Test scope: lives under {@code src/test/java/} per spec §5.4.1. Used by
 * {@code VersatileIntentFlowIntegrationTest} to verify the L1→L2→downstream
 * chain without spinning up multiple HTTP services. Cannot discover HTTP-layer
 * issues (header passing, SSE encoding, Agent Card parsing) — for that, use
 * the multi-port local runtime method (spec §5.5.3 方案 B).
 *
 * @since 0.1.0
 */
public class InProcessRemoteAgentCaller implements RemoteAgentCaller {
    private static final Logger log = LoggerFactory.getLogger(InProcessRemoteAgentCaller.class);

    private final Map<String, AgentHandler> handlersByAgentId;

    /**
     * Constructs the in-process caller.
     *
     * @param handlersByAgentId map from agentId to the target AgentHandler
     */
    public InProcessRemoteAgentCaller(Map<String, AgentHandler> handlersByAgentId) {
        this.handlersByAgentId = Map.copyOf(Objects.requireNonNull(handlersByAgentId));
    }

    @Override
    public void call(RemoteAgentCall call, QueryStreamObserver observer) {
        AgentHandler target = handlersByAgentId.get(call.agentId());
        if (target == null) {
            observer.onError(new IllegalStateException(
                    "VERSATILE_INPROCESS_AGENT_NOT_FOUND: " + call.agentId()));
            return;
        }
        ServeRequest forwarded = buildForwardedServeRequest(call.serveRequest(), call.responseContent());
        log.info("InProcess call agent={} appendedMessages={}", call.agentId(),
                forwarded.getMessages().size() - call.serveRequest().getMessages().size());
        target.streamQuery(forwarded, observer);
    }

    @Override
    public boolean supported(String agentId) {
        return agentId != null && handlersByAgentId.containsKey(agentId);
    }

    static ServeRequest buildForwardedServeRequest(ServeRequest original, String responseContent) {
        ServeRequest forwarded = new ServeRequest();
        forwarded.setConversationId(original.getConversationId());
        forwarded.setUserId(original.getUserId());
        forwarded.setSpaceId(original.getSpaceId());
        forwarded.setTenantId(original.getTenantId());
        forwarded.setStream(original.isStream());
        forwarded.setMetadata(original.getMetadata());
        List<Map<String, Object>> messages = new ArrayList<>(original.getMessages());
        if (responseContent != null && !responseContent.isBlank()) {
            Map<String, Object> assistant = new LinkedHashMap<>();
            assistant.put("role", "assistant");
            assistant.put("content", responseContent);
            messages.add(assistant);
        }
        forwarded.setMessages(messages);
        return forwarded;
    }
}
