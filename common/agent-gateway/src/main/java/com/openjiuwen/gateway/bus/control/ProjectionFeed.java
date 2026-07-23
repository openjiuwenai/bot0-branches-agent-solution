package com.openjiuwen.gateway.bus.control;

import java.util.Optional;

/** Inbound projection port (simplified BrokerForwardingConsumerPort for Gateway). */
public interface ProjectionFeed {
    /** Poll the next projection matching the correlationId. Returns empty if none available. */
    Optional<ProjectionEvent> poll(String correlationId);

    record ProjectionEvent(
            com.openjiuwen.bus.forwarding.spi.AgentBusEventType eventType,
            String taskId, String streamRef, String payloadRef) {}
}
