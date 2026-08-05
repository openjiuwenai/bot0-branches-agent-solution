/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.bus.forwarding.spi;

/**
 * High-level request submission port for a Runtime acting as a caller — submits a
 * {@link ForwardingEnvelope} to the agent-bus request path and returns a synchronous
 * receipt. Hides the broker producer SPI / topic resolution / wire mapping from the
 * Runtime; the SDK impl maps the envelope to a {@code BrokerOutboundMessage} and
 * directly produces to the request topic (no JDBC outbox).
 *
 * <p>Reliability boundary (P-14): an accepted receipt means the broker accepted the
 * message (handoff); a rejected receipt means the handoff failed (the caller fails the
 * call or retries in-memory). Pre-handoff crash recovery is NOT promised by a
 * pure-in-memory Runtime — that is a separate Agent Bus concern, never PostgreSQL
 * foisted onto the Runtime.
 *
 * @since 0.1.0
 */
public interface AgentBusRequestSubmitter {
    /**
     * Submit a request envelope to the agent-bus request path.
     *
     * @param envelope the self-describing request envelope (source/target/routeHandle/
     *                eventType/payload all present)
     * @return a synchronous receipt (accepted = broker accepted; rejected = handoff
     *         failed, carries failureCode); never {@code null}
     */
    ForwardingReceipt submit(ForwardingEnvelope envelope);
}
