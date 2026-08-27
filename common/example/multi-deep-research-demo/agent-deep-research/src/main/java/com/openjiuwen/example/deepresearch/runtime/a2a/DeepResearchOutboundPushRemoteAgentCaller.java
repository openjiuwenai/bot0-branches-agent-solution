/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.deepresearch.runtime.a2a;

import com.openjiuwen.service.app.controller.a2a.client.A2ARemoteAgentClient;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCall;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCallOutcome;

import jakarta.annotation.PreDestroy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Adds Deep Research's callback coordinates to outbound Search Agent calls.
 *
 * <p>The Runtime's A2A client converts these internal metadata keys into the
 * standard {@code taskPushNotificationConfig} field before sending the wire
 * request. Returning a requested callback is a baseline Runtime capability, so
 * this decorator does not gate the request on the remote Agent Card. Other agents
 * are delegated unchanged.
 *
 * @since 2026-08-04
 */
public final class DeepResearchOutboundPushRemoteAgentCaller implements RemoteAgentCaller {
    static final String SEARCH_AGENT = "search-agent";

    static final String CALLBACK_URL_METADATA = "runtime.a2a.callbackUrl";

    static final String CALLBACK_TOKEN_METADATA = "runtime.a2a.callbackToken";

    static final String CALLBACK_ID_METADATA = "runtime.a2a.callbackId";

    private final RemoteAgentCaller delegate;

    private final String callbackUrl;

    private final Optional<String> callbackToken;

    private final Supplier<String> callbackIdSupplier;

    /**
     * Creates the Search callback decorator.
     *
     * @param delegate Runtime's standard A2A remote caller
     * @param callbackUrl public Deep Research callback endpoint
     * @param callbackToken optional Bearer token Search returns with callbacks
     */
    public DeepResearchOutboundPushRemoteAgentCaller(RemoteAgentCaller delegate, String callbackUrl,
            String callbackToken) {
        this(delegate, callbackUrl, callbackToken, () -> "deep-search-" + UUID.randomUUID());
    }

    DeepResearchOutboundPushRemoteAgentCaller(RemoteAgentCaller delegate, String callbackUrl,
            String callbackToken, Supplier<String> callbackIdSupplier) {
        this.delegate = delegate;
        this.callbackUrl = callbackUrl;
        this.callbackToken = normalizeOptional(callbackToken);
        this.callbackIdSupplier = callbackIdSupplier;
    }

    @Override
    public CompletableFuture<RemoteCallOutcome> callOutcome(RemoteCall call,
            RemoteAgentCaller.EventObserver eventObserver) {
        RemoteCall outboundCall = SEARCH_AGENT.equals(call.agentName()) ? configureSearchCallback(call) : call;
        return delegate.callOutcome(outboundCall, eventObserver);
    }

    private RemoteCall configureSearchCallback(RemoteCall call) {
        Map<String, Object> metadata = new LinkedHashMap<>(call.metadata());
        metadata.remove(CALLBACK_URL_METADATA);
        metadata.remove(CALLBACK_TOKEN_METADATA);
        metadata.remove(CALLBACK_ID_METADATA);
        metadata.put(CALLBACK_URL_METADATA, callbackUrl);
        metadata.put(CALLBACK_ID_METADATA, callbackIdSupplier.get());
        callbackToken.ifPresent(token -> metadata.put(CALLBACK_TOKEN_METADATA, token));
        return new RemoteCall(call.agentName(), call.message(), call.contextId(), call.taskId(), metadata,
                call.messageMetadata(), call.isCallerStreaming());
    }

    private static Optional<String> normalizeOptional(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.trim());
    }

    /**
     * Closes the owned standard Runtime client when the application stops.
     */
    @PreDestroy
    public void shutdown() {
        if (delegate instanceof A2ARemoteAgentClient client) {
            client.shutdown();
        }
    }
}
