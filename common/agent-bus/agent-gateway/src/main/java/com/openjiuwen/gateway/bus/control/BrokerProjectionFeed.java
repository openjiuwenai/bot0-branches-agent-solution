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
import java.util.Optional;

/**
 * Gateway-side projection feed backed by the agent-bus SDK {@code responseConsumer}
 * ({@link BrokerForwardingConsumerPort}). Subscribes at startup to the INVOCATION_* response
 * family on {@code ascend_bus_invocation_resp_out}, polls, matches by {@code correlationId},
 * maps the descriptor to {@link ProjectionEvent}, and commits
 * (FEAT-012 §4.5 I-04 inbound + §4.6 fold).
 *
 * <p>Single-request model (mirrors the relay's {@code GatewayRuntimeService.acceptWindow}):
 * each {@link #poll} pulls one message from the shared consumer; a non-matching message is
 * committed-and-skipped. Concurrent multi-request projection routing is a follow-up (L2 §0.6:
 * 730 is single-process in-memory). The SDK {@code poll} blocks up to
 * {@code poll-wait-millis} so the {@code BusForwarder} wait loop does not busy-spin
 * (FEAT-013 §5.2).
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

    private final BrokerForwardingConsumerPort consumer;
    private final String gatewayServiceId;
    private volatile boolean running = false;

    /**
     * Creates a feed over the given response consumer.
     *
     * @param consumer SDK response-consumer port (suffix {@code resp_out})
     * @param gatewayServiceId gateway service identity (subscribe group + target filter + self-source)
     */
    public BrokerProjectionFeed(BrokerForwardingConsumerPort consumer, String gatewayServiceId) {
        this.consumer = consumer;
        this.gatewayServiceId = gatewayServiceId;
    }

    @Override
    public void start() {
        DeliveryFilter filter = new DeliveryFilter(Map.of("targetServiceId", gatewayServiceId));
        for (AgentBusEventType type : RESPONSE_FAMILY) {
            consumer.subscribe(gatewayServiceId, type, filter);
        }
        running = true;
        log.info("SUBSCRIBE gateway={} to {} INVOCATION_* response types on resp_out (filter targetServiceId={})",
                gatewayServiceId, RESPONSE_FAMILY.length, gatewayServiceId);
    }

    @Override
    public Optional<ProjectionEvent> poll(String correlationId) {
        Optional<BrokerInboundMessage> polled = consumer.poll(System.currentTimeMillis());
        if (polled.isEmpty()) {
            return Optional.empty();
        }
        BrokerInboundMessage m = polled.get();
        log.info("POLL waiting corrId={} got msg corrId={} target={} eventType={} source={} inlinePayload={}",
                correlationId, m.correlationId(), m.targetServiceId(), m.eventType(),
                m.sourceServiceId(), m.inlinePayload());
        try {
            if (gatewayServiceId.equals(m.sourceServiceId())) {
                // self-source: the gateway does not consume its own response traffic (defensive)
                log.info("skip self-source corrId={}", m.correlationId());
                consumer.commit(m);
                return Optional.empty();
            }
            if (!correlationId.equals(m.correlationId())) {
                // non-matching: consume + skip (single-request model; concurrent routing is a follow-up)
                log.info("skip non-matching corrId (wanted {} got {})", correlationId, m.correlationId());
                consumer.commit(m);
                return Optional.empty();
            }
            ProjectionEvent evt = map(m);
            log.info("MATCH corrId={} eventType={} taskId={} streamRef={} bodyPresent={}",
                    m.correlationId(), evt.eventType(), evt.taskId(), evt.streamRef(), evt.body() != null);
            consumer.commit(m);
            return Optional.of(evt);
        } catch (IllegalArgumentException decodeFailure) {
            // a malformed projection must not wedge the wait loop; commit + drop so the broker advances
            log.warn("decode failure corrId={} → drop", m.correlationId(), decodeFailure);
            consumer.commit(m);
            return Optional.empty();
        }
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

    @Override
    public void stop() {
        running = false;
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
