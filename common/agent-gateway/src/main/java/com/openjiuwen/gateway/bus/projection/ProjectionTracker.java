/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.projection;

import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;
import com.openjiuwen.bus.forwarding.spi.InvocationResponseStatus;
import com.openjiuwen.gateway.bus.wait.FiveStateFolder;

import java.util.HashSet;
import java.util.Set;

/**
 * Per-correlationId projection dedup + terminal-closure + out-of-order (FEAT-012 §4.6.1).
 *
 * @since 2026-07-24
 */
public class ProjectionTracker {
    private final Set<String> seenKeys = new HashSet<>();
    private boolean terminalClosed = false;

    /**
     * @param correlationId gateway correlation id
     * @param eventType inbound projection event type
     * @param payloadDigest optional payload digest for dedup key
     * @return {@code true} if this projection should be processed (first occurrence, not after terminal)
     */
    public boolean shouldProcess(String correlationId, AgentBusEventType eventType, String payloadDigest) {
        if (terminalClosed) {
            return false;
        }
        String key = correlationId + ":" + eventType + ":" + (payloadDigest == null ? "" : payloadDigest);
        if (!seenKeys.add(key)) {
            return false;
        }
        InvocationResponseStatus status = FiveStateFolder.fold(eventType);
        if (FiveStateFolder.isTerminal(status)) {
            terminalClosed = true;
        }
        return true;
    }

    /**
     * @return whether a terminal projection has closed further processing
     */
    public boolean isTerminalClosed() {
        return terminalClosed;
    }
}
