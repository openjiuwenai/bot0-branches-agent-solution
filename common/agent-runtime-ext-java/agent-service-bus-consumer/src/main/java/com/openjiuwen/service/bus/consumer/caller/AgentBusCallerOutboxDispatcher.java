/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.caller;

import com.openjiuwen.bus.forwarding.spi.ForwardingFailureCode;
import com.openjiuwen.bus.forwarding.spi.ForwardingOutboxClaimPort;
import com.openjiuwen.bus.forwarding.spi.ForwardingOutboxPort;
import com.openjiuwen.bus.forwarding.spi.ForwardingOutboxRecord;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerForwardingProducerPort;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerProduceOutcome;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Claims this Runtime's caller outbox rows and publishes them through requestProducer.
 *
 * @since 2026-08-04
 */
public final class AgentBusCallerOutboxDispatcher implements SmartLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(AgentBusCallerOutboxDispatcher.class);

    private final ForwardingOutboxClaimPort claims;
    private final ForwardingOutboxPort outbox;
    private final BrokerForwardingProducerPort producer;
    private final ScheduledExecutorService scheduler;
    private final String tenantId;
    private final String sourceServiceId;
    private final long leaseDurationMillis;
    private final long pollIntervalMillis;
    private final int batchSize;
    private volatile boolean running;

    /**
     * Creates a caller-side outbox dispatcher.
     *
     * @param claims Agent Bus outbox claim port
     * @param outbox Agent Bus outbox mutation port
     * @param producer caller-role request producer
     * @param scheduler owned scheduler
     * @param tenantId tenant scope
     * @param sourceServiceId local Runtime service identifier
     * @param leaseDurationMillis outbox claim lease duration
     * @param pollIntervalMillis dispatch interval and retry delay
     * @param batchSize maximum rows per tick
     */
    public AgentBusCallerOutboxDispatcher(ForwardingOutboxClaimPort claims, ForwardingOutboxPort outbox,
            BrokerForwardingProducerPort producer, ScheduledExecutorService scheduler, String tenantId,
            String sourceServiceId, long leaseDurationMillis, long pollIntervalMillis, int batchSize) {
        this.claims = Objects.requireNonNull(claims, "claims is required");
        this.outbox = Objects.requireNonNull(outbox, "outbox is required");
        this.producer = Objects.requireNonNull(producer, "producer is required");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler is required");
        this.tenantId = require(tenantId, "tenantId");
        this.sourceServiceId = require(sourceServiceId, "sourceServiceId");
        this.leaseDurationMillis = positive(leaseDurationMillis, "leaseDurationMillis");
        this.pollIntervalMillis = positive(pollIntervalMillis, "pollIntervalMillis");
        this.batchSize = Math.toIntExact(positive(batchSize, "batchSize"));
    }

    /**
     * Dispatches one due batch synchronously.
     *
     * @param nowMillisEpoch dispatch instant
     * @return accepted publish count
     */
    public int dispatchOnce(long nowMillisEpoch) {
        List<ForwardingOutboxRecord> records = claims.claimDue(tenantId, nowMillisEpoch, batchSize,
                sourceServiceId, nowMillisEpoch + leaseDurationMillis);
        int accepted = 0;
        for (ForwardingOutboxRecord record : records) {
            accepted += dispatch(record, nowMillisEpoch);
        }
        return accepted;
    }

    @Override
    public void start() {
        running = true;
        scheduler.scheduleWithFixedDelay(this::safeDispatch, 0, pollIntervalMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void stop() {
        running = false;
        scheduler.shutdown();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MIN_VALUE + 200;
    }

    private int dispatch(ForwardingOutboxRecord record, long nowMillisEpoch) {
        if (!sourceServiceId.equals(record.sourceServiceId())) {
            claims.releaseLease(record.messageId(), tenantId, sourceServiceId);
            return 0;
        }
        BrokerProduceOutcome outcome = producer.produce(record, nowMillisEpoch);
        if (outcome.outcome() == BrokerProduceOutcome.Outcome.ACCEPTED) {
            outbox.markAcked(record.messageId(), tenantId, sourceServiceId);
            return 1;
        }
        ForwardingFailureCode code = failureCode(outcome);
        if (code.retryable()) {
            outbox.scheduleRetry(record.messageId(), tenantId, sourceServiceId, code,
                    nowMillisEpoch + pollIntervalMillis);
        } else {
            outbox.moveToDlq(record.messageId(), tenantId, sourceServiceId, code);
        }
        return 0;
    }

    private void safeDispatch() {
        if (!running) {
            return;
        }
        try {
            dispatchOnce(System.currentTimeMillis());
        } catch (RuntimeException failure) {
            LOG.warn("Agent Bus caller outbox dispatch failed", failure);
        }
    }

    private static ForwardingFailureCode failureCode(BrokerProduceOutcome outcome) {
        if (outcome.failureCode() != null) {
            return outcome.failureCode();
        }
        return outcome.outcome() == BrokerProduceOutcome.Outcome.ROUTE_NOT_FOUND
                ? ForwardingFailureCode.ROUTE_NOT_FOUND : ForwardingFailureCode.RECEIVER_UNAVAILABLE;
    }

    private static long positive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be greater than zero");
        }
        return value;
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
