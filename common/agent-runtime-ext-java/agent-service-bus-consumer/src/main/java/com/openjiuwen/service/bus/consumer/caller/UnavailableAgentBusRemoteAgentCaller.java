/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.caller;

import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCall;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCallOutcome;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Fail-closed Caller used while the Agent Bus caller role is not assembled.
 *
 * @since 2026-08-04
 */
public final class UnavailableAgentBusRemoteAgentCaller implements RemoteAgentCaller {
    private static final String MESSAGE = "Agent Bus caller role is unavailable; "
            + "Runtime-to-Runtime HTTP fallback is disabled";

    @Override
    public CompletableFuture<RemoteCallOutcome> callOutcome(RemoteCall call, QueryStreamObserver streamObserver,
            Consumer<String> remoteTaskIdObserver) {
        return CompletableFuture.failedFuture(new IllegalStateException(MESSAGE));
    }

    @Override
    public boolean supported(String agentName) {
        return agentName != null && !agentName.isBlank();
    }
}
