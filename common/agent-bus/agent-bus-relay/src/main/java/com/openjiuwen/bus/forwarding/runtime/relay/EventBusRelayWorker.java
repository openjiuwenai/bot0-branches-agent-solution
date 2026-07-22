/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.bus.forwarding.runtime.relay;

import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;
import com.openjiuwen.bus.forwarding.spi.ForwardingEnvelope;
import com.openjiuwen.bus.forwarding.spi.ForwardingFailureCode;
import com.openjiuwen.bus.forwarding.spi.ForwardingInboxPort;
import com.openjiuwen.bus.forwarding.spi.ForwardingMessageId;
import com.openjiuwen.bus.forwarding.spi.ForwardingOutboxClaimPort;
import com.openjiuwen.bus.forwarding.spi.ForwardingOutboxPort;
import com.openjiuwen.bus.forwarding.spi.ForwardingOutboxRecord;
import com.openjiuwen.bus.forwarding.spi.ForwardingReceipt;
import com.openjiuwen.bus.forwarding.spi.ForwardingRouteHandle;
import com.openjiuwen.bus.forwarding.spi.ForwardingStatus;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerForwardingConsumerPort;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerForwardingRelayPort;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerInboundMessage;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerProduceOutcome;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Event-bus two-hop governance relay worker (FEAT-013/014, arch-driven to-be slice A).
 *
 * <p>The governance relay between the two broker hops (L2 feat-013 §1.2 / §4.1): it
 * CONSUMES a hop1 request event off the broker, applies between-hops governance
 * (inbox dedup + correlation-match + audit), and RE-PUBLISHES it as hop2 onto the
 * deliver topic, then commits (model B ack-after-consume). This is the
 * consume→govern→re-produce shape the as-is plane lacks — the existing
 * {@code ForwardingDispatcherWorker} is a producer-shape claim-own-outbox→deliver
 * worker, not a broker-consume→re-publish relay.
 *
 * <p><b>Routing-agnostic and direction-agnostic.</b> The worker produces via the
 * injected {@link BrokerForwardingRelayPort}, whose {@code BrokerTopicResolver}
 * (+ hop suffix) is wired per relay role (forward relay → req / deliver topics; response relay →
 * resp_in / resp_out topics). The 8-topic distinctness is a wiring concern (Spring
 * profile configs, slice B), not a worker-logic concern. Which eventTypes the relay
 * re-publishes is likewise configured, not hard-coded: a forward relay is constructed
 * with {@link #FORWARD_REQUEST_TYPES} (hop1 req → hop2 deliver); a response relay with
 * {@link #RESPONSE_TYPES} (resp_in → resp_out, symmetric governance). The worker just
 * re-publishes the forwarded record through whatever relay port + type set it is
 * constructed with.
 *
 * <p><b>P-06 governance acts applied between the hops</b> (control rides FIRST-CLASS broker
 * fields; {@code payloadRef} is the A2A data reference, never a control-descriptor token):
 * <ul>
 *   <li><b>skip non-relayable</b> — self-produced echoes / control / out-of-direction
 *       messages (eventType absent or not in this relay's configured type set) are committed
 *       without processing. A forward relay uses {@link #FORWARD_REQUEST_TYPES}; a response
 *       relay uses {@link #RESPONSE_TYPES}.</li>
 *   <li><b>control-plane presence</b> — a relayable message must carry its control plane
 *       (traceId / idempotencyKey / routeHandle / capability) as first-class fields. Absence →
 *       commit + inbox REJECTED (non-retryable poison; a broker redelivery cannot fix a missing
 *       control field). Replaces the pre-P-06 descriptor decode + correlation-match: there is now a
 *       single first-class correlationId, so no header/descriptor mismatch is possible.</li>
 *   <li><b>inbox dedup</b> — {@link ForwardingInboxPort#receive} returns
 *       {@link ForwardingStatus.Inbox#DUPLICATE_SUPPRESSED} on a replayed
 *       {@code (tenantId, messageId, consumerServiceId)}; the relay commits without
 *       re-publishing, so an at-least-once redelivery of hop1 never double-produces
 *       hop2.</li>
 *   <li><b>audit</b> — {@link ForwardingInboxPort#markConsumed} (success) /
 *       {@link ForwardingInboxPort#markRejected} (governance failure) record the
 *       terminal inbox state for observability.</li>
 * </ul>
 *
 * <p><b>Re-publish path</b> mirrors {@code GatewayRuntimeService.dispatchRequest} /
 * {@code TestAgentRuntime.produceResponse}: outbox.enqueue → claimDue(1) →
 * {@link BrokerForwardingRelayPort#produce} → markAcked. A produce
 * {@link BrokerProduceOutcome.Outcome#UNAVAILABLE UNAVAILABLE} / {@code ROUTE_NOT_FOUND}
 * → consumer.reject (redeliver; the agent-bus retry policy owns when).
 *
 * <p>Authority: {@code architecture/L2-Low-Level-Design/agent-bus/
 * feat-013-client-invocation-event-forwarding.md §1.2 / §4.1};
 * {@code docs/4plus1/delta/event-bus-relay/decision-tree.md} Q1a.
 *
 * @since 0.1.0
 */
// scope: forwarding.runtime.relay — event-bus two-hop governance relay worker; pure Java
public final class EventBusRelayWorker {
    /**
     * The FEAT-013/014 forward-request eventTypes a <b>forward relay</b> relays
     * (hop1 {@code ascend_bus_*_req} → hop2 {@code ascend_bus_*_deliver}). The request carries
     * its control plane as first-class broker fields (P-06); {@code payloadRef} is the A2A data
     * reference, not a control-descriptor token.
     */
    public static final Set<AgentBusEventType> FORWARD_REQUEST_TYPES = Set.of(
            AgentBusEventType.CLIENT_INVOCATION_REQUESTED,
            AgentBusEventType.CLIENT_INVOCATION_CANCEL_REQUESTED,
            AgentBusEventType.CLIENT_INVOCATION_QUERY_REQUESTED,
            AgentBusEventType.CLIENT_STREAM_SUBSCRIBE_REQUESTED,
            AgentBusEventType.A2A_CALL_REQUESTED,
            AgentBusEventType.A2A_CALL_CANCEL_REQUESTED,
            AgentBusEventType.A2A_CALL_QUERY_REQUESTED,
            AgentBusEventType.A2A_STREAM_SUBSCRIBE_REQUESTED);

    /**
     * The FEAT-013/014 response/terminal eventTypes a <b>response relay</b>
     * relays ({@code ascend_bus_*_resp_in} → {@code ascend_bus_*_resp_out}). The response carries
     * its control plane as first-class broker fields (P-06) + the response content (taskId /
     * status / streamRef) in {@code inlinePayload} (A2A response envelope as DATA), which the
     * gateway reads via responseToken.
     */
    public static final Set<AgentBusEventType> RESPONSE_TYPES = Set.of(
            AgentBusEventType.INVOCATION_ACCEPTED,
            AgentBusEventType.INVOCATION_REJECTED,
            AgentBusEventType.INVOCATION_FAILED,
            AgentBusEventType.INVOCATION_RESPONSE,
            AgentBusEventType.INVOCATION_STREAM_READY,
            AgentBusEventType.INVOCATION_TERMINAL,
            AgentBusEventType.A2A_CALL_ACCEPTED,
            AgentBusEventType.A2A_CALL_REJECTED,
            AgentBusEventType.A2A_CALL_FAILED,
            AgentBusEventType.A2A_CALL_RESPONSE,
            AgentBusEventType.A2A_STREAM_READY,
            AgentBusEventType.A2A_CALL_TERMINAL);

    private static final Logger log = LoggerFactory.getLogger(EventBusRelayWorker.class);

    private final BrokerForwardingConsumerPort consumer;
    private final ForwardingInboxPort inbox;
    private final ForwardingOutboxPort outbox;
    private final ForwardingOutboxClaimPort claimPort;
    private final BrokerForwardingRelayPort relay;
    private final String consumerServiceId;
    private final String sourceServiceId;
    private final long leaseDurationMillis;
    private final Set<AgentBusEventType> relayableTypes;

    public EventBusRelayWorker(BrokerForwardingConsumerPort consumer,
                                ForwardingInboxPort inbox,
                                ForwardingOutboxPort outbox,
                                ForwardingOutboxClaimPort claimPort,
                                BrokerForwardingRelayPort relay,
                                String consumerServiceId,
                                String sourceServiceId,
                                long leaseDurationMillis,
                                Set<AgentBusEventType> relayableTypes) {
        this.consumer = Objects.requireNonNull(consumer, "consumer is required");
        this.inbox = Objects.requireNonNull(inbox, "inbox is required");
        this.outbox = Objects.requireNonNull(outbox, "outbox is required");
        this.claimPort = Objects.requireNonNull(claimPort, "claimPort is required");
        this.relay = Objects.requireNonNull(relay, "relay is required");
        this.consumerServiceId = requireNonBlank(consumerServiceId, "consumerServiceId");
        this.sourceServiceId = requireNonBlank(sourceServiceId, "sourceServiceId");
        if (leaseDurationMillis <= 0) {
            throw new IllegalArgumentException("leaseDurationMillis must be > 0");
        }
        this.leaseDurationMillis = leaseDurationMillis;
        Objects.requireNonNull(relayableTypes, "relayableTypes is required");
        if (relayableTypes.isEmpty()) {
            throw new IllegalArgumentException("relayableTypes must not be empty");
        }
        this.relayableTypes = Set.copyOf(relayableTypes);
    }

    /**
     * One relay tick for a single tenant: poll up to {@code limit} hop-in messages
     * (hop1 requests for a forward relay, resp_in responses for a response relay),
     * apply between-hops governance, re-publish each as hop-out, and commit
     * (or reject for redelivery on a transient produce failure).
     *
     * @param tenantId      tenant scope (the consumer is subscribed tenant-scoped)
     * @param nowMillisEpoch the poll / produce instant
     * @param limit         max messages to relay this tick ({@code > 0})
     * @return the tick summary
     * @throws IllegalArgumentException if {@code tenantId} is blank or {@code limit <= 0}
     */
    public RelayTickResult runOnce(String tenantId, long nowMillisEpoch, int limit) {
        Objects.requireNonNull(tenantId, "tenantId is required");
        if (tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be > 0");
        }

        int relayed = 0;
        int dedupSuppressed = 0;
        int governanceRejected = 0;
        int skipped = 0;
        final String role = roleLabel();

        for (int i = 0; i < limit; i++) {
            Optional<BrokerInboundMessage> polled = consumer.poll(nowMillisEpoch);
            if (polled.isEmpty()) {
                break; // no more hop1 messages this tick
            }
            BrokerInboundMessage msg = polled.get();
            log.info("[{}] RECV messageId={} eventType={} corrId={} tenant={} source={} target={}",
                    role, msg.messageId(), msg.eventType(), msg.correlationId(),
                    msg.tenantId(), msg.sourceServiceId(), msg.targetServiceId());
            // REDELIVERED is a no-op here: reject-for-redeliver is logged in relayOneMessage,
            // and the agent-bus retry policy owns the re-drive (no tick counter for redeliver).
            switch (relayOneMessage(msg, tenantId, nowMillisEpoch, role)) {
                case RELAYED -> relayed++;
                case DEDUP_SUPPRESSED -> dedupSuppressed++;
                case GOVERNANCE_REJECTED -> governanceRejected++;
                case SKIPPED -> skipped++;
            }
        }
        if (relayed + dedupSuppressed + governanceRejected + skipped > 0) {
            log.info("[{}] TICK relayed={} dedupSuppressed={} governanceRejected={} skipped={}",
                    role, relayed, dedupSuppressed, governanceRejected, skipped);
        }
        return new RelayTickResult(relayed, dedupSuppressed, governanceRejected, skipped);
    }

    /**
     * Drive one polled hop1 message through the between-hops governance (skip non-relayable,
     * descriptor decode, correlation match, inbox dedup) and hand off to the re-publish path.
     *
     * @param msg           the polled hop1 message
     * @param tenantId      tenant scope (for the claim / markAcked / markConsumed calls in republish)
     * @param nowMillisEpoch the poll / produce instant
     * @param role          human label for log lines (forward / response / relay)
     * @return the per-message outcome used to aggregate tick counters
     */
    private MessageOutcome relayOneMessage(BrokerInboundMessage msg, String tenantId,
                                           long nowMillisEpoch, String role) {
        // governance 1: skip non-relayable echoes / control / out-of-direction (eventType absent or
        // not in this relay's configured type set) — commit + advance. P-06: eventType is now a
        // first-class inbound field (mirrored from headers at poll), so this no longer peeks a payloadRef
        // descriptor token.
        AgentBusEventType eventType = msg.eventType();
        if (eventType == null || !relayableTypes.contains(eventType)) {
            log.info("[{}] SKIP non-relayable eventType={} (not in {} relayable set) messageId={}",
                    role, eventType, role, msg.messageId());
            consumer.commit(msg);
            return MessageOutcome.SKIPPED;
        }

        // governance 2: control-plane presence (P-06). A relayable request carries its control plane as
        // first-class inbound fields (traceId / idempotencyKey / routeHandle / capability); absence means
        // the upstream produced a malformed/incomplete message — poison (reject without redelivery; a broker
        // redelivery cannot fix a missing control field). Replaces the pre-P-06 descriptor decode.
        if (isBlank(msg.traceId()) || isBlank(msg.idempotencyKey())
                || isBlank(msg.routeHandle()) || isBlank(msg.capability())) {
            log.warn("[{}] REJECT poison (control plane incomplete: trace/idem/route/cap absent) messageId={}",
                    role, msg.messageId());
            rejectPoison(msg, ForwardingFailureCode.PAYLOAD_REF_INVALID);
            return MessageOutcome.GOVERNANCE_REJECTED;
        }

        // governance 3: inbox dedup (a replayed hop1 must not double-produce hop2).
        ForwardingEnvelope env = buildHop2Envelope(msg);
        ForwardingStatus.Inbox inboxStatus = inbox.receive(env, consumerServiceId, nowMillisEpoch);
        if (inboxStatus == ForwardingStatus.Inbox.DUPLICATE_SUPPRESSED) {
            log.info("[{}] DEDUP suppressed (replayed hop1, not re-published) messageId={}",
                    role, msg.messageId());
            consumer.commit(msg);
            return MessageOutcome.DEDUP_SUPPRESSED;
        }

        // re-publish (mirrors gateway dispatch); RELAYED on accepted, REDELIVERED on produce failure.
        return republishHop2(env, msg, tenantId, nowMillisEpoch, role);
    }

    /**
     * Re-publish the hop2 envelope (enqueue → claim → produce → markAcked) and report the outcome.
     *
     * @param env           the hop2 envelope built from the inbound message + descriptor
     * @param msg           the inbound hop1 message (committed on success, rejected on failure)
     * @param tenantId      tenant scope (for claim / markAcked / markConsumed)
     * @param nowMillisEpoch the produce instant
     * @param role          human label for log lines
     * @return RELAYED on accepted produce, REDELIVERED on unavailable / route-not-found / nothing claimed
     */
    private MessageOutcome republishHop2(ForwardingEnvelope env, BrokerInboundMessage msg,
                                         String tenantId, long nowMillisEpoch, String role) {
        ForwardingReceipt receipt = outbox.enqueue(env, sourceServiceId, env.targetServiceId(), nowMillisEpoch);
        Objects.requireNonNull(receipt, "enqueue receipt");
        List<ForwardingOutboxRecord> claimed = claimPort.claimDue(tenantId, nowMillisEpoch, 1,
                consumerServiceId, nowMillisEpoch + leaseDurationMillis);
        BrokerProduceOutcome outcome = null;
        for (ForwardingOutboxRecord record : claimed) {
            outcome = relay.produce(record, nowMillisEpoch);
            if (outcome.outcome() == BrokerProduceOutcome.Outcome.ACCEPTED) {
                outbox.markAcked(record.messageId(), tenantId, consumerServiceId);
            }
        }
        if (outcome != null && outcome.outcome() == BrokerProduceOutcome.Outcome.ACCEPTED) {
            inbox.markConsumed(env.messageId(), tenantId, consumerServiceId);
            consumer.commit(msg);
            log.info("[{}] SEND hop2 messageId={} eventType={} corrId={} tenant={} source={} target={} route={}",
                    role, env.messageId(), env.eventType(), env.correlationId(), env.tenantId(),
                    env.sourceServiceId(), env.targetServiceId(), env.routeHandle().value());
            return MessageOutcome.RELAYED;
        }
        // produce UNAVAILABLE / ROUTE_NOT_FOUND (or nothing claimed): reject → redeliver; the agent-bus
        // retry policy owns when, and rejecting leaves the hop1 message visible for operator intervention.
        ForwardingFailureCode code = (outcome != null && outcome.failureCode() != null)
                ? outcome.failureCode()
                : ForwardingFailureCode.RECEIVER_UNAVAILABLE;
        log.warn("[{}] REJECT redeliver (produce outcome={} failureCode={}) messageId={}",
                role, outcome == null ? "none-claimed" : outcome.outcome(), code, msg.messageId());
        consumer.reject(msg, code);
        return MessageOutcome.REDELIVERED;
    }

    /**
     * Human label for logs: which relay role this worker drives (forward / response / relay).
     *
     * @return the relay role label ("forward", "response", or "relay" for a custom type set)
     */
    private String roleLabel() {
        if (FORWARD_REQUEST_TYPES.equals(relayableTypes)) {
            return "forward";
        }
        if (RESPONSE_TYPES.equals(relayableTypes)) {
            return "response";
        }
        return "relay";
    }

    /**
     * Build the hop2 forward envelope from the inbound hop1 message (P-06).
     *
     * <p>The hop2 {@code messageId} is a fresh relay-scoped id (prefix {@code eb-} +
     * hop1 broker id) so the outbox dedup {@code (tenantId, messageId)} does NOT
     * collide with the hop1 record enqueued by the gateway — the outbox is a shared
     * table and reusing the hop1 id would make {@code ON CONFLICT DO NOTHING} silently
     * skip the hop2 insert, leaving nothing for {@code claimDue} to claim. Defence
     * against double-relay of a replayed hop1 is owned by the inbox dedup (which keys
     * on the hop1 id carried in {@code env.messageId()}'s tail).
     *
     * <p><b>P-06 control/data separation.</b> The control plane (eventType / traceId /
     * correlationId / idempotencyKey / routeHandle / capability / deadline) is read from the
     * inbound's FIRST-CLASS fields — no descriptor decode. The A2A data reference
     * ({@code payloadRef}) and the bounded inline body ({@code inlinePayload}) pass through as
     * DATA; the policy follows data presence (a control-only hop1 forwards as CONTROL_ONLY).
     * {@code sourceServiceId} = this relay; {@code targetServiceId} = the original runtime target.
     *
     * @param msg the inbound hop1 message (carries first-class control + data fields)
     * @return the hop2 forward envelope (fresh {@code eb-} messageId, control from msg)
     */
    private ForwardingEnvelope buildHop2Envelope(BrokerInboundMessage msg) {
        String hop2MessageId = "eb-" + msg.messageId();
        boolean hasData = msg.payloadRef() != null || msg.inlinePayload() != null;
        return new ForwardingEnvelope(
                new ForwardingMessageId(hop2MessageId),
                msg.eventType(),
                msg.tenantId(),
                msg.traceId(),
                msg.correlationId(),
                msg.idempotencyKey(),
                new ForwardingRouteHandle(msg.routeHandle(), msg.tenantId()),
                msg.capability(),
                sourceServiceId,
                msg.targetServiceId(),
                msg.deadlineMillisEpoch(),
                hasData ? ForwardingEnvelope.PayloadPolicy.DATA_BEARING
                        : ForwardingEnvelope.PayloadPolicy.CONTROL_ONLY,
                msg.payloadRef(),
                msg.inlinePayload(),
                msg.originalCaller());  // P-06: preserve original caller across the hop (response routing)
    }

    /**
     * Commit a poison message (do NOT redeliver — a broker redelivery cannot fix a
     * malformed / data-integrity failure and would loop) and mark the inbox REJECTED
     * for audit.
     *
     * @param msg  the poison inbound message to commit (no redelivery)
     * @param code the failure code recorded against the inbox entry
     */
    private void rejectPoison(BrokerInboundMessage msg, ForwardingFailureCode code) {
        consumer.commit(msg);
        inbox.markRejected(new ForwardingMessageId(msg.messageId()), msg.tenantId(),
                consumerServiceId, code);
    }

    /** Immutable summary of one relay tick. */
    public record RelayTickResult(int relayed, int dedupSuppressed, int governanceRejected, int skipped) {
        public RelayTickResult {
            if (relayed < 0 || dedupSuppressed < 0 || governanceRejected < 0 || skipped < 0) {
                throw new IllegalArgumentException("tick counts must be non-negative");
            }
        }
    }

    /** Per-message outcome of one relay iteration, used to aggregate tick counters in {@link #runOnce}. */
    private enum MessageOutcome {
        RELAYED, DEDUP_SUPPRESSED, GOVERNANCE_REJECTED, SKIPPED, REDELIVERED
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name + " is required");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static boolean isBlank(String value) {
        // P-06 control-plane presence guard: null or blank control field → malformed/incomplete message.
        return value == null || value.isBlank();
    }
}
