package com.openjiuwen.gateway.bus.wait;

import com.openjiuwen.gateway.governance.idempotency.IdempotencyRule;

/** Handles client disconnect during sync wait (FEAT-012 §4.6.2). */
public class SyncDisconnectHandler {
    /** Release window, abort G4; do NOT auto-Cancel Task. */
    public void onDisconnect(WaitWindow window, String tenantId, String messageId, IdempotencyRule g4) {
        window.release();
        g4.abort(tenantId, messageId);
    }
}
