/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.caller;

import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;
import com.openjiuwen.bus.forwarding.spi.ForwardingFailureCode;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerForwardingConsumerPort;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerInboundMessage;
import com.openjiuwen.bus.forwarding.spi.broker.DeliveryFilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

/**
 * Subscribes to Runtime caller responses and routes them to pending remote calls.
 *
 * @since 2026-08-04
 */
public final class AgentBusCallerResponseLifecycle implements SmartLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(AgentBusCallerResponseLifecycle.class);
    private static final AgentBusEventType[] RESPONSE_TYPES = {
            AgentBusEventType.A2A_CALL_ACCEPTED,
            AgentBusEventType.A2A_CALL_REJECTED,
            AgentBusEventType.A2A_CALL_FAILED,
            AgentBusEventType.A2A_CALL_RESPONSE,
            AgentBusEventType.A2A_CALL_INPUT_REQUIRED,
            AgentBusEventType.A2A_STREAM_READY,
            AgentBusEventType.A2A_CALL_TERMINAL
    };

    private final BrokerForwardingConsumerPort consumer;
    private final AgentBusRemoteAgentCaller caller;
    private final ExecutorService executor;
    private final String consumerServiceId;
    private final String tenantId;
    private final String sourceServiceId;
    private volatile boolean running;

    /**
     * Creates the caller response lifecycle.
     *
     * @param consumer caller-role response consumer
     * @param caller pending call owner
     * @param executor owned polling executor
     * @param consumerServiceId broker consumer group
     * @param tenantId tenant scope
     * @param sourceServiceId local Runtime service identifier
     */
    public AgentBusCallerResponseLifecycle(BrokerForwardingConsumerPort consumer, AgentBusRemoteAgentCaller caller,
            ExecutorService executor, String consumerServiceId, String tenantId, String sourceServiceId) {
        this.consumer = Objects.requireNonNull(consumer, "consumer is required");
        this.caller = Objects.requireNonNull(caller, "caller is required");
        this.executor = Objects.requireNonNull(executor, "executor is required");
        this.consumerServiceId = require(consumerServiceId, "consumerServiceId");
        this.tenantId = require(tenantId, "tenantId");
        this.sourceServiceId = require(sourceServiceId, "sourceServiceId");
    }

    @Override
    public void start() {
        DeliveryFilter filter = new DeliveryFilter(Map.of("tenantId", tenantId,
                "targetServiceId", sourceServiceId));
        for (AgentBusEventType type : RESPONSE_TYPES) {
            consumer.subscribe(consumerServiceId, type, filter);
        }
        running = true;
        executor.execute(this::pollLoop);
    }

    @Override
    public void stop() {
        running = false;
        consumer.close();
        executor.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MIN_VALUE + 100;
    }

    private void pollLoop() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                consumer.poll(System.currentTimeMillis()).ifPresent(this::process);
            } catch (RuntimeException failure) {
                if (running) {
                    LOG.warn("Agent Bus caller response poll failed", failure);
                }
            }
        }
    }

    private void process(BrokerInboundMessage message) {
        try {
            validate(message);
            caller.accept(message);
            consumer.commit(message);
        } catch (IllegalArgumentException protocolFailure) {
            caller.failProtocol(message.correlationId(), protocolFailure);
            consumer.commit(message);
            LOG.warn("Discarded invalid Agent Bus response messageId={}", message.messageId(), protocolFailure);
        } catch (RuntimeException transientFailure) {
            consumer.reject(message, ForwardingFailureCode.RECEIVER_UNAVAILABLE);
            LOG.warn("Rejected Agent Bus response messageId={}", message.messageId(), transientFailure);
        }
    }

    private void validate(BrokerInboundMessage message) {
        if (!tenantId.equals(message.tenantId()) || !sourceServiceId.equals(message.targetServiceId())) {
            throw new IllegalArgumentException("Agent Bus response target does not match this Runtime");
        }
        if (message.correlationId() == null || message.correlationId().isBlank()) {
            throw new IllegalArgumentException("Agent Bus response correlationId is required");
        }
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
