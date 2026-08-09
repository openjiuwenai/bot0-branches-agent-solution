/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.bus.control;

import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerForwardingConsumerPort;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerInboundMessage;
import com.openjiuwen.bus.forwarding.spi.broker.DeliveryFilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Gateway-side projection feed backed by the agent-bus SDK {@code responseConsumer}
 * ({@link BrokerForwardingConsumerPort}). Subscribes at startup to the INVOCATION_* response
 * family on {@code ascend_bus_invocation_resp_out}, polls, matches by {@code correlationId},
 * maps the descriptor to {@link ProjectionEvent}, and commits
 * (FEAT-012 §4.5 I-04 inbound + §4.6 fold).
 *
 * <p><b>issue E-layer2 dispatcher model.</b> A single background dispatcher thread owns the shared
 * blocking consumer (RocketMQ {@code poll} blocks up to {@code pollWaitMillis}); it drains messages
 * and routes each to its {@code correlationId}'s staging queue. {@link #poll} reads ONLY its own
 * staging queue (bounded wait) — servlet threads no longer contend on the shared consumer, and an
 * event for {@code corrId A} is delivered to {@code A}'s queue the moment the dispatcher pulls it
 * (no one-message-per-poll stage-retry that caused 14-15s TERMINAL match latency under concurrent
 * streams). Routing + ack-after-consume ({@code commit}) happen in the dispatcher; the SPI
 * at-least-once contract (commit after map) is preserved.
 *
 * <p>Staging cleanup is race-free: {@code drainOnce} adds to a queue under {@code synchronized(queue)},
 * and {@link #poll} removes the corrId entry only when its queue is empty under the same lock — a
 * concurrently-routed event is never dropped (it lands in a fresh queue the next poll drains).
 *
 * <p>Subscribes to the INVOCATION_* family ONLY — A2A_CALL_* (FEAT-014) folding is the caller
 * runtime's job (FEAT-005), not the gateway's.
 *
 * @since 2026-07-27
 */
public class BrokerProjectionFeed implements ProjectionFeed, SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(BrokerProjectionFeed.class);

    /** INVOCATION_* response family the gateway folds (FEAT-012 §4.12.3). */
    private static final AgentBusEventType[] RESPONSE_FAMILY = {
            AgentBusEventType.INVOCATION_ACCEPTED,
            AgentBusEventType.INVOCATION_REJECTED,
            AgentBusEventType.INVOCATION_FAILED,
            AgentBusEventType.INVOCATION_RESPONSE,
            AgentBusEventType.INVOCATION_INPUT_REQUIRED,
            AgentBusEventType.INVOCATION_STREAM_READY,
            AgentBusEventType.INVOCATION_TERMINAL
    };

    /** Default max wait (ms) for {@link #poll} on an empty staging queue (production). */
    private static final long DEFAULT_POLL_TIMEOUT_MILLIS = 500L;

    /** Backoff (ms) when the dispatcher finds the consumer empty (non-blocking test fakes). */
    private static final long DISPATCHER_BACKOFF_MILLIS = 10L;

    private final BrokerForwardingConsumerPort consumer;
    private final String gatewayServiceId;
    private final long pollTimeoutMillis;
    private volatile boolean running = false;
    private Thread dispatcherThread;

    /**
     * Per-correlationId staging queues. Filled by the dispatcher; drained by {@link #poll}.
     * A polled message whose corrId matches is routed here; the owner's next {@link #poll} reads it.
     */
    private final ConcurrentHashMap<String, LinkedBlockingQueue<ProjectionEvent>> stagingByCorrId = new ConcurrentHashMap<>();

    /**
     * Creates a feed over the given response consumer with the default poll timeout.
     *
     * @param consumer SDK response-consumer port (suffix {@code resp_out})
     * @param gatewayServiceId gateway service identity (subscribe group + target filter + self-source)
     */
    public BrokerProjectionFeed(BrokerForwardingConsumerPort consumer, String gatewayServiceId) {
        this(consumer, gatewayServiceId, DEFAULT_POLL_TIMEOUT_MILLIS);
    }

    /**
     * Creates a feed with an explicit poll timeout (how long {@link #poll} blocks on an empty
     * staging queue before returning empty).
     *
     * @param consumer SDK response-consumer port (suffix {@code resp_out})
     * @param gatewayServiceId gateway service identity (subscribe group + target filter + self-source)
     * @param pollTimeoutMillis max wait (ms) on an empty per-corrId staging queue
     */
    public BrokerProjectionFeed(BrokerForwardingConsumerPort consumer, String gatewayServiceId,
                                long pollTimeoutMillis) {
        this.consumer = Objects.requireNonNull(consumer, "consumer is required");
        this.gatewayServiceId = Objects.requireNonNull(gatewayServiceId, "gatewayServiceId is required");
        this.pollTimeoutMillis = pollTimeoutMillis;
    }

    @Override
    public void start() {
        DeliveryFilter filter = new DeliveryFilter(Map.of("targetServiceId", gatewayServiceId));
        for (AgentBusEventType type : RESPONSE_FAMILY) {
            consumer.subscribe(gatewayServiceId, type, filter);
        }
        running = true;
        dispatcherThread = new Thread(this::dispatchLoop, "broker-projection-feed-dispatcher");
        dispatcherThread.setDaemon(true);
        dispatcherThread.start();
        log.info("SUBSCRIBE gateway={} to {} INVOCATION_* response types on resp_out (filter targetServiceId={}); dispatcher started",
                gatewayServiceId, RESPONSE_FAMILY.length, gatewayServiceId);
    }

    private void dispatchLoop() {
        while (running) {
            boolean processed;
        try {
                processed = drainOnce();
            } catch (RuntimeException failure) {
                // a transient consumer failure must not kill the dispatcher (the wait loop would starve)
                log.warn("projection dispatcher drain failed", failure);
                processed = false;
            }
            if (!processed) {
                sleepQuiet(DISPATCHER_BACKOFF_MILLIS);
            }
        }
    }

    /**
     * Drain ONE message from the shared consumer and route it to its corrId's staging queue.
     * Package-private test seam (the dispatcher loops this; tests call it for deterministic routing).
     *
     * @return {@code true} if a message was processed (routed / skipped / dropped), {@code false} if the consumer was empty
     */
    boolean drainOnce() {
        Optional<BrokerInboundMessage> polled = consumer.poll(System.currentTimeMillis());
        if (polled.isEmpty()) {
            return false;
        }
        BrokerInboundMessage m = polled.get();
        try {
            if (gatewayServiceId.equals(m.sourceServiceId())) {
                // self-source: the gateway does not consume its own response traffic (defensive)
                log.info("skip self-source corrId={}", m.correlationId());
                consumer.commit(m);
                return true;
            }
            ProjectionEvent evt = map(m);
            consumer.commit(m); // ack-after-consume: commit regardless of match (broker advances)
            LinkedBlockingQueue<ProjectionEvent> queue =
                    stagingByCorrId.computeIfAbsent(m.correlationId(), k -> new LinkedBlockingQueue<>());
            synchronized (queue) {
                queue.add(evt);
            }
            log.info("ROUTE corrId={} eventType={} taskId={} bodyPresent={}",
                    m.correlationId(), evt.eventType(), evt.taskId(), evt.body() != null);
            return true;
        } catch (IllegalArgumentException decodeFailure) {
            // a malformed projection must not wedge the dispatcher; commit + drop so the broker advances
            log.warn("decode failure corrId={} → drop", m.correlationId(), decodeFailure);
            consumer.commit(m);
            return true;
        }
    }

    @Override
    public Optional<ProjectionEvent> poll(String correlationId) {
        LinkedBlockingQueue<ProjectionEvent> queue =
                stagingByCorrId.computeIfAbsent(correlationId, k -> new LinkedBlockingQueue<>());
        ProjectionEvent evt;
        try {
            evt = queue.poll(pollTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // G.CON.10: do not re-interrupt; surface empty so the wait loop can re-check its own timeout
            return Optional.empty();
        }
        if (evt != null) {
            log.info("POLL staged-hit corrId={} eventType={} taskId={} bodyPresent={}",
                    correlationId, evt.eventType(), evt.taskId(), evt.body() != null);
            return Optional.of(evt);
        }
        // empty after the bounded wait — remove this corrId's queue if still empty (race-free w/ drainOnce)
        synchronized (queue) {
            if (queue.isEmpty()) {
                stagingByCorrId.remove(correlationId, queue);
            }
        }
        return Optional.empty();
    }

    private ProjectionEvent map(BrokerInboundMessage m) {
        Map<String, String> descriptor = parseDescriptor(m.inlinePayload());
        String taskId = descriptor.get("taskId");
        String streamRef = descriptor.get("streamRef");
        String a2aResponse = descriptor.get("a2aResponse");
        String body = null;
        if (a2aResponse != null && !a2aResponse.isBlank()) {
            body = decodeBase64Url(a2aResponse);
        }
        if (body == null) {
            String reason = descriptor.get("reason");
            if (reason != null && !reason.isBlank()) {
                body = reason;
            }
        }
        return new ProjectionEvent(m.eventType(), taskId, streamRef, m.payloadRef(), body);
    }

    private static Map<String, String> parseDescriptor(String inlinePayload) {
        Map<String, String> map = new HashMap<>();
        if (inlinePayload == null || inlinePayload.isBlank()) {
            return map;
        }
        for (String entry : inlinePayload.split(";")) {
            int eq = entry.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            map.put(entry.substring(0, eq), entry.substring(eq + 1));
        }
        return map;
    }

    private static String decodeBase64Url(String s) {
        int pad = (4 - s.length() % 4) % 4;
        String padded = pad == 0 ? s : s + "=".repeat(pad);
        byte[] bytes = Base64.getUrlDecoder().decode(padded);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void sleepQuiet(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            // dispatcher interrupted (stop) — exit the sleep; the loop re-checks running
        }
    }

    @Override
    public void stop() {
        running = false;
        if (dispatcherThread != null) {
            dispatcherThread.interrupt();
        }
        consumer.close();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MIN_VALUE + 100;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }
}
