package com.openjiuwen.gateway.projection;

import java.util.HashSet;
import java.util.Set;

import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;
import com.openjiuwen.bus.forwarding.spi.InvocationResponseStatus;
import com.openjiuwen.gateway.bus.wait.FiveStateFolder;

/** Per-correlationId projection dedup + terminal-closure + out-of-order (FEAT-012 §4.6.1). */
public class ProjectionTracker {
    private final Set<String> seenKeys = new HashSet<>();
    private boolean terminalClosed = false;

    /** @return true if this projection should be processed (first occurrence, not after terminal). */
    public boolean shouldProcess(String correlationId, AgentBusEventType eventType, String payloadDigest) {
        if (terminalClosed) return false;
        String key = correlationId + ":" + eventType + ":" + (payloadDigest == null ? "" : payloadDigest);
        if (!seenKeys.add(key)) return false; // duplicate
        InvocationResponseStatus status = FiveStateFolder.fold(eventType);
        if (FiveStateFolder.isTerminal(status)) terminalClosed = true;
        return true;
    }
    public boolean isTerminalClosed() { return terminalClosed; }
}
