/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.bus.forwarding.runtime.transport.broker.rocketmq;

import com.openjiuwen.bus.forwarding.runtime.transport.BrokerTopicResolver;
import com.openjiuwen.bus.forwarding.runtime.transport.broker.BrokerMessageHeaders;
import com.openjiuwen.bus.forwarding.runtime.transport.broker.BrokerOutboundMessage;
import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;
import com.openjiuwen.bus.forwarding.spi.ForwardingFailureCode;
import com.openjiuwen.bus.forwarding.spi.ForwardingOutboxRecord;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerForwardingRelayPort;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerProduceOutcome;

import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * RocketMQ concrete adapter for {@link BrokerForwardingRelayPort} (FEAT-013/014, S3).
 *
 * <p>Maps a claimed {@link ForwardingOutboxRecord} onto a RocketMQ {@link Message} and
 * produces it via a {@link DefaultMQProducer}-backed sender. The broker topic is
 * derived from the record's {@link AgentBusEventType eventType} by the injected
 * {@link BrokerTopicResolver} (event-type-driven, decoupled from the opaque
 * routeHandle — Option B; the adapter never reads {@code routeHandle.value()}).
 * The opaque {@code routeHandle} rides the record end-to-end as a passthrough
 * field. The message body is a routing descriptor only
 * (decision §6.2 ②); routing metadata (tenantId / messageId / source / target /
 * correlationId / payloadRef) rides as RocketMQ user properties — never the payload
 * body, a token stream, or Task state.
 *
 * <p><b>Testability seam.</b> The {@link MessageSender} functional interface isolates the
 * {@code producer.send} call so unit tests inject a fake (no live broker). The real
 * {@link DefaultMQProducer}-backed sender is {@link #defaultSender(DefaultMQProducer)};
 * its lifecycle (start / shutdown) is wired by the Stage 27+ runtime config (S4), not
 * here. The real-broker round-trip is the S7 {@code @EnabledIfEnvironmentProperty}
 * integration test (env-guarded, not a unit test).
 *
 * <p>Broker native retry is OFF — the agent-bus retry policy (Stage 14) leads, so an
 * {@link BrokerProduceOutcome.Outcome#UNAVAILABLE UNAVAILABLE} outcome surfaces a
 * retryable failure for the policy to schedule, never a double-retry.
 *
 * <p>Authority: {@code architecture/L2-Low-Level-Design/agent-bus/
 * feat-013-client-invocation-event-forwarding.md} §4.1 / §5.2 / §8.1;
 * {@code feat-014-a2a-call-event-forwarding.md} §5.2.
 *
 * @since 0.1.0
 */
// scope: forwarding transport.broker — concrete RocketMQ relay adapter (SPI-licensed, ArchUnit-confined)
public final class RocketMqBrokerForwardingRelay implements BrokerForwardingRelayPort {
    private final BrokerTopicResolver resolver;
    private final String suffix;
    private final MessageSender sender;

    public RocketMqBrokerForwardingRelay(BrokerTopicResolver resolver, String suffix, MessageSender sender) {
        this.resolver = Objects.requireNonNull(resolver, "resolver is required");
        this.suffix = requireSuffix(suffix);
        this.sender = Objects.requireNonNull(sender, "sender is required");
    }

    /**
     * Seam for sending a built {@link Message} — unit tests inject a fake (no live broker);
     * prod injects a {@link DefaultMQProducer}-backed sender via {@link #defaultSender}.
     */
    @FunctionalInterface
    public interface MessageSender {
        /**
         * Send the message; throw on any failure (the relay maps it to UNAVAILABLE).
         *
         * @param message the built RocketMQ message to send
         * @throws Exception if the send fails (MQClientException / broker error / interrupt)
         */
        void send(Message message) throws Exception;
    }

    @Override
    public BrokerProduceOutcome produce(ForwardingOutboxRecord record, long nowMillisEpoch) {
        Objects.requireNonNull(record, "record is required");
        return produce(toOutboundMessage(record), nowMillisEpoch);
    }

    /**
     * Direct (non-outbox) produce — the FEAT-017 direct-tap entry. Builds the RocketMQ
     * {@link Message} from the pre-built {@link BrokerOutboundMessage} (routing-descriptor
     * body + first-class control-plane headers) and sends it via the injected {@link MessageSender}.
     *
     * @param message the pre-built broker-agnostic outbound message (required)
     * @param nowMillisEpoch the produce instant (adapter-internal sequencing / observability)
     * @return the produce outcome (ACCEPTED / UNAVAILABLE / ROUTE_NOT_FOUND)
     */
    @Override
    public BrokerProduceOutcome produce(BrokerOutboundMessage message, long nowMillisEpoch) {
        Objects.requireNonNull(message, "message is required");
        AgentBusEventType eventType = message.headers().eventType();
        if (eventType == null) {
            return BrokerProduceOutcome.routeNotFound(ForwardingFailureCode.ROUTE_NOT_FOUND);
        }
        String topic = resolver.resolveTopic(eventType, suffix);
        Message rocketmq = buildMessage(message, topic);
        try {
            sender.send(rocketmq);
            return BrokerProduceOutcome.accepted();
        } catch (InterruptedException e) {
            // interrupted during send — surface retryable UNAVAILABLE; the dispatch loop's
            // own cooperative-cancellation flag governs whether to retry (G.CON.10: no interrupt()).
            return BrokerProduceOutcome.unavailable(ForwardingFailureCode.RECEIVER_UNAVAILABLE);
        } catch (Exception e) {
            // MQClientException / MQBrokerException / RemotingException — broker transiently
            // unreachable; surface retryable UNAVAILABLE for the agent-bus retry policy.
            return BrokerProduceOutcome.unavailable(ForwardingFailureCode.RECEIVER_UNAVAILABLE);
        }
    }

    /**
     * Pure mapping: a claimed outbox record → RocketMQ {@link Message}. Delegates to
     * {@link #toOutboundMessage(ForwardingOutboxRecord)} then
     * {@link #buildMessage(BrokerOutboundMessage, String)}.
     *
     * @param record the claimed outbox record to map (required)
     * @param topic  the resolved RocketMQ topic to produce to (required)
     * @return the built RocketMQ {@link Message} (routing descriptor body + user-property headers)
     */
    static Message buildMessage(ForwardingOutboxRecord record, String topic) {
        return buildMessage(toOutboundMessage(record), topic);
    }

    /**
     * Pure mapping: a broker-agnostic {@link BrokerOutboundMessage} → RocketMQ {@link Message}.
     * Body is the message's routing descriptor (§6.2 ②); routing metadata rides as user properties
     * (incl. {@code correlationId} per FEAT-013 §4.2 so the gateway can match responses). Shared by
     * the outbox-record overload (via {@link #toOutboundMessage}) and the direct-tap overload — a
     * single encoder so the wire format never drifts between the two produce paths.
     *
     * @param message the broker-agnostic outbound message to map (required)
     * @param topic   the resolved RocketMQ topic to produce to (required)
     * @return the built RocketMQ {@link Message} (routing descriptor body + user-property headers)
     */
    static Message buildMessage(BrokerOutboundMessage message, String topic) {
        BrokerMessageHeaders h = message.headers();
        Message msg = new Message(
                topic,
                /* tags */ null,
                /* keys */ h.messageId(),
                message.routingDescriptor().getBytes(StandardCharsets.UTF_8));
        msg.putUserProperty("tenantId", h.tenantId());
        msg.putUserProperty("messageId", h.messageId());
        msg.putUserProperty("sourceServiceId", h.sourceServiceId());
        msg.putUserProperty("targetServiceId", h.targetServiceId());
        if (h.correlationId() != null) {
            msg.putUserProperty("correlationId", h.correlationId());
        }
        if (h.eventType() != null) {
            msg.putUserProperty("eventType", h.eventType().name());
        }
        if (h.payloadRef() != null) {
            msg.putUserProperty("payloadRef", h.payloadRef());
        }
        // P-06: the control plane rides FIRST-CLASS user properties — never overloaded into payloadRef
        // (payloadRef stays the A2A data reference). Mirrors InMemoryBroker's produce-side mapping.
        if (h.traceId() != null) {
            msg.putUserProperty("traceId", h.traceId());
        }
        if (h.idempotencyKey() != null) {
            msg.putUserProperty("idempotencyKey", h.idempotencyKey());
        }
        if (h.routeHandle() != null) {
            msg.putUserProperty("routeHandle", h.routeHandle());
        }
        if (h.capability() != null) {
            msg.putUserProperty("capability", h.capability());
        }
        if (h.deadlineMillisEpoch() != Long.MAX_VALUE) {
            msg.putUserProperty("deadlineMillisEpoch", Long.toString(h.deadlineMillisEpoch()));
        }
        if (h.inlinePayload() != null) {
            msg.putUserProperty("inlinePayload", h.inlinePayload());
        }
        if (h.originalCaller() != null) {
            msg.putUserProperty("originalCaller", h.originalCaller());
        }
        return msg;
    }

    /**
     * Map a claimed outbox record to the broker-agnostic {@link BrokerOutboundMessage} the
     * encoder reads. The routing descriptor is {@link #buildRoutingDescriptor(ForwardingOutboxRecord)};
     * the headers mirror the record's first-class control plane + data reference + inlinePayload.
     *
     * @param record the claimed outbox record to map (required)
     * @return the broker-agnostic outbound message
     */
    static BrokerOutboundMessage toOutboundMessage(ForwardingOutboxRecord record) {
        BrokerMessageHeaders headers = new BrokerMessageHeaders(
                record.tenantId(),
                record.messageId().value(),
                record.sourceServiceId(),
                record.targetServiceId(),
                record.payloadRef(),
                record.correlationId(),
                record.eventType(),
                record.traceId(),
                record.idempotencyKey(),
                record.routeHandle().value(),
                record.capability(),
                record.deadlineMillisEpoch(),
                record.inlinePayload(),
                record.originalCaller());
        return new BrokerOutboundMessage(buildRoutingDescriptor(record), headers);
    }

    /**
     * §6.2 ②: the broker body is a short routing descriptor only.
     *
     * @param record the claimed outbox record (required)
     * @return the routing descriptor string (the broker message body)
     */
    static String buildRoutingDescriptor(ForwardingOutboxRecord record) {
        return "target=" + record.targetServiceId();
    }

    /**
     * Production sender backed by a started {@link DefaultMQProducer}. A non-{@code SEND_OK}
     * send status (replication timeout / slave unavailable) surfaces as an exception so the
     * relay maps it to {@code UNAVAILABLE} (retryable) — the agent-bus retry policy re-drives
     * and the outbox dedups on {@code (tenantId, messageId)}, preserving at-least-once.
     *
     * @param producer the started {@link DefaultMQProducer} (required)
     * @return a {@link MessageSender} that delegates to {@code producer.send} and maps
     *         non-{@code SEND_OK} statuses to an {@link IllegalStateException}
     */
    public static MessageSender defaultSender(DefaultMQProducer producer) {
        Objects.requireNonNull(producer, "producer is required");
        return message -> {
            SendResult result = producer.send(message);
            if (result.getSendStatus() != SendStatus.SEND_OK) {
                throw new IllegalStateException(
                        "rocketmq produce non-OK send status: " + result.getSendStatus());
            }
        };
    }

    /**
     * Validate the hop suffix (non-null, non-blank) for the constructor.
     *
     * @param suffix the hop suffix ({@code req} / {@code deliver} / {@code resp_in} / {@code resp_out})
     * @return the validated suffix
     */
    private static String requireSuffix(String suffix) {
        Objects.requireNonNull(suffix, "suffix is required");
        if (suffix.isBlank()) {
            throw new IllegalArgumentException("suffix must not be blank");
        }
        return suffix;
    }
}
