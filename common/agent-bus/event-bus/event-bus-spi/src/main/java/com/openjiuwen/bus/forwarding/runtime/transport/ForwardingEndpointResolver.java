/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.bus.forwarding.runtime.transport;

import com.openjiuwen.bus.forwarding.spi.ForwardingRouteHandle;

import java.util.Optional;

/**
 * Resolves an opaque {@link ForwardingRouteHandle} to a physical transport
 * endpoint URL — the <b>T1</b> A2A HTTP/SSE push port (Stage 15, A2A PoC).
 *
 * <p><b>Repositioned by FEAT-013/014 registry-discovery-center integration
 * (Option B).</b> This port is the T1 endpoint-resolution seam: a gateway /
 * host unwraps an opaque registry handle into a concrete physical endpoint URL
 * to establish an A2A HTTP/SSE connection (the T1 SSE bridge — gateway
 * responsibility, out of agent-bus). The agent-bus <b>T4 pub/sub main path does
 * NOT call this interface</b>: T4 topic derivation is
 * {@link BrokerTopicResolver}'s responsibility (event-type-driven, decoupled
 * from the opaque handle), and the opaque {@code routeHandle} rides the
 * envelope end-to-end as a passthrough field on the T4 path. The port is kept
 * here so the gateway (next batch) and the T1 PoC fixture can reuse it without
 * agent-bus redefining it.
 *
 * <p><b>HD4 preserved.</b> {@link ForwardingRouteHandle} stays opaque on the
 * outbox record and in the {@code ForwardingDeliveryPort} contract — the
 * dispatcher / delivery binding never unwraps it. This injected port is the
 * sanctioned seam where an opaque handle becomes a concrete URL: the binding
 * calls {@link #resolve} and acts on the result, it does not read
 * {@code routeHandle.value()} itself. Injected exactly like
 * {@code ForwardingRetryPolicy} / {@code DispatchLeasePolicy} / {@code EpochClock}.
 *
 * <p>An empty result means the handle has no resolvable endpoint → the delivery
 * binding returns {@code dlq(ROUTE_NOT_FOUND)} (non-retryable; a route that
 * cannot be resolved is a configuration / registry problem, not a transient
 * receiver failure).
 *
 * <p>Production implementation is the Stage 3 registry / discovery runtime
 * (deferred — the next-batch gateway module wraps
 * {@code AgentDiscoveryService.resolveRouteHandle} behind this port); the
 * Stage 15 PoC ships {@link MapEndpointResolver} (configuration map). This port
 * lives outside {@code transport.a2a} so it carries no A2A SDK coupling — a
 * future non-A2A binding reuses it.
 *
 * <p>Plain JDK type — no Spring / JDBC / broker dependency (forwarding purity,
 * decision §6.1). The ArchUnit purity harness does not confine it.
 *
 * <p>Authority: {@code docs/architecture/l0/10-governance/review-packets/agent-bus-forwarding-runtime-decision.md}
 * (Stage 15 — A2A transport adapter permit, §6.1 item 4 lifted to scaffold level);
 * {@code common/agent-bus/registry-discovery-center-integration-plan.md} §5.3 / §7.2.
 *
 * @since 0.1.0
 */
public interface ForwardingEndpointResolver {
    /**
     * Resolve an opaque route handle to an endpoint URL, or empty if the handle
     * is not resolvable (the delivery binding maps this to ROUTE_NOT_FOUND).
     *
     * @param handle the opaque route handle (non-null)
     * @return the endpoint URL, or {@link Optional#empty()} if unresolvable
     */
    Optional<String> resolve(ForwardingRouteHandle handle);
}
