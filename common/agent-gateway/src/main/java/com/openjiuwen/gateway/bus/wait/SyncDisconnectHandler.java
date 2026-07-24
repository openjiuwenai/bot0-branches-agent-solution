/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.bus.wait;

import com.openjiuwen.gateway.governance.idempotency.IdempotencyRule;

/**
 * Handles client disconnect during sync wait (FEAT-012 §4.6.2).
 *
 * @since 2026-07-24
 */
public class SyncDisconnectHandler {

    /**
     * Releases the wait window and aborts G4; does not auto-cancel the task.
     *
     * @param window the active wait window
     * @param tenantId tenant scope
     * @param messageId client message id (G4 key)
     * @param g4 idempotency rule
     */
    public void onDisconnect(WaitWindow window, String tenantId, String messageId, IdempotencyRule g4) {
        window.release();
        g4.abort(tenantId, messageId);
    }
}
