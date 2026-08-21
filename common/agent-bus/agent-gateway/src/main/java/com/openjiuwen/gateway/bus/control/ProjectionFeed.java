/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.bus.control;

import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;

import java.util.Optional;

/**
 * Inbound projection port (simplified BrokerForwardingConsumerPort for Gateway).
 *
 * @since 2026-07-24
 */
public interface ProjectionFeed {
    /**
     * Polls the next projection matching the correlation id.
     *
     * @param correlationId gateway correlation id
     * @return the next projection event, or empty if none available
     */
    Optional<ProjectionEvent> poll(String correlationId);

    /**
     * One inbound projection event surfaced to the gateway wait loop.
     *
     * @param eventType bus event type
     * @param taskId task id when present
     * @param streamRef opaque stream ref when present
     * @param payloadRef payload ref when present
     * @param body decoded A2A response (RESPONSE/TERMINAL) or reason/error (REJECTED/FAILED)
     *             or input descriptor (INPUT_REQUIRED); {@code null} when the projection
     *             carries no body (ACCEPTED/STREAM_READY)
     * @param a2aResponsePresent whether body is an embedded validated JSON-RPC response
     * @param protocolFailure local protocol-failure classification, when decoding failed
     */
    record ProjectionEvent(
            AgentBusEventType eventType,
            String taskId,
            String streamRef,
            String payloadRef,
            String body,
            boolean a2aResponsePresent,
            String protocolFailure) {
        /**
         * Convenience for projections without a decoded response/reason body.
         *
         * @param eventType bus event type
         * @param taskId task id when present
         * @param streamRef opaque stream ref when present
         * @param payloadRef payload ref when present
         */
        public ProjectionEvent(AgentBusEventType eventType, String taskId, String streamRef, String payloadRef) {
            this(eventType, taskId, streamRef, payloadRef, null, false, null);
        }

        public ProjectionEvent(AgentBusEventType eventType, String taskId, String streamRef, String payloadRef,
                String body) {
            this(eventType, taskId, streamRef, payloadRef, body, false, null);
        }

        /**
         * Creates a local deterministic failure for a malformed broker projection.
         */
        public static ProjectionEvent protocolFailure(String failureCode) {
            return new ProjectionEvent(AgentBusEventType.INVOCATION_FAILED, null, null, null,
                    failureCode, false, failureCode);
        }
    }
}
