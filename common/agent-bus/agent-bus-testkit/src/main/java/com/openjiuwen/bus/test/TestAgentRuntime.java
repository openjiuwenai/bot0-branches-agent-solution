/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.bus.test;

import com.openjiuwen.bus.forwarding.spi.broker.BrokerControlDescriptor;
import com.openjiuwen.bus.forwarding.runtime.transport.broker.InMemoryBroker;
import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;
import com.openjiuwen.bus.forwarding.spi.ForwardingEnvelope;
import com.openjiuwen.bus.forwarding.spi.ForwardingMessageId;
import com.openjiuwen.bus.forwarding.spi.ForwardingOutboxRecord;
import com.openjiuwen.bus.forwarding.spi.ForwardingReceipt;
import com.openjiuwen.bus.forwarding.spi.ForwardingRouteHandle;
import com.openjiuwen.bus.forwarding.spi.ForwardingStatus;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerForwardingConsumerPort;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerForwardingRelayPort;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerInboundMessage;
import com.openjiuwen.bus.forwarding.spi.broker.DeliveryFilter;
import com.openjiuwen.bus.forwarding.test.InMemoryForwardingOutbox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-repo test double that stands in for the EXTERNAL
 * {@code agent-runtime-java} during E2E forwarding tests — NON-PRODUCTION.
 *
 * <p>It is the <em>agent-runtime END</em> of the forwarding chain (FEAT-013
 * client-invocation + FEAT-014 a2a-call): it CONSUMES request events from an
 * {@link InMemoryBroker}, "processes" them per the L2 §4.3 response state
 * machine, and PRODUCES response events back to the broker via an
 * {@link InMemoryForwardingOutbox} as its own durable outbox (mirroring how the
 * real gateway would). It is a CLIENT of the broker, not a broker itself.
 *
 * <h2>Encoding decisions (the request envelope's control fields)</h2>
 *
 * <p>{@link BrokerInboundMessage} carries ONLY routing metadata (tenantId,
 * messageId, sourceServiceId, targetServiceId, consumerServiceId, payloadRef)
 * — it has NO {@code eventType}, NO {@code body} (the routing descriptor is
 * dropped at poll), and NO {@code idempotencyKey} / {@code traceId} /
 * {@code correlationId} / {@code routeHandle}. Those envelope control fields are
 * therefore encoded into the request's {@code payloadRef} as a compact
 * {@code k=v;k=v;...} descriptor token — it is the only field that survives the
 * broker's poll and so is the only clean vehicle for the request envelope's
 * control-plane fields, given the existing SPI types cannot be modified. Use
 * {@link #buildRequest} / {@link #publishRequest} to produce a request; they
 * encode the descriptor so consumers stay decoupled from the format.
 *
 * <ul>
 *   <li><b>eventType transport</b> — encoded in the {@code payloadRef}
 *       descriptor ({@code eventType=<ET>}). The runtime decodes it to pick the
 *       response sequence (§4.3). The broker body stays a pure routing
 *       descriptor (§6.2② preserved).</li>
 *   <li><b>idempotencyKey transport</b> — same path, {@code idempotencyKey=<K>}
 *       in the descriptor. The runtime uses it for server-side creation
 *       idempotency (§4.4 layer 2): a duplicate {@code (tenantId,
 *       idempotencyKey)} returns the SAME taskId with no second logical call.</li>
 * </ul>
 *
 * <p><b>Response routing &amp; self-consumption.</b> Responses reuse the
 * request's {@code routeHandle} (so they resolve to the same broker topic) and
 * swap source/target (source=this runtime, target=the original source). Because
 * {@link InMemoryBroker#poll} scans every topic for the consumer-group, the
 * runtime would re-poll its own responses; {@link #pollAndProcess} skips
 * (commit-without-process) any polled message whose decoded eventType is NOT a
 * request type, so it always advances to the next real request.
 *
 * <h2>FEAT-001 stubs</h2>
 *
 * <p>{@code CancelTask} ({@link AgentBusEventType#CLIENT_INVOCATION_CANCEL_REQUESTED}),
 * {@code SubscribeToTask} ({@link AgentBusEventType#CLIENT_STREAM_SUBSCRIBE_REQUESTED}),
 * and {@code ListTasks}/{@code GetTask} ({@link AgentBusEventType#CLIENT_INVOCATION_QUERY_REQUESTED})
 * — plus their FEAT-014 A2A twins — map to canned responses. This is the
 * FEAT-001 routing gap in the real external agent-runtime; the stub unblocks
 * E2E for this version. See {@link #cannedResponses}.
 *
 * <p>Authority: {@code architecture/L2-Low-Level-Design/agent-bus/
 * feat-013-client-invocation-event-forwarding.md §4.3 / §4.4 / §6};
 * {@code architecture/L2-Low-Level-Design/agent-bus/
 * feat-014-a2a-call-event-forwarding.md §4.4}.
 *
 * @since 0.1.0
 */
// non-production — test fixture only; stands in for external agent-runtime-java
public final class TestAgentRuntime {
    /** Configurable response behaviour for a REQUESTED event (FEAT-013 §6 scenarios). */
    public enum ResponseMode {
        /** REQUESTED → ACCEPTED + RESPONSE + TERMINAL(completed) — §6.2.1 blocking final response. */
        BLOCKING,
        /** REQUESTED → ACCEPTED + STREAM_READY — §6.2.4 streaming call. */
        STREAMING,
        /** REQUESTED → ACCEPTED only — §6.2.2 degenerate to Task ref (gateway → ACCEPTED_WITH_TASK). */
        ACCEPTED_ONLY,
        /** REQUESTED → create task, emit nothing — §6.2.3 accept-window UNKNOWN (gateway times out). */
        SILENT
    }

    /** Outcome of a single {@link #pollAndProcess} tick. */
    public record ProcessingOutcome(Outcome outcome, String requestMessageId, String taskId,
            List<ForwardingEnvelope> responses) {
        /** Result classification of a pollAndProcess tick. */
        public enum Outcome {
            PROCESSED, SKIPPED_NON_REQUEST, IDLE
        }
        public ProcessingOutcome {
            Objects.requireNonNull(outcome, "outcome is required");
            Objects.requireNonNull(responses, "responses is required");
        }

        /**
         * Convenience: the event types of the produced responses, in order.
         */
        public List<AgentBusEventType> responseEventTypes() {
            return responses.stream().map(ForwardingEnvelope::eventType).toList();
        }
    }

    private final InMemoryBroker broker;
    private final BrokerForwardingConsumerPort consumer;
    private final InMemoryForwardingOutbox outbox;
    private final String consumerServiceId;
    private final String tenantId;
    private final AtomicLong taskIdSeq = new AtomicLong();
    private final AtomicLong respIdSeq = new AtomicLong();
    private final Map<String, TaskEntry> taskByIdempotencyKey = new LinkedHashMap<>();
    private ResponseMode responseMode = ResponseMode.BLOCKING;

    private record RequestDescriptor(
            AgentBusEventType eventType, String traceId, String correlationId,
            String idempotencyKey, String routeHandleValue, String capability,
            long deadlineMillisEpoch) {}

    private record PlannedResponse(AgentBusEventType eventType, String payloadRef) {}

    private static final class TaskEntry {
        final String taskId;
        boolean responsesEmitted;   // has the runtime ever emitted ACCEPTED/RESPONSE/TERMINAL for this task?

        TaskEntry(String taskId) {
            this.taskId = taskId;
        }
    }

    public TestAgentRuntime(InMemoryBroker broker, InMemoryForwardingOutbox outbox,
                            String consumerServiceId, String tenantId) {
        this.broker = Objects.requireNonNull(broker, "broker is required");
        this.outbox = Objects.requireNonNull(outbox, "outbox is required");
        this.consumerServiceId = requireNonBlank(consumerServiceId, "consumerServiceId");
        this.tenantId = requireNonBlank(tenantId, "tenantId");
        // Subscribe this runtime's consumer once at construction: receive only REQUEST events
        // targeted at this runtime (targetServiceId == consumerServiceId), within its tenant.
        // The route is a placeholder — the in-memory double scans every topic; a real adapter
        // (decision §7) resolves the route to the request topic(s) and may accumulate multi-route.
        this.consumer = broker.consumerFor(consumerServiceId);
        this.consumer.subscribe(consumerServiceId,
                new ForwardingRouteHandle("runtime-" + consumerServiceId, tenantId),
                DeliveryFilter.forRuntime(tenantId, consumerServiceId));
    }

    /**
     * Test-only: switch the response behaviour for subsequent REQUESTED events.
     *
     * @param mode the response behaviour to apply to subsequent REQUESTED events
     */
    public void setResponseMode(ResponseMode mode) {
        this.responseMode = Objects.requireNonNull(mode, "mode is required");
    }

    /**
     * Test-only: the broker this runtime talks to (for introspection in assertions).
     *
     * @return the in-memory broker this runtime consumes from
     */
    public InMemoryBroker broker() {
        return broker;
    }

    /**
     * Test-only: this runtime's own outbox (for introspection in assertions).
     *
     * @return the in-memory outbox this runtime produces through
     */
    public InMemoryForwardingOutbox outbox() {
        return outbox;
    }

    /**
     * Test-only introspection: the taskId a (tenantId, idempotencyKey) resolved to, if any.
     *
     * @param idempotencyKey the client retry idempotency key
     * @return the resolved taskId, or an empty Optional if no task was created for this key
     */
    public synchronized Optional<String> taskIdFor(String idempotencyKey) {
        requireNonBlank(idempotencyKey, "idempotencyKey");
        TaskEntry e = taskByIdempotencyKey.get(idempotencyKeyKey(tenantId, idempotencyKey));
        return Optional.ofNullable(e).map(t -> t.taskId);
    }

    /**
     * One tick of the core loop: poll the next REQUEST event from the broker,
     * skip (commit) any non-request messages in the way, "process" the request
     * (produce its response sequence back to the broker), then commit the request
     * (model B ack-after-consume).
     *
     * @param nowMillisEpoch the poll / process instant (epoch millis)
     * @return the tick outcome (PROCESSED with responses, SKIPPED_NON_REQUEST, or IDLE)
     */
    public synchronized ProcessingOutcome pollAndProcess(long nowMillisEpoch) {
        while (true) {
            Optional<BrokerInboundMessage> polled = consumer.poll(nowMillisEpoch);
            if (polled.isEmpty()) {
                return new ProcessingOutcome(ProcessingOutcome.Outcome.IDLE, null, null, List.of());
            }
            BrokerInboundMessage msg = polled.get();
            // soft-check the eventType BEFORE full decode: self-produced responses carry a
            // response payloadRef (taskId=...;status=...) with no eventType= token, so the
            // full decoder would throw. Skip+commit anything that is not a request descriptor.
            AgentBusEventType eventType = softEventType(msg.payloadRef()).orElse(null);
            if (eventType == null || !isRequestType(eventType)) {
                // own responses / control echoes — commit (advance our offset) and keep polling
                consumer.commit(msg);
                continue;
            }
            RequestDescriptor desc = decodeDescriptor(msg.payloadRef());
            List<ForwardingEnvelope> responses = processRequest(msg, desc, nowMillisEpoch);
            consumer.commit(msg);
            String taskId = taskIdFor(desc.idempotencyKey()).orElse(null);
            return new ProcessingOutcome(ProcessingOutcome.Outcome.PROCESSED, msg.messageId(), taskId, responses);
        }
    }

    // ===== request processing =====

    private List<ForwardingEnvelope> processRequest(BrokerInboundMessage msg, RequestDescriptor req,
                                                    long nowMillisEpoch) {
        TaskEntry entry = resolveOrCreateTask(req.eventType(), req.idempotencyKey()).orElse(null);
        String taskId = entry != null ? entry.taskId : null;
        List<PlannedResponse> planned = planResponses(req.eventType(), taskId, req.idempotencyKey());
        // mark the task's responses as emitted BEFORE producing so a retry within the same tick dedups
        if (entry != null && !planned.isEmpty()) {
            entry.responsesEmitted = true;
        }
        List<ForwardingEnvelope> produced = new ArrayList<>(planned.size());
        for (PlannedResponse p : planned) {
            ForwardingEnvelope env = buildResponseEnvelope(req, msg, p, nowMillisEpoch);
            produceResponse(env, nowMillisEpoch);
            produced.add(env);
        }
        return List.copyOf(produced);
    }

    /**
     * Server-side creation idempotency (§4.4 layer 2): a REQUESTED/A2A_CALL_REQUESTED
     * creates a task; a repeat with the same {@code (tenantId, idempotencyKey)}
     * reuses the SAME taskId (no second logical call). CANCEL/QUERY/SUBSCRIBE do
     * NOT create tasks (they operate on an existing taskId), so they return an empty
     * Optional.
     *
     * @param eventType      the request event type
     * @param idempotencyKey the client retry idempotency key
     * @return the created/reused task entry, or an empty Optional for non-creating request types
     */
    private Optional<TaskEntry> resolveOrCreateTask(AgentBusEventType eventType, String idempotencyKey) {
        if (eventType != AgentBusEventType.CLIENT_INVOCATION_REQUESTED
                && eventType != AgentBusEventType.A2A_CALL_REQUESTED) {
            return Optional.empty(); // stubs operate on an existing taskId; no creation here
        }
        return Optional.of(taskByIdempotencyKey.computeIfAbsent(
                idempotencyKeyKey(tenantId, idempotencyKey),
                k -> new TaskEntry("task-" + taskIdSeq.incrementAndGet())));
    }

    /**
     * Map a request eventType to its response sequence per L2 §4.3 + the §6
     * scenarios. FEAT-001 stubs (CANCEL/QUERY/SUBSCRIBE and their A2A twins)
     * produce canned responses.
     *
     * @param eventType      the request event type (REQUESTED family or a FEAT-001 stub type)
     * @param taskId         the task id stamped on the responses (null for non-creating stubs)
     * @param idempotencyKey the client retry idempotency key (for the already-emitted dedup check)
     * @return the planned response sequence for the request type
     */
    private List<PlannedResponse> planResponses(AgentBusEventType eventType, String taskId,
                                               String idempotencyKey) {
        if (eventType == AgentBusEventType.CLIENT_INVOCATION_REQUESTED
                || eventType == AgentBusEventType.A2A_CALL_REQUESTED) {
            boolean a2a = eventType == AgentBusEventType.A2A_CALL_REQUESTED;
            AgentBusEventType accepted = a2a
                    ? AgentBusEventType.A2A_CALL_ACCEPTED : AgentBusEventType.INVOCATION_ACCEPTED;
            AgentBusEventType response = a2a
                    ? AgentBusEventType.A2A_CALL_RESPONSE : AgentBusEventType.INVOCATION_RESPONSE;
            AgentBusEventType terminal = a2a
                    ? AgentBusEventType.A2A_CALL_TERMINAL : AgentBusEventType.INVOCATION_TERMINAL;
            AgentBusEventType streamReady = a2a
                    ? AgentBusEventType.A2A_STREAM_READY : AgentBusEventType.INVOCATION_STREAM_READY;
            TaskEntry entry = taskByIdempotencyKey.get(idempotencyKeyKey(tenantId, idempotencyKey));
            // §4.4: an already-emitted task on a repeat REQUESTED → re-emit only ACCEPTED (same taskId)
            if (entry != null && entry.responsesEmitted) {
                return List.of(new PlannedResponse(accepted, "taskId=" + entry.taskId));
            }
            return switch (responseMode) {
                case BLOCKING -> List.of(
                        new PlannedResponse(accepted, "taskId=" + taskId),
                        new PlannedResponse(response, "taskId=" + taskId + ";status=snapshot"),
                        new PlannedResponse(terminal, "taskId=" + taskId + ";status=completed"));
                case STREAMING -> List.of(
                        new PlannedResponse(accepted, "taskId=" + taskId),
                        new PlannedResponse(streamReady, "taskId=" + taskId + ";streamRef=stream://" + taskId));
                case ACCEPTED_ONLY -> List.of(
                        new PlannedResponse(accepted, "taskId=" + taskId));
                case SILENT -> List.of(); // create task (done above), emit nothing → gateway UNKNOWN
            };
        }
        return cannedResponses(eventType);
    }

    /**
     * FEAT-001 stubs: CancelTask / SubscribeToTask / ListTasks(GetTask) and their
     * FEAT-014 A2A twins. The real external agent-runtime does not yet route these
     * (FEAT-001 gap); these canned responses unblock E2E for this version.
     *
     * @param eventType the stub request event type (CANCEL/QUERY/SUBSCRIBE or an A2A twin)
     * @return the canned response sequence for the stub type
     */
    private List<PlannedResponse> cannedResponses(AgentBusEventType eventType) {
        return switch (eventType) {
            case CLIENT_INVOCATION_CANCEL_REQUESTED -> List.of(
                    new PlannedResponse(AgentBusEventType.INVOCATION_TERMINAL,
                            "status=cancelled")); // FEAT-001 stub: CancelTask → cancelled terminal
            case A2A_CALL_CANCEL_REQUESTED -> List.of(
                    new PlannedResponse(AgentBusEventType.A2A_CALL_TERMINAL,
                            "status=cancelled")); // FEAT-001 stub: A2A CancelTask
            case CLIENT_INVOCATION_QUERY_REQUESTED -> List.of(
                    new PlannedResponse(AgentBusEventType.INVOCATION_RESPONSE,
                            "status=snapshot")); // FEAT-001 stub: GetTask/ListTasks → snapshot
            case A2A_CALL_QUERY_REQUESTED -> List.of(
                    new PlannedResponse(AgentBusEventType.A2A_CALL_RESPONSE,
                            "status=snapshot")); // FEAT-001 stub: A2A GetTask
            case CLIENT_STREAM_SUBSCRIBE_REQUESTED -> List.of(
                    new PlannedResponse(AgentBusEventType.INVOCATION_STREAM_READY,
                            "streamRef=stream://stub")); // FEAT-001 stub: SubscribeToTask
            case A2A_STREAM_SUBSCRIBE_REQUESTED -> List.of(
                    new PlannedResponse(AgentBusEventType.A2A_STREAM_READY,
                            "streamRef=stream://stub")); // FEAT-001 stub: A2A SubscribeToTask
            default -> throw new IllegalArgumentException(
                    "unsupported request eventType for TestAgentRuntime: " + eventType);
        };
    }

    // ===== response envelope construction + produce path =====

    private ForwardingEnvelope buildResponseEnvelope(RequestDescriptor req, BrokerInboundMessage msg,
                                                    PlannedResponse p, long nowMillisEpoch) {
        ForwardingRouteHandle route = new ForwardingRouteHandle(req.routeHandleValue(), tenantId);
        // response: source=this runtime, target=original source (gateway/caller). Swap source↔target.
        return new ForwardingEnvelope(
                new ForwardingMessageId("resp-" + respIdSeq.incrementAndGet()),
                p.eventType(),
                tenantId,
                req.traceId(),
                req.correlationId(),
                req.idempotencyKey(),
                route,
                req.capability(),
                consumerServiceId,             // sourceServiceId = this runtime
                msg.sourceServiceId(),         // targetServiceId = original caller
                req.deadlineMillisEpoch(),
                ForwardingEnvelope.PayloadPolicy.CONTROL_ONLY,
                p.payloadRef());               // small control descriptor; not a payload body
    }

    /**
     * Simplified-but-realistic produce path (mirrors the gateway): enqueue into
     * the runtime's own outbox → claim → {@link BrokerForwardingRelayPort#produce}
     * → markAcked. A full worker loop is intentionally not modelled.
     *
     * @param env           the response envelope to produce
     * @param nowMillisEpoch the produce instant
     */
    private void produceResponse(ForwardingEnvelope env, long nowMillisEpoch) {
        ForwardingReceipt receipt = outbox.enqueue(env, env.sourceServiceId(), env.targetServiceId(), nowMillisEpoch);
        List<ForwardingOutboxRecord> claimed = outbox.claimDue(env.tenantId(), nowMillisEpoch, 1,
                consumerServiceId, nowMillisEpoch + 60_000L);
        for (ForwardingOutboxRecord r : claimed) {
            broker.produce(r, nowMillisEpoch);
            outbox.markAcked(r.messageId(), r.tenantId(), consumerServiceId);
        }
        // receipt unused beyond enqueue; the outbox dedups on (tenantId, messageId)
        Objects.requireNonNull(receipt, "enqueue receipt");
    }

    // ===== request publish helpers (encoding encapsulated here) =====

    /**
     * Build a request envelope whose {@code payloadRef} carries the control
     * descriptor (eventType/traceId/correlationId/idempotencyKey/routeHandle/
     * capability/deadline). Use {@link #publishRequest} to put it on the broker.
     *
     * @param messageId           the request message id (broker keys / dedup hook)
     * @param eventType           the request event type (FEAT-013/014 family)
     * @param tenantId            tenant scope
     * @param traceId             W3C 32-char hex trace id
     * @param correlationId       cross-hop correlation key (gateway = requestId)
     * @param idempotencyKey      client retry idempotency key
     * @param routeHandleValue    opaque route handle value (resolved via ForwardingEndpointResolver)
     * @param capability          capability identifier
     * @param sourceServiceId     the request source service id (the caller/gateway)
     * @param targetServiceId     the request target service id (the runtime)
     * @param deadlineMillisEpoch absolute deadline, or {@code Long.MAX_VALUE} for none
     * @return the request envelope carrying the control descriptor in its payloadRef
     */
    public static ForwardingEnvelope buildRequest(String messageId, AgentBusEventType eventType,
                                                  String tenantId, String traceId, String correlationId,
                                                  String idempotencyKey, String routeHandleValue,
                                                  String capability, String sourceServiceId,
                                                  String targetServiceId, long deadlineMillisEpoch) {
        RequestDescriptor reqDescriptor = new RequestDescriptor(eventType, traceId, correlationId,
                idempotencyKey, routeHandleValue, capability, deadlineMillisEpoch);
        String descriptor = encodeDescriptor(reqDescriptor);
        return new ForwardingEnvelope(
                new ForwardingMessageId(messageId),
                eventType, tenantId, traceId, correlationId, idempotencyKey,
                new ForwardingRouteHandle(routeHandleValue, tenantId),
                capability, sourceServiceId, targetServiceId, deadlineMillisEpoch,
                ForwardingEnvelope.PayloadPolicy.DATA_BEARING, descriptor);
    }

    /**
     * Produce a request envelope onto the broker so a {@link TestAgentRuntime} can poll it.
     *
     * @param broker        the in-memory broker to produce onto
     * @param request       the request envelope (built via {@link #buildRequest})
     * @param nowMillisEpoch the produce instant
     */
    public static void publishRequest(InMemoryBroker broker, ForwardingEnvelope request, long nowMillisEpoch) {
        Objects.requireNonNull(broker, "broker is required");
        Objects.requireNonNull(request, "request is required");
        ForwardingOutboxRecord record = new ForwardingOutboxRecord(
                request.tenantId(),
                request.messageId(),
                request.sourceServiceId(),
                request.targetServiceId(),
                request.routeHandle(),
                request.payloadRef(),
                ForwardingStatus.Outbox.PENDING,
                0, 0L, nowMillisEpoch, nowMillisEpoch, null, null,
                request.correlationId(),        // FEAT-013 cross-hop correlation (mirrored from request envelope)
                request.eventType());           // FEAT-013/014 event-type (mirrored from request envelope)
        broker.produce(record, nowMillisEpoch);
    }

    // ===== descriptor encode / decode (delegated to the shared main utility) =====
    //
    // The codec lives in BrokerControlDescriptor (forwarding.spi.broker,
    // main) so the production GatewayRuntimeService can build a request payloadRef that
    // this test double decodes, without main depending on a test fixture. These
    // package-private delegates keep the call sites (buildRequest / pollAndProcess)
    // and the RequestDescriptor shape unchanged — behaviour is identical.

    static String encodeDescriptor(RequestDescriptor req) {
        return BrokerControlDescriptor.encode(req.eventType(), req.traceId(), req.correlationId(),
                req.idempotencyKey(), req.routeHandleValue(), req.capability(), req.deadlineMillisEpoch());
    }

    /**
     * Soft-extract the {@code eventType} token from a payloadRef without throwing —
     * returns an empty Optional if the descriptor has no {@code eventType=} token or the value is
     * not a known {@link AgentBusEventType}. Used to skip self-produced responses
     * (which carry {@code taskId=...;status=...}) before the full, validating decode.
     *
     * @param payloadRef the descriptor (nullable; null/blank → empty)
     * @return the event type, or an empty Optional if absent / unknown
     */
    static Optional<AgentBusEventType> softEventType(String payloadRef) {
        return BrokerControlDescriptor.softEventType(payloadRef);
    }

    static RequestDescriptor decodeDescriptor(String payloadRef) {
        BrokerControlDescriptor.Descriptor d = BrokerControlDescriptor.decode(payloadRef);
        return new RequestDescriptor(d.eventType(), d.traceId(), d.correlationId(),
                d.idempotencyKey(), d.routeHandle(), d.capability(), d.deadlineMillisEpoch());
    }

    private static boolean isRequestType(AgentBusEventType t) {
        return switch (t) {
            case CLIENT_INVOCATION_REQUESTED, CLIENT_INVOCATION_CANCEL_REQUESTED,
                 CLIENT_INVOCATION_QUERY_REQUESTED, CLIENT_STREAM_SUBSCRIBE_REQUESTED,
                 A2A_CALL_REQUESTED, A2A_CALL_CANCEL_REQUESTED,
                 A2A_CALL_QUERY_REQUESTED, A2A_STREAM_SUBSCRIBE_REQUESTED -> true;
            default -> false; // response / terminal types — skip (own echoes)
        };
    }

    private static String idempotencyKeyKey(String tenantId, String idempotencyKey) {
        return tenantId + "|" + idempotencyKey;
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name + " is required");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
