package com.openjiuwen.gateway.bus.control;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;
import com.openjiuwen.bus.forwarding.spi.ForwardingEnvelope;
import com.openjiuwen.bus.forwarding.spi.ForwardingMessageId;
import com.openjiuwen.bus.forwarding.spi.ForwardingRouteHandle;

/** Builds the ForwardingEnvelope for BUS I-04 outbound (FEAT-012 §4.4 P3). */
@Component
public class EnvelopeBuilder {

    public ForwardingEnvelope buildEnvelope(
            String tenantId, String traceId, String idempotencyKey,
            String routeHandleValue, String targetServiceId, String sourceServiceId,
            String payloadRef, long deadlineMillisEpoch) {
        String correlationId = "gw-correlation-" + UUID.randomUUID();
        ForwardingMessageId messageId = new ForwardingMessageId("gw-" + UUID.randomUUID());
        ForwardingRouteHandle routeHandle = new ForwardingRouteHandle(routeHandleValue, tenantId);
        ForwardingEnvelope.PayloadPolicy policy = payloadRef != null
                ? ForwardingEnvelope.PayloadPolicy.DATA_BEARING
                : ForwardingEnvelope.PayloadPolicy.CONTROL_ONLY;
        return new ForwardingEnvelope(
                messageId, AgentBusEventType.CLIENT_INVOCATION_REQUESTED,
                tenantId, traceId, correlationId, idempotencyKey,
                routeHandle, "client-invocation", sourceServiceId, targetServiceId,
                deadlineMillisEpoch, policy, payloadRef);
    }
}
