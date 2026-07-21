/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.bus.forwarding.runtime.transport;

import com.openjiuwen.bus.forwarding.spi.ForwardingRouteHandle;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Configuration-map {@link ForwardingEndpointResolver} — Stage 15 T1 PoC fixture.
 *
 * <p>Maps {@link ForwardingRouteHandle#value()} → endpoint URL. In the PoC the
 * map is populated with the MockWebServer URL; the next-batch gateway module
 * swaps this for a {@code registry-discovery-center}-backed resolver that wraps
 * {@code AgentDiscoveryService.resolveRouteHandle}. The handle's {@code value}
 * is treated as an opaque lookup key — the resolver still makes no assumption
 * about its internal encoding; it is the registry's job (real or mocked) to map
 * a handle to a URL.
 *
 * <p><b>T1 only — not for the T4 pub/sub path.</b> This resolver resolves a
 * physical <em>endpoint URL</em> for the T1 A2A HTTP/SSE push path (gateway
 * responsibility). The T4 broker pub/sub path derives its <em>topic</em> via
 * {@link BrokerTopicResolver} (event-type-driven), not via this port. Do not
 * wire this into broker consumer / relay construction.
 *
 * <p>Immutable; thread-safe via an unmodifiable copy of the supplied map.
 *
 * <p>Authority: Stage 15 PoC — A2A transport adapter
 * ({@code docs/architecture/l0/10-governance/delivery-projections/agent-bus-stage14-review-and-stage15-plan.md});
 * {@code common/agent-bus/registry-discovery-center-integration-plan.md} §7.3.
 *
 * @since 0.1.0
 */
@Deprecated
public final class MapEndpointResolver implements ForwardingEndpointResolver {
    private final Map<String, String> endpoints;

    /**
     * Construct a resolver backed by an immutable copy of the supplied endpoint map.
     *
     * @param endpoints map of {@code routeHandle.value()} → endpoint URL; copied
     *                  unmodifiable. Values may be null (mapped to
     *                  {@link Optional#empty()}).
     */
    public MapEndpointResolver(Map<String, String> endpoints) {
        Objects.requireNonNull(endpoints, "endpoints is required");
        this.endpoints = Map.copyOf(endpoints);
    }

    @Override
    public Optional<String> resolve(ForwardingRouteHandle handle) {
        Objects.requireNonNull(handle, "handle is required");
        return Optional.ofNullable(endpoints.get(handle.value()));
    }
}
