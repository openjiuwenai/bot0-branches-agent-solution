/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.bus.wait;

import com.openjiuwen.bus.forwarding.spi.InvocationResponseStatus;
import com.openjiuwen.gateway.governance.idempotency.IdempotencyRule;

/**
 * Maps fold results to G4 complete/abort (FEAT-012 §4.6.3).
 *
 * @since 2026-07-24
 */
public class G4BusWiring {
    private final IdempotencyRule g4;

    /**
     * Creates wiring over the given G4 idempotency rule.
     *
     * @param g4 idempotency rule wired for BUS sync path
     */
    public G4BusWiring(IdempotencyRule g4) {
        this.g4 = g4;
    }

    /**
     * Terminal fold (RESPONSE/REJECTED/FAILED) or ACCEPTED-timeout → complete (replayable).
     *
     * @param tenantId tenant scope
     * @param messageId client message id
     * @param resultBody optional result body for replay
     */
    public void onComplete(String tenantId, String messageId, String resultBody) {
        g4.complete(tenantId, messageId, resultBody);
    }

    /**
     * Produce-fail / accept-timeout / sync-disconnect / streaming-fail → abort (retryable).
     *
     * @param tenantId tenant scope
     * @param messageId client message id
     */
    public void onAbort(String tenantId, String messageId) {
        g4.abort(tenantId, messageId);
    }

    /**
     * Maps a fold result and optional body to the appropriate G4 action.
     *
     * @param status folded invocation status
     * @param tenantId tenant scope
     * @param messageId client message id
     * @param resultBody optional result body for complete path
     */
    public void onFold(InvocationResponseStatus status, String tenantId, String messageId, String resultBody) {
        if (FiveStateFolder.isTerminal(status) || status == InvocationResponseStatus.ACCEPTED_WITH_TASK) {
            onComplete(tenantId, messageId, resultBody);
        } else {
            onAbort(tenantId, messageId);
        }
    }
}
