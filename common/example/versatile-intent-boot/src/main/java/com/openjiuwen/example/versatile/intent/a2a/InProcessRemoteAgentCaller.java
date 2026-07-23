/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.a2a;

import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCall;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentException;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Dev/test {@link RemoteAgentCaller} that invokes a target {@link AgentHandler}
 * bean directly (no HTTP). Consumes {@link RemoteAgentCall#responseContent()}
 * to append an assistant message to the forwarded {@link ServeRequest#messages}.
 *
 * <p>Lives under {@code src/main/java} per L2 §5.4.1 so it can be activated
 * via the {@code dev-inprocess} profile (L2 §5.5.3 方案 A). Because this caller
 * needs a map from {@code agentId} to the target {@code AgentHandler} bean
 * (one per layer in a multi-layer in-process chain), it cannot be auto-wired
 * as a plain {@code @Component} — deployments register it via an explicit
 * {@code @Bean} method under the {@code dev-inprocess} profile.
 *
 * <p>Cannot discover HTTP-layer issues (header passing, SSE encoding, Agent
 * Card parsing) — for that, use the multi-port local runtime method
 * (L2 §5.5.3 方案 B).
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
        boolean streaming = call.streaming();
        AgentHandler target = handlersByAgentId.get(call.agentId());
        if (target == null) {
            RemoteAgentException ex = new RemoteAgentException(
                    "VERSATILE_INPROCESS_AGENT_NOT_FOUND: " + call.agentId(), null);
            if (streaming) {
                observer.onError(ex);
                return;
            }
            throw ex;
        }
        ServeRequest forwarded = ForwardedServeRequests.build(call.serveRequest(), call.responseContent());
        log.info("InProcess call agent={} streaming={} appendedMessages={}", call.agentId(), streaming,
                forwarded.getMessages().size() - call.serveRequest().getMessages().size());
        if (streaming) {
            target.streamQuery(forwarded, observer);
            return;
        }
        try {
            QueryResponse response = target.query(forwarded);
            observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, toEnvelope(response.getResult())));
            if (!observer.isCancelled()) {
                observer.onComplete();
            }
        } catch (RuntimeException e) {
            throw new RemoteAgentException(
                    "InProcess call to " + call.agentId() + " failed", e);
        }
    }

    private static Map<String, Object> toEnvelope(Object result) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("type", "answer");
        if (result instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                envelope.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        } else {
            envelope.put("output", result == null ? "" : result);
        }
        return envelope;
    }

    @Override
    public boolean supported(String agentId) {
        return agentId != null && handlersByAgentId.containsKey(agentId);
    }
}
