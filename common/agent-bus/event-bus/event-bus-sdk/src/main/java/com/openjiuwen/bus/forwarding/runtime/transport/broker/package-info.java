/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

/**
 * Broker transport adapter for the C3 forwarding substrate (Stage 26, T4 hybrid).
 *
 * <p>Stage 25 adopted T4 hybrid (outbox + broker): the outbox stays the durable /
 * transactional / auditable / RLS-protected layer (Stage 12 + Stage 24), and a
 * broker carries messages from the relay (sender-side) to the receiver
 * (consumer-pull — backpressure owned by the consumer, MQ is just one carrier).
 *
 * <p>This package holds the broker-common runtime types that stay alongside the
 * concrete adapter: {@link com.openjiuwen.bus.forwarding.runtime.transport.broker.BrokerClientProperties},
 * {@link com.openjiuwen.bus.forwarding.runtime.transport.broker.BrokerOutboundMessage},
 * {@link com.openjiuwen.bus.forwarding.runtime.transport.broker.BrokerMessageHeaders}. The
 * broker SPI surface (Ports + the broker-agnostic message / outcome / filter types)
 * was extracted to {@code forwarding.spi.broker} by the forwarding-reorg (ADR-0163). P-06
 * retired the control-descriptor codec that used to overload {@code payloadRef}: the control
 * plane now rides FIRST-CLASS broker fields, so the bus governs only the outer-envelope fields
 * and {@code payloadRef} stays the A2A data reference. A concrete broker adapter (RocketMQ PoC)
 * lives in the {@code broker.rocketmq} subpackage; the in-memory test double ships in the test
 * source tree.
 *
 * <p><b>Governance.</b> Decision §6.1 item 1 (concrete broker client) is lifted for
 * THIS subpackage only (Stage 25), mirroring how Stage 12 confines Spring/JDBC to
 * {@code persistence.jdbc} and Stage 15 confines the A2A SDK to {@code transport.a2a}.
 * Decision §6.2 ① spirit holds: broker product concepts (topic / partition / offset /
 * consumer-group) never leak outside this package — they map onto forwarding SPI
 * concepts (topic→routeHandle via resolver, partition-key→messageId hash,
 * consumer-group→consumerServiceId, offset→adapter-internal commit). §6.2 ②③④⑤
 * are unchanged (no payload body / token stream / Task state in the broker message;
 * payloadRef rides as a header; cross-tenant is explicitly rejected).
 *
 * <p>Authority: {@code docs/architecture/l0/10-governance/review-packets/
 * agent-bus-forwarding-runtime-transport-decision.md} (Stage 25 adopted-t4);
 * {@code docs/architecture/l0/10-governance/review-packets/
 * agent-bus-forwarding-runtime-decision.md} §6.1/§6.2.
 */
package com.openjiuwen.bus.forwarding.runtime.transport.broker;
