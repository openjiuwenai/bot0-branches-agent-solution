/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.bus.gateway.runtime;

import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;
import com.openjiuwen.bus.forwarding.spi.ForwardingEnvelope;
import com.openjiuwen.bus.forwarding.spi.ForwardingMessageId;
import com.openjiuwen.bus.forwarding.spi.ForwardingOutboxClaimPort;
import com.openjiuwen.bus.forwarding.spi.ForwardingOutboxPort;
import com.openjiuwen.bus.forwarding.spi.ForwardingOutboxRecord;
import com.openjiuwen.bus.forwarding.spi.ForwardingReceipt;
import com.openjiuwen.bus.forwarding.spi.ForwardingRouteHandle;
import com.openjiuwen.bus.forwarding.spi.InvocationResponseStatus;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerForwardingConsumerPort;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerForwardingRelayPort;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerInboundMessage;
import com.openjiuwen.bus.spi.ingress.IngressEnvelope;
import com.openjiuwen.bus.spi.ingress.IngressGateway;
import com.openjiuwen.bus.spi.ingress.IngressResponse;
import com.openjiuwen.rdc.model.AgentCardDto;
import com.openjiuwen.rdc.service.AgentDiscoveryService;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Production gateway runtime for FEAT-013 client-invocation event forwarding
 * (S2 of REQ-2026-001 step-6 W2).
 *
 * <p>Implements {@link IngressGateway#routeClientRequest}: it builds a
 * {@link ForwardingEnvelope} carrying the request's control descriptor in its
 * {@code payloadRef}, dispatches it through the outbox / claim / relay substrate
 * (synchronous simplified produce — the real async worker loop is S4), then polls
 * the response consumer within the accept window and classifies responses by the
 * NATIVE {@link BrokerInboundMessage#eventType()} (matched by the NATIVE
 * {@link BrokerInboundMessage#correlationId()}) — NO descriptor-decoding for
 * classification (L2 feat-013 §4.2 / §4.3).
 *
 * <p><b>Accept-window state machine (§4.3).</b> The gateway observes, per
 * {@code correlationId == requestId}:
 * <ul>
 *   <li>{@code INVOCATION_ACCEPTED} → records {@code taskId}, keeps polling for a
 *       terminal up to {@code responseTimeoutMs}; if the window drains with no
 *       terminal, returns {@link IngressResponse#accepted ACCEPTED} with the
 *       {@code taskId} cursor ({@link InvocationResponseStatus#ACCEPTED_WITH_TASK}).</li>
 *   <li>{@code INVOCATION_RESPONSE} → {@link InvocationResponseStatus#COMPLETED_RESPONSE}
 *       → returns {@code ACCEPTED} (cursor = {@code taskId}).</li>
 *   <li>{@code INVOCATION_STREAM_READY} → {@link InvocationResponseStatus#STREAM_READY}
 *       → returns {@code ACCEPTED} (cursor = {@code streamRef}). SSE bridging itself
 *       is deferred to S4 (S2 returns the stream reference cursor only).</li>
 *   <li>{@code INVOCATION_REJECTED} / {@code INVOCATION_FAILED} →
 *       {@link IngressResponse#rejected REJECTED}.</li>
 *   <li>{@code INVOCATION_TERMINAL} with {@code status=completed} →
 *       {@link InvocationResponseStatus#COMPLETED_RESPONSE}; else {@code FAILED}.</li>
 *   <li>No accepted/terminal within the window → {@link IngressResponse#deferred
 *       DEFERRED} (the §6.2.3 UNKNOWN outcome — the client retries with the same
 *       {@code idempotencyKey}).</li>
 * </ul>
 *
 * <p><b>Self-consumption.</b> The gateway's own request (whose
 * {@code sourceServiceId} equals this gateway's {@code sourceServiceId}) is visible
 * to the gateway's consumer-group; {@link #acceptWindow} commits it without
 * processing and keeps polling for real responses.
 *
 * <p><b>Deferred to S4.</b> The Spring HTTP controller ({@code GatewayRuntimeController},
 * {@code POST /a2a}), the real async dispatch worker, and the full A2A SSE bridge
 * (token-streaming) are NOT in S2 — this class is the {@link IngressGateway} impl
 * with {@link #dispatchRequest} + {@link #acceptWindow} exposed as testable sub-methods.
 *
 * <p><b>Discovery path (FEAT-013/014 registry-discovery-center integration, §7.5 Option A).</b>
 * This test-scope class is a <b>reference implementation for the next-batch gateway production
 * module</b>: it injects {@link AgentDiscoveryService} and resolves the target via
 * {@code searchByServiceId} / {@code searchInstancesByAgentId} / {@code searchByCapability},
 * selecting a candidate by weight / health, then stamps the discovered opaque {@code routeHandle}
 * + {@code serviceId} onto the envelope. The opaque {@code routeHandle} rides the envelope
 * end-to-end (T4 pub/sub passthrough) and is unwrapped only by the gateway's T1 SSE bridge
 * (deferred). agent-bus main stays zero-dependency on registry-discovery-center — the
 * {@link AgentDiscoveryService} dependency is test-scope only.
 *
 * <p>Authority: {@code architecture/L2-Low-Level-Design/agent-bus/
 * feat-013-client-invocation-event-forwarding.md §4.2 / §4.3 / §4.6 / §6}.
 *
 * @since 0.1.0
 */
// scope: gateway runtime — IngressGateway impl; pure Java (controller/SSE-bridge deferred to S4)
public final class GatewayRuntimeService implements IngressGateway {
    /** Lease duration granted when the gateway claims its own outbox record (matches the test double). */
    static final long DISPATCH_LEASE_MS = 60_000L;

    private final ForwardingOutboxPort outbox;
    private final ForwardingOutboxClaimPort claimPort;
    private final BrokerForwardingRelayPort relay;
    private final BrokerForwardingConsumerPort responseConsumer;
    private final AgentDiscoveryService discovery;
    private final String sourceServiceId;
    private final long acceptTimeoutMs;
    private final long responseTimeoutMs;
    private final LongSupplier clock;

    /**
     * Construct the gateway runtime with its outbox / relay / response-consumer substrate, the
     * discovery service (test-scope reference impl — §7.5 Option A), and the accept / response
     * window timeouts.
     *
     * @param outbox             the gateway's durable outbox (enqueue + ack)
     * @param claimPort          the gateway's claim / lease port
     * @param relay              the broker relay (produce the request onto the broker)
     * @param responseConsumer   the broker consumer (poll responses off the broker)
     * @param discovery          the registry discovery service (resolves the target + opaque routeHandle)
     * @param sourceServiceId    the gateway's service id (envelope sourceServiceId + claim leaseOwner)
     * @param acceptTimeoutMs    accept window: no accepted/rejected/failed within → UNKNOWN (deferred)
     * @param responseTimeoutMs  post-accepted window: no terminal within → ACCEPTED_WITH_TASK
     * @param clock              epoch-millis supplier (drives due / lease / window judgements)
     */
    public GatewayRuntimeService(ForwardingOutboxPort outbox,
                                 ForwardingOutboxClaimPort claimPort,
                                 BrokerForwardingRelayPort relay,
                                 BrokerForwardingConsumerPort responseConsumer,
                                 AgentDiscoveryService discovery,
                                 String sourceServiceId,
                                 long acceptTimeoutMs,
                                 long responseTimeoutMs,
                                 LongSupplier clock) {
        this.outbox = Objects.requireNonNull(outbox, "outbox is required");
        this.claimPort = Objects.requireNonNull(claimPort, "claimPort is required");
        this.relay = Objects.requireNonNull(relay, "relay is required");
        this.responseConsumer = Objects.requireNonNull(responseConsumer, "responseConsumer is required");
        this.discovery = Objects.requireNonNull(discovery, "discovery is required");
        this.sourceServiceId = requireNonBlank(sourceServiceId, "sourceServiceId");
        if (acceptTimeoutMs < 0) {
            throw new IllegalArgumentException("acceptTimeoutMs must be >= 0");
        }
        if (responseTimeoutMs < 0) {
            throw new IllegalArgumentException("responseTimeoutMs must be >= 0");
        }
        this.acceptTimeoutMs = acceptTimeoutMs;
        this.responseTimeoutMs = responseTimeoutMs;
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    @Override
    public IngressResponse routeClientRequest(IngressEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope is required");
        dispatchRequest(envelope);
        return acceptWindow(envelope.requestId(), envelope.tenantId());
    }

    /**
     * Build the request {@link ForwardingEnvelope} and synchronously dispatch it:
     * enqueue → claimDue → {@link BrokerForwardingRelayPort#produce produce} → markAcked.
     * Returns the built envelope so tests can assert the mapped eventType / correlationId /
     * payloadRef descriptor.
     *
     * <p>S2 simplified synchronous produce; the real async worker loop is S4.
     *
     * @param env the ingress request envelope
     * @return the built request envelope (carrying the control descriptor in its payloadRef)
     */
    public ForwardingEnvelope dispatchRequest(IngressEnvelope env) {
        Objects.requireNonNull(env, "env is required");
        long now = clock.getAsLong();
        // Discovery path (§7.5 Option A): resolve the target via AgentDiscoveryService — the opaque
        // routeHandle + targetServiceId come from the selected AgentCardDto (not requestAttributes).
        DiscoveredTarget target = resolveTarget(env);
        ForwardingRouteHandle routeHandle = target.routeHandle();
        AgentBusEventType eventType = mapEventType(env.requestType(), target.routeFamily());
        String targetServiceId = target.serviceId();
        String capability = resolveCapability(env);
        long deadline = env.deadlineMillisEpoch() != null ? env.deadlineMillisEpoch() : Long.MAX_VALUE;
        String correlationId = env.requestId().toString();
        String idempotencyKey = env.idempotencyKey().toString();

        // P-06: the control plane rides the envelope's FIRST-CLASS control fields; the A2A body rides
        // inlinePayload (small JSON-RPC text — 2b). payloadRef is the data reference for large/multimodal
        // bodies, resolved via a CALLER-OWNED store (2a, NOT provided by the SDK). This reference gateway
        // inlines small String bodies; a production gateway stores large bodies and puts the ref here.
        Object body = env.payload();
        String inlinePayload = (body instanceof String s) ? s : null;
        ForwardingEnvelope.PayloadPolicy policy = (inlinePayload != null)
                ? ForwardingEnvelope.PayloadPolicy.DATA_BEARING
                : ForwardingEnvelope.PayloadPolicy.CONTROL_ONLY; // non-inlineable body → prod gateway store (2a)
        ForwardingEnvelope envelope = new ForwardingEnvelope(
                new ForwardingMessageId("gw-" + UUID.randomUUID()),
                eventType,
                env.tenantId(),
                env.traceId(),
                correlationId,
                idempotencyKey,
                routeHandle,
                capability,
                sourceServiceId,
                targetServiceId,
                deadline,
                policy,
                null,           // payloadRef: A2A data reference (large bodies, caller store) — none when inlined
                inlinePayload,
                sourceServiceId); // P-06: originalCaller = this gateway (response routing across the relay hop)

        // enqueue → claim → produce → markAcked (simplified synchronous dispatch; S4 adds the worker loop)
        ForwardingReceipt receipt = outbox.enqueue(envelope, sourceServiceId, targetServiceId, now);
        Objects.requireNonNull(receipt, "enqueue receipt");
        List<ForwardingOutboxRecord> claimed = claimPort.claimDue(
                env.tenantId(), now, 1, sourceServiceId, now + DISPATCH_LEASE_MS);
        for (ForwardingOutboxRecord record : claimed) {
            relay.produce(record, now);
            outbox.markAcked(record.messageId(), env.tenantId(), sourceServiceId);
        }
        return envelope;
    }

    /**
     * Poll the response consumer within the accept window, classify each matching
     * response by its NATIVE {@link BrokerInboundMessage#eventType()}, and return the
     * resulting {@link IngressResponse}. Skips (commits) the gateway's own request
     * (self-consumption) and any non-matching responses.
     *
     * @param requestId the ingress request id (correlationId == requestId.toString())
     * @param tenantId  tenant scope for the client-side response filter (Rule R-C.c; the response
     *                 consumer is subscribed with a targetServiceId filter — D13 response-side)
     * @return the observed acknowledgement; never null
     */
    public IngressResponse acceptWindow(UUID requestId, String tenantId) {
        Objects.requireNonNull(requestId, "requestId is required");
        requireNonBlank(tenantId, "tenantId");
        String correlationId = requestId.toString();
        long start = clock.getAsLong();
        long acceptDeadline = start + acceptTimeoutMs;
        long responseDeadline = start + responseTimeoutMs;

        String taskId = null;
        while (true) {
            long now = clock.getAsLong();
            // check window expiry BEFORE polling — poll may block for pollWaitMillis,
            // so re-check after each iteration to bound the wait within the window.
            if (taskId == null && now >= acceptDeadline) {
                break; // accept window exhausted with no accepted → UNKNOWN (deferred)
            }
            if (taskId != null && now >= responseDeadline) {
                break; // response window exhausted, but we have taskId → ACCEPTED_WITH_TASK
            }
            BrokerInboundMessage msg = responseConsumer.poll(now).orElse(null);
            if (msg == null) {
                continue; // poll timed out with no message; loop back to re-check window expiry
            }
            if (shouldSkipResponse(msg, tenantId, correlationId)) {
                continue; // self-source / cross-tenant / non-matching corrId — committed + skipped
            }
            InvocationResponseStatus status = classify(msg);
            responseConsumer.commit(msg);
            MatchResult mr = handleMatchedResponse(msg, requestId, status, taskId);
            if (mr.response() != null) {
                return mr.response();
            }
            taskId = mr.nextTaskId();
        }
        // window drained without a terminal: ACCEPTED_WITH_TASK if we got a taskId, else UNKNOWN (deferred)
        if (taskId != null) {
            return toIngressResponse(requestId, InvocationResponseStatus.ACCEPTED_WITH_TASK, taskId, null, null);
        }
        return toIngressResponse(requestId, InvocationResponseStatus.UNKNOWN, null, null, null);
    }

    /**
     * Skip (commit + advance) responses that are NOT the one this accept window waits on: the
     * gateway's own request (self-consumption), cross-tenant responses (D13 client-side tenant
     * filter), and responses for a different correlationId. The polled message is committed here.
     *
     * @param msg           the polled response candidate
     * @param tenantId      tenant scope (the response consumer's client-side tenant filter)
     * @param correlationId the correlationId this window matches (== requestId.toString())
     * @return true if the message was committed + skipped (caller keeps polling); false if it matches
     */
    private boolean shouldSkipResponse(BrokerInboundMessage msg, String tenantId, String correlationId) {
        // self-consumption: the gateway's own request (source == this gateway) → commit + keep polling
        if (sourceServiceId.equals(msg.sourceServiceId())) {
            responseConsumer.commit(msg);
            return true;
        }
        // client-side tenant filter (D13 response-side): a cross-tenant response is committed + skipped.
        if (!tenantId.equals(msg.tenantId())) {
            responseConsumer.commit(msg);
            return true;
        }
        // a response for a different request → commit (advance) and keep polling for ours
        if (!correlationId.equals(msg.correlationId())) {
            responseConsumer.commit(msg);
            return true;
        }
        return false;
    }

    /**
     * Act on a matched response's classification: ACCEPTED_WITH_TASK records the taskId and keeps
     * polling; COMPLETED_RESPONSE / STREAM_READY / REJECTED / FAILED return the mapped
     * {@link IngressResponse}; UNKNOWN keeps polling (defensive — classify should not yield UNKNOWN
     * for a known eventType).
     *
     * @param msg      the matched response (correlationId == this window's)
     * @param requestId the ingress request id
     * @param status   the observed invocation status
     * @param taskId   the taskId recorded so far (from a prior ACCEPTED_WITH_TASK), or null
     * @return the match result (response to return, or the next taskId to keep polling with)
     */
    private MatchResult handleMatchedResponse(BrokerInboundMessage msg, UUID requestId,
                                               InvocationResponseStatus status, String taskId) {
        return switch (status) {
            case ACCEPTED_WITH_TASK -> new MatchResult(null, responseToken(msg, "taskId"));
            case COMPLETED_RESPONSE -> new MatchResult(toIngressResponse(requestId, status,
                    taskId != null ? taskId : responseToken(msg, "taskId"), null, null), null);
            case STREAM_READY -> new MatchResult(toIngressResponse(requestId, status, null,
                    responseToken(msg, "streamRef"), null), null);
            case REJECTED, FAILED -> new MatchResult(toIngressResponse(requestId, status, null, null,
                    reasonFrom(msg, status)), null);
            case UNKNOWN -> new MatchResult(null, taskId);
        };
    }

    /** Carrier for a matched-response outcome: a response to return, or the next taskId to keep polling. */
    private record MatchResult(IngressResponse response, String nextTaskId) {}

    /**
     * Classify a polled response by its NATIVE {@link BrokerInboundMessage#eventType()}
     * (FEAT-013/014 family). The {@code INVOCATION_TERMINAL} / {@code A2A_CALL_TERMINAL}
     * sub-state (completed / cancelled / failed) is decoded from the {@code status=} token in the
     * {@code inlinePayload} (the A2A response envelope as DATA — P-06/FEAT-014:68); all other
     * classifications are pure eventType mappings.
     *
     * <p>Terminal sub-state mapping:
     * <ul>
     *   <li>{@code status=completed} → {@link InvocationResponseStatus#COMPLETED_RESPONSE}
     *       (normal completion).</li>
     *   <li>{@code status=cancelled} → {@link InvocationResponseStatus#COMPLETED_RESPONSE}
     *       (user-initiated cancel is a normal terminal state, not a failure — FEAT-013
     *       §6.2.5 UC-05 expects TERMINAL(cancelled) → ACCEPTED).</li>
     *   <li>any other {@code status=} → {@link InvocationResponseStatus#FAILED}.</li>
     * </ul>
     *
     * @param m the polled response (non-null; correlationId already matched by the caller)
     * @return the observed invocation status
     */
    public InvocationResponseStatus classify(BrokerInboundMessage m) {
        Objects.requireNonNull(m, "m is required");
        AgentBusEventType eventType = m.eventType();
        if (eventType == null) {
            return InvocationResponseStatus.UNKNOWN; // control-only / JDBC-back-compat
        }
        return switch (eventType) {
            case INVOCATION_RESPONSE, A2A_CALL_RESPONSE -> InvocationResponseStatus.COMPLETED_RESPONSE;
            case INVOCATION_ACCEPTED, A2A_CALL_ACCEPTED -> InvocationResponseStatus.ACCEPTED_WITH_TASK;
            case INVOCATION_STREAM_READY, A2A_STREAM_READY -> InvocationResponseStatus.STREAM_READY;
            case INVOCATION_REJECTED, A2A_CALL_REJECTED -> InvocationResponseStatus.REJECTED;
            case INVOCATION_FAILED, A2A_CALL_FAILED -> InvocationResponseStatus.FAILED;
            case INVOCATION_TERMINAL, A2A_CALL_TERMINAL -> {
                String status = responseToken(m, "status");
                yield "completed".equals(status) || "cancelled".equals(status)
                        ? InvocationResponseStatus.COMPLETED_RESPONSE
                        : InvocationResponseStatus.FAILED;
            }
            // request eventTypes should not appear as responses; treat as unknown (be defensive)
            default -> InvocationResponseStatus.UNKNOWN;
        };
    }

    /**
     * Map an observed invocation status to an {@link IngressResponse}.
     *
     * @param requestId the ingress request id (mirrored on the response)
     * @param status   the observed invocation status
     * @param taskId   cursor for COMPLETED_RESPONSE / ACCEPTED_WITH_TASK (nullable for COMPLETED_RESPONSE)
     * @param streamRef cursor for STREAM_READY
     * @param reason   rejection reason for REJECTED / FAILED (nullable → defaulted from status)
     * @return the bus acknowledgement; never null
     */
    public IngressResponse toIngressResponse(UUID requestId, InvocationResponseStatus status,
                                             String taskId, String streamRef, String reason) {
        Objects.requireNonNull(requestId, "requestId is required");
        Objects.requireNonNull(status, "status is required");
        return switch (status) {
            case COMPLETED_RESPONSE -> IngressResponse.accepted(requestId, taskId); // cursor = taskId or null
            case ACCEPTED_WITH_TASK -> IngressResponse.accepted(requestId, taskId);
            case STREAM_READY -> IngressResponse.accepted(requestId, streamRef);
            case REJECTED, FAILED -> IngressResponse.rejected(requestId, rejectionReason(status, reason));
            case UNKNOWN -> IngressResponse.deferred(requestId);
        };
    }

    private static AgentBusEventType mapEventType(IngressEnvelope.IngressRequestType requestType, String routeFamily) {
        // routeFamily 决定事件族(topic 由 DefaultBrokerTopicResolver 按 eventType 派生,与 routeHandle 解耦):
        //   invocation 族 → CLIENT_INVOCATION_* (FEAT-013,client → gateway → event-bus → runtime);
        //   a2a      族 → A2A_CALL_* / A2A_STREAM_* (FEAT-014,runtime → runtime)。
        // TempRuntimeMain 按收到的 eventType 镜像响应族,relay 透传 eventType,gateway classify 两族都认,
        // 故只需在此按 routeFamily 切换请求事件族,整条 A2A 链路即可在 in-repo E2E 跑通。
        boolean a2a = "a2a".equals(routeFamily);
        return switch (requestType) {
            case RUN_CREATE -> a2a
                    ? AgentBusEventType.A2A_CALL_REQUESTED
                    : AgentBusEventType.CLIENT_INVOCATION_REQUESTED;
            case RUN_CANCEL -> a2a
                    ? AgentBusEventType.A2A_CALL_CANCEL_REQUESTED
                    : AgentBusEventType.CLIENT_INVOCATION_CANCEL_REQUESTED;
            case RUN_GET -> a2a
                    ? AgentBusEventType.A2A_CALL_QUERY_REQUESTED
                    : AgentBusEventType.CLIENT_INVOCATION_QUERY_REQUESTED;
            case RUN_RESUME -> a2a
                    ? AgentBusEventType.A2A_STREAM_SUBSCRIBE_REQUESTED
                    : AgentBusEventType.CLIENT_STREAM_SUBSCRIBE_REQUESTED;
        };
    }

    /**
     * Resolve the forwarding target via {@link AgentDiscoveryService} (§7.5 Option A reference impl).
     * Picks the discovery dimension present on the request attributes ({@code serviceId} →
     * {@code agentId} → {@code capability}), queries the registry, selects a candidate by weight /
     * health, and returns the discovered opaque {@code routeHandle} + {@code serviceId} + the
     * request's {@code routeFamily}.
     *
     * @param env the ingress request envelope
     * @return the discovered target (opaque routeHandle + serviceId + routeFamily)
     */
    private DiscoveredTarget resolveTarget(IngressEnvelope env) {
        String tenantId = env.tenantId();
        String routeFamily = resolveRouteFamily(env);
        List<AgentCardDto> candidates = discoverCandidates(env, tenantId);
        if (candidates.isEmpty()) {
            throw new IllegalStateException("discovery found no candidate for tenant=" + tenantId
                    + " (no matching serviceId/agentId/capability)");
        }
        AgentCardDto selected = selectCandidate(candidates);
        return new DiscoveredTarget(
                new ForwardingRouteHandle(selected.getRouteHandle(), tenantId),
                selected.getServiceId(),
                routeFamily);
    }

    private List<AgentCardDto> discoverCandidates(IngressEnvelope env, String tenantId) {
        Optional<String> serviceId = stringAttr(env, "serviceId");
        Optional<String> agentId = stringAttr(env, "agentId");
        // prefer the most specific dimension (serviceId), then agentId, then capability (default a2a)
        if (serviceId.isPresent()) {
            return discovery.searchByServiceId(tenantId, serviceId.get(), null);
        }
        if (agentId.isPresent()) {
            return discovery.searchInstancesByAgentId(tenantId, agentId.get(), null);
        }
        String cap = stringAttr(env, "capability").orElse("a2a");
        return discovery.searchByCapability(tenantId, cap, null);
    }

    private static AgentCardDto selectCandidate(List<AgentCardDto> candidates) {
        // the registry pre-sorts weight DESC, last_heartbeat DESC; prefer the first ONLINE candidate,
        // falling back to the head of the list (DEGRADED/DRAINING) when none is ONLINE.
        for (AgentCardDto c : candidates) {
            if ("ONLINE".equals(c.getHealth())) {
                return c;
            }
        }
        return candidates.get(0);
    }

    private static String resolveRouteFamily(IngressEnvelope env) {
        Optional<String> routeFamily = stringAttr(env, "routeFamily");
        if (routeFamily.isEmpty()) {
            return "invocation"; // FEAT-013 client invocation is the default family
        }
        String value = routeFamily.get();
        if (!"invocation".equals(value) && !"a2a".equals(value)) {
            throw new IllegalArgumentException(
                    "requestAttributes 'routeFamily' must be 'invocation' or 'a2a'");
        }
        return value;
    }

    private static String resolveCapability(IngressEnvelope env) {
        return stringAttr(env, "capability").orElse("a2a"); // default capability when omitted
    }

    private static Optional<String> stringAttr(IngressEnvelope env, String key) {
        Object v = env.requestAttributes().get(key);
        if (v == null) {
            return Optional.empty();
        }
        if (!(v instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException(
                    "requestAttributes '" + key + "' must be a non-blank String when present");
        }
        return Optional.of(s);
    }

    /** Resolved forwarding target: the discovered opaque routeHandle + serviceId + the request's routeFamily. */
    private record DiscoveredTarget(ForwardingRouteHandle routeHandle, String serviceId, String routeFamily) {
    }

    private static String reasonFrom(BrokerInboundMessage m, InvocationResponseStatus status) {
        String reason = responseToken(m, "reason");
        if (reason != null && !reason.isBlank()) {
            return reason;
        }
        String tokenStatus = responseToken(m, "status");
        if (tokenStatus != null && !tokenStatus.isBlank()) {
            return tokenStatus;
        }
        return m.eventType() != null ? m.eventType().name() : status.name();
    }

    private static String rejectionReason(InvocationResponseStatus status, String reason) {
        if (reason != null && !reason.isBlank()) {
            return reason;
        }
        return status.name();
    }

    static String responseToken(BrokerInboundMessage m, String key) {
        // P-06 / FEAT-014:68: response content (taskId / status / streamRef / reason) rides inlinePayload
        // (the A2A response envelope as DATA), NOT a control-descriptor payloadRef. The gateway — the
        // A2A-aware caller — interprets the inline response content here; the bus carries it opaquely.
        // Pre-P-06 this peeked a token from payloadRef (the retired descriptor codec), conflating the
        // data reference with response content.
        String result = null;
        String body = m.inlinePayload();
        if (body != null && !body.isBlank()) {
            for (String pair : body.split(";")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && pair.substring(0, eq).equals(key)) {
                    result = pair.substring(eq + 1);
                    break;
                }
            }
        }
        return result;
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name + " is required");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
