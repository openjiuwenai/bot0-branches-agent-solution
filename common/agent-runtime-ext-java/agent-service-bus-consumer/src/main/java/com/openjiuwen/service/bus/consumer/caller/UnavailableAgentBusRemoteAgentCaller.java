/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.caller;

import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCall;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCallOutcome;

import java.util.concurrent.CompletableFuture;

/**
 * Fail-closed Caller used while the Agent Bus caller role is not assembled.
 *
 * @since 2026-08-04
 */
public final class UnavailableAgentBusRemoteAgentCaller implements RemoteAgentCaller {
    private static final String MESSAGE = "Agent Bus caller role is unavailable; "
            + "Runtime-to-Runtime HTTP fallback is disabled";

    @Override
    public CompletableFuture<RemoteCallOutcome> callOutcome(RemoteCall call, EventObserver eventObserver) {
        return CompletableFuture.failedFuture(new IllegalStateException(MESSAGE));
    }
}
