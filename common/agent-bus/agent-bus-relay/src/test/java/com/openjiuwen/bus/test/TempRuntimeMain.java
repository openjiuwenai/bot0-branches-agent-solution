/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.bus.test;

import com.openjiuwen.bus.forwarding.runtime.transport.BrokerTopicResolver;
import com.openjiuwen.bus.forwarding.runtime.transport.broker.rocketmq.RocketMqBrokerForwardingConsumer;
import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;
import com.openjiuwen.bus.forwarding.spi.ForwardingRouteHandle;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerControlDescriptor;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerInboundMessage;
import com.openjiuwen.bus.forwarding.spi.broker.DeliveryFilter;

import org.apache.rocketmq.client.exception.MQBrokerException;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.remoting.exception.RemotingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Standalone main-class runtime double for E2E verification of FEAT-013 (and FEAT-014)
 * two-hop broker forwarding against a REAL RocketMQ + REAL Postgres agent-bus stack.
 *
 * <p>Stands in for the EXTERNAL {@code agent-runtime-java} (which is not yet wired for
 * FEAT-013 broker ingest path). Subscribes to {@code ascend_bus_invocation_deliver}
 * (hop2 deliver), decodes the {@link BrokerControlDescriptor} payloadRef on each polled
 * request, and produces response events back to {@code ascend_bus_invocation_resp_in}
 * (the response relay governs them and re-publishes to {@code ascend_bus_invocation_resp_out}
 * for the gateway).
 *
 * <p>Mirrors the {@code TempRuntime} inner class in
 * {@code RealBrokerTwoHopRelayIntegrationTest} (G5-E IT), but is a standalone main
 * program (no JUnit harness) so it can run as a peer process alongside the gateway
 * and event-bus fat-jar processes during manual E2E verification. NON-PRODUCTION.
 *
 * <h2>Usage</h2>
 * <pre>
 *   java -cp agent-bus-*.jar \
 *        com.openjiuwen.bus.test.TempRuntimeMain \
 *        --nameserver      &lt;host:9876&gt;       \
 *        --runtime         &lt;serviceId&gt;       \
 *        --tenant          &lt;tenantId&gt;        \
 *        --mode            BLOCKING|STREAMING|ACCEPTED_ONLY|SILENT|REJECT|DEFER_THEN_RESOLVE \
 *        [--poll-wait-ms   3000]              \
 *        [--consumer-group &lt;group&gt;]          \
 *        [--producer-group &lt;group&gt;]          \
 *        [--route          invocation|a2a]    \
 *        [--verbose]
 * </pre>
 *
 * <h2>Defaults</h2>
 * <ul>
 *   <li>{@code --mode} = {@code BLOCKING} (S6.2.1)</li>
 *   <li>{@code --route} = {@code invocation} (FEAT-013; use {@code a2a} for FEAT-014)</li>
 *   <li>{@code --poll-wait-ms} = {@code 3000}</li>
 *   <li>{@code --consumer-group} = {@code runtime-<serviceId>} (per-runtime isolation)</li>
 *   <li>{@code --producer-group} = {@code runtime-producer-<serviceId>}</li>
 * </ul>
 *
 * <p>The runtime subscribes with {@link DeliveryFilter#forRuntime} ({@code tenantId +
 * targetServiceId = runtime}) - broker-side SQL92 filtering (requires
 * {@code enablePropertyFilter=true} in broker.conf). It blocks on {@code poll} and
 * processes one request per tick (model B ack-after-consume: {@code commit} on success,
 * skip+commit on non-request descriptors). Shutdown via SIGINT (Ctrl+C) - the shutdown
 * hook closes the consumer + producer cleanly.
 *
 * <p><b>Response routing.</b> Responses are produced directly to
 * {@code ascend_bus_<route>_resp_in} (NOT to the request topic), carrying the symmetric
 * {@link BrokerControlDescriptor} payloadRef so the response relay can recover the
 * control fields (traceId / idempotencyKey / routeHandle / capability / deadline) plus
 * the {@code taskId} / {@code status} / {@code streamRef} tokens the gateway reads via
 * {@link BrokerControlDescriptor#token}. Source = this runtime, target = the original
 * source (gateway / caller).
 *
 * <p>Authority: {@code architecture/L2-Low-Level-Design/agent-bus/
 * feat-013-client-invocation-event-forwarding.md} S4.3 / S6.2;
 * {@code RealBrokerTwoHopRelayIntegrationTest.TempRuntime} (G5-E IT reference impl).
 *
 * @since 0.1.0
 */
// non-production - E2E verification runtime double (mirrors G5-E IT TempRuntime)
public final class TempRuntimeMain {
    private static final Logger log = LoggerFactory.getLogger(TempRuntimeMain.class);
    private static final String TOPIC_PREFIX = BrokerTopicResolver.TOPIC_PREFIX; // "ascend_bus_"
    private static final long DEFAULT_POLL_WAIT_MS = 3_000L;

    private final String nameserver;
    private final String runtimeServiceId;
    private final String tenant;
    private final ResponseMode mode;
    private final long pollWaitMillis;
    private final String route; // "invocation" | "a2a"
    private final boolean verbose;

    private final DefaultMQProducer producer;
    private final RocketMqBrokerForwardingConsumer consumer;
    private final String consumerGroup;
    private final String respInTopic;

    private final AtomicLong taskSeq = new AtomicLong();

    // server-side creation idempotency (S4.4 layer 2): (idempotencyKey) then taskId
    private final Map<String, String> taskByIdempotencyKey = new LinkedHashMap<>();
    private volatile boolean running;

    public TempRuntimeMain(String nameserver, String runtimeServiceId, String tenant,
                           ResponseMode mode, long pollWaitMillis, String route,
                           String consumerGroup, String producerGroup, boolean verbose) {
        this.nameserver = Objects.requireNonNull(nameserver, "nameserver is required");
        this.runtimeServiceId = Objects.requireNonNull(runtimeServiceId, "runtimeServiceId is required");
        this.tenant = Objects.requireNonNull(tenant, "tenant is required");
        this.mode = Objects.requireNonNull(mode, "mode is required");
        this.pollWaitMillis = pollWaitMillis > 0 ? pollWaitMillis : DEFAULT_POLL_WAIT_MS;
        this.route = Objects.requireNonNull(route, "route is required");
        this.verbose = verbose;
        this.consumerGroup = consumerGroup != null ? consumerGroup : "runtime-" + runtimeServiceId;
        String producerGroupResolved = producerGroup != null ? producerGroup
                : "runtime-producer-" + runtimeServiceId;

        // consumer: subscribe to hop2 deliver topic (ascend_bus_<route>_deliver)
        this.consumer = new RocketMqBrokerForwardingConsumer(
                new BrokerTopicResolver("deliver"),
                RocketMqBrokerForwardingConsumer.defaultPollerFactory(nameserver),
                this.pollWaitMillis);
        // producer: produce to resp_in topic (ascend_bus_<route>_resp_in)
        this.respInTopic = TOPIC_PREFIX + route + "_resp_in";
        this.producer = new DefaultMQProducer(producerGroupResolved);
        this.producer.setNamesrvAddr(nameserver);
    }

    /** Configurable response behaviour for a REQUESTED event (FEAT-013 S6 scenarios). */
    public enum ResponseMode {
        /** S6.2.1 - REQUESTED then ACCEPTED + RESPONSE(snapshot) + TERMINAL(completed). */
        BLOCKING,
        /** S6.2.4 - REQUESTED then ACCEPTED + STREAM_READY(streamRef). */
        STREAMING,
        /** S6.2.2 - REQUESTED then ACCEPTED only (degenerate to Task ref). */
        ACCEPTED_ONLY,
        /** S6.2.3 - REQUESTED then create task, emit nothing (gateway accept window UNKNOWN). */
        SILENT,
        /** S6.2.5 UC-07 - REQUESTED then REJECTED (server refuses; no Task created). */
        REJECT,
        /**
         * S6.2.3 UC-03 - first REQUESTED with a given idempotencyKey creates the Task
         * but emits nothing (gateway accept window then UNKNOWN); a second REQUESTED with
         * the same idempotencyKey hits the server-side idempotency map and replays the
         * BLOCKING response sequence with the same taskId. This avoids SIGSTOP/SIGCONT
         * orchestration for the UNKNOWN-then-retry scenario.
         */
        DEFER_THEN_RESOLVE
    }

    /**
     * Start the runtime: start the producer, subscribe the consumer with the per-runtime
     * filter, register a JVM shutdown hook, and enter the blocking poll loop. Blocks
     * until {@link #shutdown()} is invoked (typically via the shutdown hook on Ctrl+C).
     *
     * @throws Exception if the producer fails to start or the consumer fails to subscribe
     */
    public void start() throws Exception {
        log("Starting TempRuntime: nameserver=" + nameserver
                + " runtime=" + runtimeServiceId + " tenant=" + tenant
                + " mode=" + mode + " route=" + route
                + " consumerGroup=" + consumerGroup
                + " respInTopic=" + respInTopic);

        producer.start();
        log("Producer started (group=" + producer.getProducerGroup() + ")");

        // tenant-only filter would also work for a single-tenant relay, but forRuntime
        // (tenantId + targetServiceId) is the strict per-runtime filter (D2/D10) - only
        // messages targeted at THIS runtime are delivered broker-side.
        DeliveryFilter filter = DeliveryFilter.forRuntime(tenant, runtimeServiceId);
        consumer.subscribe(consumerGroup, new ForwardingRouteHandle(route, tenant), filter);
        log("Consumer subscribed: group=" + consumerGroup + " route=" + route
                + " filter=" + filter.requiredProperties());

        Thread shutdownHook = Executors.defaultThreadFactory().newThread(this::shutdown);
        shutdownHook.setName("temp-runtime-shutdown");
        shutdownHook.setUncaughtExceptionHandler((t, e) ->
                log.error("Uncaught exception in temp-runtime shutdown hook", e));
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        running = true;
        pollLoop();
    }

    private void pollLoop() {
        log("Poll loop started (pollWaitMs=" + pollWaitMillis + ")");
        while (running) {
            try {
                Optional<BrokerInboundMessage> msg = consumer.poll(System.currentTimeMillis());
                if (msg.isPresent()) {
                    processRequest(msg.get());
                    consumer.commit(msg.get()); // model B ack-after-consume
                }
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
            } catch (IllegalStateException | NullPointerException e) {
                if (running) {
                    log("WARN: poll/process error (transient - keep polling): " + e);
                    sleepQuiet(1_000);
                }
            }
        }
        log("Poll loop exited.");
    }

    /**
     * Cooperative shutdown - sets {@code running=false} (the poll loop checks this and
     * exits on its next iteration), then closes the consumer and producer. Idempotent -
     * a second call is a no-op once {@code running} is already false.
     */
    public synchronized void shutdown() {
        if (!running) {
            return;
        }
        running = false;
        log("Shutting down...");
        try {
            consumer.close();
        } catch (IllegalStateException | NullPointerException e) {
            log("WARN: consumer close failed: " + e);
        }
        try {
            producer.shutdown();
        } catch (IllegalStateException | NullPointerException e) {
            log("WARN: producer shutdown failed: " + e);
        }
        log("Shutdown complete.");
    }

    private synchronized void processRequest(BrokerInboundMessage req) {
        if (req.payloadRef() == null || req.payloadRef().isBlank()) {
            log("SKIP: no payloadRef (not a request descriptor) messageId=" + req.messageId());
            return;
        }
        BrokerControlDescriptor.Descriptor desc;
        try {
            desc = BrokerControlDescriptor.decode(req.payloadRef());
        } catch (IllegalArgumentException | IllegalStateException e) {
            log("SKIP: payloadRef not a valid request descriptor messageId=" + req.messageId()
                    + " err=" + e.getMessage());
            return;
        }

        AgentBusEventType eventType = desc.eventType();
        if (!isRequestType(eventType)) {
            log("SKIP: eventType not a request type (" + eventType + ") messageId=" + req.messageId());
            return;
        }

        log("RECV: messageId=" + req.messageId()
                + " eventType=" + eventType
                + " corrId=" + desc.correlationId()
                + " idem=" + desc.idempotencyKey()
                + " route=" + desc.routeHandle()
                + " cap=" + desc.capability()
                + " source=" + req.sourceServiceId());

        if (eventType == AgentBusEventType.CLIENT_INVOCATION_REQUESTED
                || eventType == AgentBusEventType.A2A_CALL_REQUESTED) {
            handleRequested(req, desc);
            return;
        }

        // FEAT-001 stubs (CancelTask / GetTask / SubscribeToTask) - canned responses
        handleStub(eventType, req, desc);
    }

    private void handleRequested(BrokerInboundMessage req, BrokerControlDescriptor.Descriptor desc) {
        RequestCtx ctx = new RequestCtx(req, desc);
        boolean a2a = desc.eventType() == AgentBusEventType.A2A_CALL_REQUESTED;
        AgentBusEventType accepted = a2a ? AgentBusEventType.A2A_CALL_ACCEPTED
                : AgentBusEventType.INVOCATION_ACCEPTED;
        AgentBusEventType response = a2a ? AgentBusEventType.A2A_CALL_RESPONSE
                : AgentBusEventType.INVOCATION_RESPONSE;
        AgentBusEventType terminal = a2a ? AgentBusEventType.A2A_CALL_TERMINAL
                : AgentBusEventType.INVOCATION_TERMINAL;
        AgentBusEventType streamReady = a2a ? AgentBusEventType.A2A_STREAM_READY
                : AgentBusEventType.INVOCATION_STREAM_READY;
        AgentBusEventType rejected = a2a ? AgentBusEventType.A2A_CALL_REJECTED
                : AgentBusEventType.INVOCATION_REJECTED;

        // S4.4 layer 2 - server-side creation idempotency: same idempotencyKey then same taskId.
        // REJECT mode does NOT create a task (server refuses the invocation outright).
        // DEFER_THEN_RESOLVE creates the task on first sight but defers the response;
        // the second hit replays the BLOCKING sequence with the same taskId.
        boolean firstSight = mode == ResponseMode.DEFER_THEN_RESOLVE
                && !taskByIdempotencyKey.containsKey(desc.idempotencyKey());
        String taskId = mode == ResponseMode.REJECT
                ? null
                : taskByIdempotencyKey.computeIfAbsent(desc.idempotencyKey(),
                        k -> "task-" + taskSeq.incrementAndGet());

        switch (mode) {
            case BLOCKING -> {
                produceResponse(accepted, taskId, "accepted", ctx);
                produceResponse(response, taskId, "snapshot", ctx);
                produceResponse(terminal, taskId, "completed", ctx);
            }
            case STREAMING -> {
                produceResponse(accepted, taskId, "accepted", ctx);
                produceResponse(streamReady, taskId, "streamReady", ctx,
                        "streamRef=stream://" + taskId);
            }
            case ACCEPTED_ONLY -> produceResponse(accepted, taskId, "accepted", ctx);
            case SILENT -> {
                // create task only - gateway accept window will time out (UNKNOWN)
                log("SILENT: task=" + taskId + " created, no response emitted");
            }
            case REJECT -> produceResponse(rejected, null, "rejected", ctx,
                    "reason=server-policy-rejected");
            case DEFER_THEN_RESOLVE -> {
                if (firstSight) {
                    // first REQUESTED: create task (already recorded above), emit nothing
                    // then gateway accept window drains then UNKNOWN/DEFERRED
                    log("DEFER_THEN_RESOLVE: first sight (task=" + taskId + "), no response emitted");
                } else {
                    // second REQUESTED with same idempotencyKey: idempotency hit then replay BLOCKING
                    // with the SAME taskId (proves S4.4 layer 2 server-side idempotency)
                    produceResponse(accepted, taskId, "accepted", ctx);
                    produceResponse(response, taskId, "snapshot", ctx);
                    produceResponse(terminal, taskId, "completed", ctx);
                }
            }
        }
    }

    /**
     * FEAT-001 stubs (CancelTask / GetTask / SubscribeToTask and their A2A twins).
     *
     * @param eventType the inbound request event type
     * @param req the inbound broker message
     * @param desc the decoded control descriptor
     */
    private void handleStub(AgentBusEventType eventType, BrokerInboundMessage req,
                            BrokerControlDescriptor.Descriptor desc) {
        RequestCtx ctx = new RequestCtx(req, desc);
        switch (eventType) {
            case CLIENT_INVOCATION_CANCEL_REQUESTED -> produceResponse(
                    AgentBusEventType.INVOCATION_TERMINAL, null, "cancelled", ctx);
            case A2A_CALL_CANCEL_REQUESTED -> produceResponse(
                    AgentBusEventType.A2A_CALL_TERMINAL, null, "cancelled", ctx);
            case CLIENT_INVOCATION_QUERY_REQUESTED -> produceResponse(
                    AgentBusEventType.INVOCATION_RESPONSE, null, "snapshot", ctx);
            case A2A_CALL_QUERY_REQUESTED -> produceResponse(
                    AgentBusEventType.A2A_CALL_RESPONSE, null, "snapshot", ctx);
            case CLIENT_STREAM_SUBSCRIBE_REQUESTED -> produceResponse(
                    AgentBusEventType.INVOCATION_STREAM_READY, null, "streamReady", ctx,
                    "streamRef=stream://stub");
            case A2A_STREAM_SUBSCRIBE_REQUESTED -> produceResponse(
                    AgentBusEventType.A2A_STREAM_READY, null, "streamReady", ctx,
                    "streamRef=stream://stub");
            default -> log("WARN: no stub handler for eventType=" + eventType);
        }
    }

    private boolean isRequestType(AgentBusEventType t) {
        return switch (t) {
            case CLIENT_INVOCATION_REQUESTED, CLIENT_INVOCATION_CANCEL_REQUESTED,
                    CLIENT_INVOCATION_QUERY_REQUESTED, CLIENT_STREAM_SUBSCRIBE_REQUESTED,
                    A2A_CALL_REQUESTED, A2A_CALL_CANCEL_REQUESTED,
                    A2A_CALL_QUERY_REQUESTED, A2A_STREAM_SUBSCRIBE_REQUESTED -> true;
            default -> false;
        };
    }

    private void produceResponse(AgentBusEventType eventType, String taskId, String status,
                                 RequestCtx ctx) {
        produceResponse(eventType, taskId, status, ctx, null);
    }

    /**
     * Build + send a response to {@link #respInTopic} with a symmetric descriptor payloadRef.
     * Body is a routing descriptor only (S6.2 2). Routing metadata rides as user properties
     * (incl. {@code correlationId} + {@code eventType} so the gateway classifies by NATIVE
     * headers - no descriptor-decoding for classification).
     *
     * @param eventType the response event type to emit
     * @param taskId the task reference (may be {@code null} for reject/cancel/query stubs)
     * @param status the response status token (e.g. "accepted", "snapshot", "completed")
     * @param ctx the inbound request + decoded descriptor bundle
     * @param extraToken an optional extra token appended to the descriptor (e.g. streamRef); may be {@code null}
     */
    private void produceResponse(AgentBusEventType eventType, String taskId, String status,
                                 RequestCtx ctx, String extraToken) {
        BrokerInboundMessage req = ctx.req();
        BrokerControlDescriptor.Descriptor desc = ctx.desc();
        // response routes back to the ORIGINAL gateway caller (carried end-to-end in the
        // descriptor originalCaller field), NOT to req.sourceServiceId() (which is the
        // event-bus relay, not the original gateway).
        String responseTarget = desc.originalCaller() != null ? desc.originalCaller() : req.sourceServiceId();
        String descriptor = BrokerControlDescriptor.encode(new BrokerControlDescriptor.Descriptor(
                eventType, desc.traceId(), desc.correlationId(), desc.idempotencyKey(),
                desc.routeHandle(), desc.capability(), desc.deadlineMillisEpoch(),
                desc.originalCaller()));
        if (taskId != null) {
            descriptor = descriptor + ";taskId=" + taskId;
        }
        descriptor = descriptor + ";status=" + status;
        if (extraToken != null && !extraToken.isBlank()) {
            descriptor = descriptor + ";" + extraToken;
        }

        String messageId = "resp-" + java.util.UUID.randomUUID();
        Message msg = new Message(respInTopic, /* tags */ null, messageId,
                ("target=" + responseTarget).getBytes(StandardCharsets.UTF_8));
        msg.putUserProperty("tenantId", tenant);
        msg.putUserProperty("messageId", messageId);
        msg.putUserProperty("sourceServiceId", runtimeServiceId);
        msg.putUserProperty("targetServiceId", responseTarget);
        msg.putUserProperty("correlationId", desc.correlationId());
        msg.putUserProperty("eventType", eventType.name());
        msg.putUserProperty("payloadRef", descriptor);

        try {
            producer.send(msg);
            log("SEND: eventType=" + eventType + " messageId=" + messageId
                    + " taskId=" + taskId + " status=" + status
                    + " target=" + responseTarget
                    + (extraToken != null ? " extra=" + extraToken : ""));
        } catch (InterruptedException | RemotingException | MQBrokerException | MQClientException e) {
            log("ERROR: producer.send failed for eventType=" + eventType
                    + " messageId=" + messageId + " err=" + e);
        }
    }

    /**
     * CLI entry point - parse args, construct the runtime, and start the poll loop.
     * Returns normally for {@code --help}; otherwise blocks in {@link #start()} until
     * shutdown (Ctrl+C / SIGINT).
     *
     * @param args CLI arguments (see class Javadoc)
     * @throws Exception if the runtime fails to start
     */
    public static void main(String[] args) throws Exception {
        Args parsed = Args.parse(args);
        if (parsed.printHelp) {
            return;
        }
        TempRuntimeMain runtime = new TempRuntimeMain(
                parsed.nameserver, parsed.runtime, parsed.tenant, parsed.mode,
                parsed.pollWaitMs, parsed.route, parsed.consumerGroup, parsed.producerGroup,
                parsed.verbose);
        runtime.start();
    }

    private void log(String msg) {
        if (msg.startsWith("ERROR")) {
            log.error("[TempRuntime {}] {}", runtimeServiceId, msg);
        } else if (msg.startsWith("WARN")) {
            log.warn("[TempRuntime {}] {}", runtimeServiceId, msg);
        } else {
            if (shouldLogInfo(msg)) {
                log.info("[TempRuntime {}] {}", runtimeServiceId, msg);
            }
        }
    }

    private boolean shouldLogInfo(String msg) {
        if (verbose) {
            return true;
        }
        return msg.startsWith("Starting") || msg.startsWith("Producer")
                || msg.startsWith("Consumer") || msg.startsWith("Shutdown")
                || msg.startsWith("Poll loop");
    }

    private void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            // cooperative cancellation - do not re-interrupt; the poll loop running flag drives exit
            running = false;
        }
    }

    /** Bundle of inbound request + decoded control descriptor (always passed together to produceResponse). */
    private record RequestCtx(BrokerInboundMessage req, BrokerControlDescriptor.Descriptor desc) {
    }

    /** Minimal CLI arg parser - flags {@code --key value} (or {@code --key=value}). */
    static final class Args {
        String nameserver;
        String runtime;
        String tenant;
        ResponseMode mode = ResponseMode.BLOCKING;
        long pollWaitMs = DEFAULT_POLL_WAIT_MS;
        String route = "invocation";
        String consumerGroup;
        String producerGroup;
        boolean verbose = false;
        boolean printHelp = false;

        static Args parse(String[] args) {
            Args a = new Args();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                String next = (i + 1 < args.length) ? args[i + 1] : null;
                if (applyArg(a, arg, next)) {
                    i++;
                }
                if (a.printHelp) {
                    return a;
                }
            }
            if (a.nameserver == null || a.runtime == null || a.tenant == null) {
                printHelp();
                throw new IllegalArgumentException(
                        "--nameserver, --runtime, --tenant are required");
            }
            return a;
        }

        private static boolean applyArg(Args a, String arg, String next) {
            return switch (arg) {
                case "--nameserver" -> {
                    a.nameserver = next;
                    yield true;
                }
                case "--runtime" -> {
                    a.runtime = next;
                    yield true;
                }
                case "--tenant" -> {
                    a.tenant = next;
                    yield true;
                }
                case "--mode" -> {
                    a.mode = ResponseMode.valueOf(next);
                    yield true;
                }
                case "--poll-wait-ms" -> {
                    a.pollWaitMs = Long.parseLong(next);
                    yield true;
                }
                case "--route" -> {
                    a.route = next;
                    yield true;
                }
                case "--consumer-group" -> {
                    a.consumerGroup = next;
                    yield true;
                }
                case "--producer-group" -> {
                    a.producerGroup = next;
                    yield true;
                }
                case "--verbose" -> {
                    a.verbose = true;
                    yield false;
                }
                case "-h", "--help" -> {
                    printHelp();
                    a.printHelp = true;
                    yield false;
                }
                default -> {
                    applyDefaultArg(a, arg);
                    yield false;
                }
            };
        }

        private static void applyDefaultArg(Args a, String arg) {
            if (arg.startsWith("--") && arg.contains("=")) {
                int eq = arg.indexOf('=');
                applyKV(a, arg.substring(2, eq), arg.substring(eq + 1));
            } else {
                throw new IllegalArgumentException("unknown arg: " + arg);
            }
        }

        private static void applyKV(Args a, String k, String v) {
            switch (k) {
                case "nameserver" -> a.nameserver = v;
                case "runtime" -> a.runtime = v;
                case "tenant" -> a.tenant = v;
                case "mode" -> a.mode = ResponseMode.valueOf(v);
                case "poll-wait-ms" -> a.pollWaitMs = Long.parseLong(v);
                case "route" -> a.route = v;
                case "consumer-group" -> a.consumerGroup = v;
                case "producer-group" -> a.producerGroup = v;
                case "verbose" -> a.verbose = Boolean.parseBoolean(v);
                default -> throw new IllegalArgumentException("unknown key: --" + k);
            }
        }

        private static void printHelp() {
            log.info("""
                    TempRuntimeMain - standalone agent-runtime double for FEAT-013/014 E2E verification.

                    Usage:
                      java -cp agent-bus-*.jar com.openjiuwen.bus.test.TempRuntimeMain \\
                        --nameserver      <host:9876>       (required)
                        --runtime         <serviceId>       (required)
                        --tenant          <tenantId>        (required)
                        --mode            BLOCKING|STREAMING|ACCEPTED_ONLY|SILENT|REJECT|DEFER_THEN_RESOLVE  (default: BLOCKING)
                        --route           invocation|a2a    (default: invocation)
                        --poll-wait-ms    3000              (default: 3000)
                        --consumer-group  <group>           (default: runtime-<serviceId>)
                        --producer-group  <group>           (default: runtime-producer-<serviceId>)
                        --verbose                           (log every poll/recv/send)
                        --help | -h                         (this message)

                    Topology:
                      consumes:  ascend_bus_<route>_deliver   (hop2 forward-relay output)
                      produces:  ascend_bus_<route>_resp_in   (response relay ingests -> resp_out -> gateway)

                    Modes (FEAT-013 S6.2):
                      BLOCKING           S6.2.1 - ACCEPTED + RESPONSE(snapshot) + TERMINAL(completed)
                      STREAMING          S6.2.4 - ACCEPTED + STREAM_READY(streamRef)
                      ACCEPTED_ONLY      S6.2.2 - ACCEPTED only (gateway returns ACCEPTED_WITH_TASK)
                      SILENT             S6.2.3 - create task, emit nothing (gateway UNKNOWN)
                      REJECT             S6.2.5 UC-07 - REJECTED (no Task created; gateway returns REJECTED)
                      DEFER_THEN_RESOLVE S6.2.3 UC-03 - first REQUESTED creates task but emits nothing
                                          (gateway UNKNOWN); second REQUESTED with same idempotencyKey
                                          replays BLOCKING with the same taskId (server-side idempotency)

                    Shutdown: Ctrl+C (SIGINT) - clean shutdown hook closes consumer + producer.
                    """);
        }
    }
}
