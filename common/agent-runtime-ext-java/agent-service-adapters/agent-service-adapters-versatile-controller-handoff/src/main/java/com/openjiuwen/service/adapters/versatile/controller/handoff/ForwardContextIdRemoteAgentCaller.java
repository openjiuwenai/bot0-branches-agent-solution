/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.controller.handoff;

import com.openjiuwen.service.app.controller.a2a.client.A2ARemoteAgentClient;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCall;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCallOutcome;

import jakarta.annotation.PreDestroy;

import java.util.concurrent.CompletableFuture;

/**
 * Rewrites the outbound A2A contextId with the resolved target agentId prefix
 * ({@code <agentId>-<原contextId>}) so the downstream runtime receives a
 * per-target contextId distinct from the local conversation id.
 *
 * <p>The derivation is deterministic — the runtime coordinator recomputes the
 * original contextId for every call of one delegation chain (initial call and
 * INPUT_REQUIRED 续调 alike), so both are rewritten to the same value and the
 * remote task resume chain stays intact. An already-prefixed contextId or a
 * blank one passes through unchanged.
 *
 * @since 0.1.0
 */
public final class ForwardContextIdRemoteAgentCaller implements RemoteAgentCaller {

    private static final String PREFIX_SEPARATOR = "-";

    private final RemoteAgentCaller delegate;

    /**
     * Creates the contextId-prefix decorator.
     *
     * @param delegate Runtime's standard A2A remote caller
     */
    public ForwardContextIdRemoteAgentCaller(RemoteAgentCaller delegate) {
        this.delegate = delegate;
    }

    @Override
    public CompletableFuture<RemoteCallOutcome> callOutcome(RemoteCall call,
            EventObserver eventObserver) {
        return delegate.callOutcome(rewrite(call), eventObserver);
    }

    private static RemoteCall rewrite(RemoteCall call) {
        return new RemoteCall(call.agentName(), call.message(),
                prefixedContextId(call.agentName(), call.contextId()), call.taskId(),
                call.metadata(), call.messageMetadata(), call.isCallerStreaming());
    }

    /** {@code agentId + "-" + contextId}；已带同目标前缀或空白原值不重复改写。 */
    static String prefixedContextId(String agentId, String contextId) {
        if (contextId == null || contextId.isBlank()) {
            return contextId;
        }
        String prefix = agentId + PREFIX_SEPARATOR;
        return contextId.startsWith(prefix) ? contextId : prefix + contextId;
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
