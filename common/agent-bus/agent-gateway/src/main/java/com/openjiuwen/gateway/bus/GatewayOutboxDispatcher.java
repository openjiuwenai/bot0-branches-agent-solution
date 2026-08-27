/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.bus;

import com.openjiuwen.bus.forwarding.spi.ClaimDueRequest;
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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Caller-side outbox dispatcher (the missing caller pump, FEAT-012 §4.4): a scheduled loop that
 * claims due outbox records the gateway enqueued, produces each to {@code ascend_bus_*_req}
 * via the SDK {@code requestProducer}, and mark-acks (or schedules retry). Without this, the
 * gateway's {@link com.openjiuwen.gateway.bus.control.BusControlForwarder#forward} enqueues to
 * the durable outbox but nothing republishes to the broker → the relay's forward consumer
 * never sees a request → the gateway times out UNKNOWN.
 *
 * <p>Source-scoped: claims only records whose {@code sourceServiceId} is this gateway (e.g. it
 * never claims the relay's own hop2 records in the shared {@code agent_bus_forwarding_outbox}
 * table). The source-scoped claim means there is no foreign row to skip-and-release — the prior
 * claim-foreign-then-releaseLease branch (which left a stranded {@code lease_owner} residue on
 * the relay's rows and could squeeze the batch quota) is gone. The shared table is a durability
 * mechanism; this pump owns only the gateway's rows.
 *
 * <p>SPI-only: depends on {@link ForwardingOutboxClaimPort} / {@link BrokerForwardingProducerPort}
 * / {@link ForwardingOutboxPort} (agent-bus-spi) — never on agent-bus-sdk.
 *
 * @since 2026-07-28
 */
public class GatewayOutboxDispatcher implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(GatewayOutboxDispatcher.class);

    private final ForwardingOutboxClaimPort claimPort;
    private final BrokerForwardingProducerPort producer;
    private final ForwardingOutboxPort outbox;
    private final String tenant;
    private final String leaseOwner;
    private final long leaseDurationMillis;
    private final long pollIntervalMillis;
    private final int batchSize;
    private ScheduledExecutorService scheduler;
    private volatile boolean running;

    /**
     * Creates a dispatcher.
     *
     * @param claimPort          outbox claim / lease port (SDK {@code forwardingOutbox})
     * @param producer           SDK {@code requestProducer} (suffix {@code req})
     * @param outbox             outbox mutation port (ack / retry)
     * @param tenant             tenant scope of records to claim
     * @param leaseOwner         this gateway's service id (claim owner + source-scope filter)
     * @param leaseDurationMillis lease TTL for a claimed record
     * @param pollIntervalMillis  dispatch tick interval (and retry backoff)
     * @param batchSize          max records claimed per tick
     */
    public GatewayOutboxDispatcher(ForwardingOutboxClaimPort claimPort, BrokerForwardingProducerPort producer,
                                   ForwardingOutboxPort outbox, String tenant, String leaseOwner,
                                   long leaseDurationMillis, long pollIntervalMillis, int batchSize) {
        this.claimPort = claimPort;
        this.producer = producer;
        this.outbox = outbox;
        this.tenant = tenant;
        this.leaseOwner = leaseOwner;
        this.leaseDurationMillis = leaseDurationMillis;
        this.pollIntervalMillis = pollIntervalMillis;
        this.batchSize = batchSize;
    }

    /**
     * Run one dispatch tick. Testable synchronously; the {@link SmartLifecycle} scheduler drives
     * it on {@code pollIntervalMillis}.
     *
     * @param nowMillisEpoch the claim / produce instant
     * @return how many records were produced + acked this tick
     */
    public int dispatchOnce(long nowMillisEpoch) {
        long leaseUntil = nowMillisEpoch + leaseDurationMillis;
        // Source-scoped claim: only this gateway's own hop1 records (sourceServiceId == leaseOwner)
        // are claimable, so the dispatcher never claims another role's row on the shared outbox.
        // Defect A: the prior claim-foreign-then-releaseLease branch is gone — there is no foreign
        // row to release, hence no stranded residue (lease_owner stamped on a relay's row) and no
        // cross-role shift chain. Multi-instance gateways share this sourceServiceId and compete
        // for the scoped rows via the SKIP-LOCKED claim.
        List<ForwardingOutboxRecord> claimed =
                claimPort.claimDue(new ClaimDueRequest(tenant, nowMillisEpoch, batchSize,
                        leaseOwner, leaseUntil, leaseOwner));
        int produced = 0;
        for (ForwardingOutboxRecord r : claimed) {
            BrokerProduceOutcome outcome = producer.produce(r, nowMillisEpoch);
            if (outcome.outcome() == BrokerProduceOutcome.Outcome.ACCEPTED) {
                outbox.markAcked(r.messageId(), tenant, leaseOwner);
                produced++;
            } else {
                ForwardingFailureCode code = outcome.failureCode() != null
                        ? outcome.failureCode() : ForwardingFailureCode.RECEIVER_UNAVAILABLE;
                outbox.scheduleRetry(r.messageId(), tenant, leaseOwner, code, nowMillisEpoch + pollIntervalMillis);
            }
        }
        return produced;
    }

    @Override
    public void start() {
        running = true;
        scheduler = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = java.util.concurrent.Executors.defaultThreadFactory().newThread(r);
            t.setName("gateway-outbox-dispatcher");
            t.setDaemon(true);
            t.setUncaughtExceptionHandler((thread, ex) ->
                    log.warn("outbox dispatch tick uncaught", ex));
            return t;
        });
        scheduler.scheduleAtFixedRate(
                () -> dispatchOnce(System.currentTimeMillis()), 0, pollIntervalMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void stop() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        // after BrokerProjectionFeed (MIN_VALUE + 100) so consume-subscribe is ready first
        return Integer.MIN_VALUE + 200;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }
}
