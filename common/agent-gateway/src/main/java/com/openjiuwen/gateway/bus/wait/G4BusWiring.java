package com.openjiuwen.gateway.bus.wait;

import com.openjiuwen.bus.forwarding.spi.InvocationResponseStatus;
import com.openjiuwen.gateway.governance.idempotency.IdempotencyRule;

/** Maps fold results to G4 complete/abort (FEAT-012 §4.6.3). */
public class G4BusWiring {
    private final IdempotencyRule g4;
    public G4BusWiring(IdempotencyRule g4) { this.g4 = g4; }

    /** Terminal fold (RESPONSE/REJECTED/FAILED) or ACCEPTED-timeout → complete (replayable). */
    public void onComplete(String tenantId, String messageId, String resultBody) {
        g4.complete(tenantId, messageId, resultBody);
    }

    /** Produce-fail / accept-timeout / sync-disconnect / streaming-fail → abort (retryable). */
    public void onAbort(String tenantId, String messageId) {
        g4.abort(tenantId, messageId);
    }

    /** Convenience: map a fold + result to the right G4 action. */
    public void onFold(InvocationResponseStatus status, String tenantId, String messageId, String resultBody) {
        if (FiveStateFolder.isTerminal(status) || status == InvocationResponseStatus.ACCEPTED_WITH_TASK) {
            onComplete(tenantId, messageId, resultBody);
        } else {
            onAbort(tenantId, messageId); // UNKNOWN → abort
        }
    }
}
