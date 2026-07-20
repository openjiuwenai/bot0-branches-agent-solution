/**
 * Pure-Java contract surface for the C3 forwarding runtime: envelope, receipt,
 * outbox / inbox record models, lease value type, status / failure-code enums,
 * and the ports — enqueue / mark / status ({@code ForwardingOutboxPort},
 * {@code ForwardingInboxPort}), accept ({@code ForwardingDispatcher}),
 * concurrent claim / lease ({@code ForwardingOutboxClaimPort}) and delivery
 * ({@code ForwardingDeliveryPort}).
 *
 * <p>This package MUST stay pure Java — no Spring, no JDBC, no HTTP, no broker
 * client. SPI purity for this package is enforced by
 * {@code AgentBusForwardingSpiPurityTest}.
 *
 * <p>Authority: {@code ICD-Agent-Bus-Forwarding-Runtime};
 * {@code architecture/L2-Low-Level-Design/agent-bus/forwarding-outbox-inbox.md §8};
 * {@code architecture/L2-Low-Level-Design/agent-bus/forwarding-persistence.md}.
 */
package com.openjiuwen.bus.forwarding.spi;
