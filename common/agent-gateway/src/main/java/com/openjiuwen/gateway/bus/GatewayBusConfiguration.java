/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.bus;

import com.openjiuwen.bus.forwarding.spi.ForwardingOutboxClaimPort;
import com.openjiuwen.bus.forwarding.spi.ForwardingOutboxPort;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerForwardingConsumerPort;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerForwardingProducerPort;
import com.openjiuwen.gateway.bus.control.BrokerProjectionFeed;
import com.openjiuwen.gateway.bus.control.BusControlForwarder;
import com.openjiuwen.gateway.bus.control.EnvelopeBuilder;
import com.openjiuwen.gateway.bus.control.PayloadStore;
import com.openjiuwen.gateway.bus.control.ProjectionFeed;
import com.openjiuwen.gateway.governance.idempotency.IdempotencyRule;
import com.openjiuwen.gateway.routing.RdcRouteClient;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the FEAT-012 BUS path beans when {@code gateway.path-mode=bus} (deployment-level; the
 * facade consults {@link com.openjiuwen.gateway.path.PathSelector}). The outbox +
 * response-consumer ports come from the agent-bus SDK role autoconfigs on the runtime
 * classpath (the launcher module puts {@code agent-bus-sdk} there); in tests, Fakes stand in.
 *
 * <p>SPI-only: depends on {@code agent-bus-spi} types ({@link ForwardingOutboxPort},
 * {@link BrokerForwardingConsumerPort}) — never on {@code agent-bus-sdk} (which is a
 * {@code forbidden_dependencies} of this module by design; it arrives at deploy time).
 * Ports are injected by type (single caller-role bean in the gateway process; no @Qualifier).
 *
 * @since 2026-07-27
 */
@Configuration
@ConditionalOnProperty(prefix = "gateway", name = "path-mode", havingValue = "bus")
public class GatewayBusConfiguration {

    /**
     * I-04 outbound: build envelope + enqueue (FEAT-012 §4.4 P3).
     *
     * @param envelopeBuilder envelope factory
     * @param payloadStore body stash for payload refs
     * @param outboxPort durable outbox port (SDK {@code forwardingOutbox})
     * @return the bus control forwarder
     */
    @Bean
    public BusControlForwarder busControlForwarder(EnvelopeBuilder envelopeBuilder,
                                                    PayloadStore payloadStore,
                                                    ForwardingOutboxPort outboxPort) {
        return new BusControlForwarder(envelopeBuilder, payloadStore, outboxPort);
    }

    /**
     * Inbound projection feed backed by the SDK {@code responseConsumer}; subscribes to the
     * INVOCATION_* response family at startup (FEAT-012 §4.5 I-04 + §4.12.3).
     *
     * @param consumer SDK response-consumer port (suffix {@code resp_out})
     * @param gatewayServiceId gateway service identity (subscribe group + target filter)
     * @return the broker-backed projection feed
     */
    @Bean
    public BrokerProjectionFeed brokerProjectionFeed(BrokerForwardingConsumerPort consumer,
            @Value("${agent-bus.gateway-service-id:gateway}") String gatewayServiceId) {
        return new BrokerProjectionFeed(consumer, gatewayServiceId);
    }

    /**
     * Caller-side outbox pump (FEAT-012 §4.4): claims gateway outbox records and produces them to
     * {@code ascend_bus_*_req} via the SDK {@code requestProducer}. Without it the relay never sees a
     * request → the gateway times out UNKNOWN (req stuck PENDING in {@code agent_bus_forwarding_outbox}).
     *
     * @param claimPort          outbox claim / lease port (SDK {@code forwardingOutbox})
     * @param producer           SDK {@code requestProducer} (suffix {@code req})
     * @param outbox             outbox mutation port (ack / retry)
     * @param tenant             tenant scope of records to claim
     * @param gatewayServiceId   this gateway's service id (claim owner + source-scope filter)
     * @param leaseDurationMillis lease TTL for a claimed record
     * @param pollIntervalMillis  dispatch tick interval (and retry backoff)
     * @return the caller outbox dispatcher
     */
    @Bean
    public GatewayOutboxDispatcher gatewayOutboxDispatcher(ForwardingOutboxClaimPort claimPort,
            BrokerForwardingProducerPort producer, ForwardingOutboxPort outbox,
            @Value("${agent-bus.tenant:tenant-a}") String tenant,
            @Value("${agent-bus.gateway-service-id:gateway}") String gatewayServiceId,
            @Value("${agent-bus.lease-duration-ms:60000}") long leaseDurationMillis,
            @Value("${gateway.bus.dispatch-interval-ms:1000}") long pollIntervalMillis) {
        return new GatewayOutboxDispatcher(claimPort, producer, outbox, tenant, gatewayServiceId,
                leaseDurationMillis, pollIntervalMillis, 10);
    }

    /**
     * Orchestrates the BUS sync create path (FEAT-012 §4): search → enqueue → wait → fold.
     *
     * @param rdc route discovery client
     * @param control I-04 outbound forwarder
     * @param projectionFeed inbound projection poll port
     * @param g4 idempotency rule (G4)
     * @param sourceServiceId gateway service identity for envelope audit
     * @param acceptWindowMillis accept-phase timeout
     * @param responseWindowMillis response-phase timeout after accept
     * @return the bus forwarder
     */
    @Bean
    public BusForwarder busForwarder(RdcRouteClient rdc, BusControlForwarder control,
                                     ProjectionFeed projectionFeed, IdempotencyRule g4,
            @Value("${agent-bus.gateway-service-id:gateway}") String sourceServiceId,
            @Value("${gateway.bus.accept-window-ms:30000}") long acceptWindowMillis,
            @Value("${gateway.bus.response-window-ms:60000}") long responseWindowMillis) {
        return new BusForwarder(rdc, control, projectionFeed, g4, sourceServiceId,
                acceptWindowMillis, responseWindowMillis);
    }
}
