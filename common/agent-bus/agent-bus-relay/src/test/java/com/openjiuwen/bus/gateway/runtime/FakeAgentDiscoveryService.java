/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.bus.gateway.runtime;

import com.openjiuwen.rdc.model.AgentCardDto;
import com.openjiuwen.rdc.model.RouteResolution;
import com.openjiuwen.rdc.service.AgentDiscoveryService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * In-memory test fake of {@link AgentDiscoveryService} — FEAT-013/014
 * registry-discovery-center integration (§7.5 Option A). NON-PRODUCTION.
 *
 * <p>Stands in for the real registry in the {@link GatewayRuntimeService} test
 * path so the gateway can demo the discovery → opaque-routeHandle → envelope
 * assembly without a live PostgreSQL registry. Cards are registered with the
 * dimensions the real registry indexes on ({@code agentId} / {@code serviceId} /
 * {@code capabilities}) — none of which are exposed on {@link AgentCardDto}
 * itself (the DTO carries only ICD routing fields), so the fake tracks them
 * out-of-band.
 *
 * <p>Query semantics mirror the real service: empty list on no match (never
 * null, never throws on not-found — anti-enumeration); results sorted
 * {@code weight DESC} (the real service also sorts by {@code last_heartbeat
 * DESC}, which the fake has no notion of). {@code contractVersion} is applied as
 * an exact-match filter when non-null.
 *
 * <p>{@link #resolveRouteHandle} returns a deterministic stub: the T1 A2A SSE
 * bridge (the only caller of {@code resolveRouteHandle}) is gateway
 * responsibility and is deferred out of agent-bus this batch, so the T4 pub/sub
 * path never calls it. The stub keeps the fake contract-complete.
 *
 * <p>Single-tenant: the {@code tenantId} argument is accepted for signature
 * compatibility but not filtered (test fixtures pin one tenant).
 *
 * @since 0.1.0
 */
// non-production — test fixture only; stands in for the real registry-discovery-center
public final class FakeAgentDiscoveryService implements AgentDiscoveryService {
    private final List<RegisteredCard> cards = new ArrayList<>();

    /**
     * Register a fully-built card with its discovery dimensions.
     *
     * @param agentId      the agentId the card is registered under (searchInstancesByAgentId dimension)
     * @param capabilities capability tags the card advertises (searchByCapability dimension)
     * @param card         the agent card DTO (carries serviceId / routeHandle / health / weight / ...)
     * @return this fake (for fluent registration)
     */
    public FakeAgentDiscoveryService register(String agentId, Set<String> capabilities, AgentCardDto card) {
        cards.add(new RegisteredCard(
                requireNonBlank(agentId, "agentId"),
                Set.copyOf(Objects.requireNonNull(capabilities, "capabilities is required")),
                Objects.requireNonNull(card, "card is required")));
        return this;
    }

    /**
     * Convenience: register a card from its scalar routing fields (builds the {@link AgentCardDto}
     * with test-default {@code weight=100}/{@code health=ONLINE}/{@code contractVersion=v1}).
     *
     * @param agentId     the agentId the card is registered under
     * @param serviceId   the logical service identifier (searchByServiceId dimension)
     * @param routeHandle the opaque route handle the gateway carries end-to-end
     * @param capability  a single capability tag (searchByCapability dimension)
     * @return this fake (for fluent registration)
     */
    public FakeAgentDiscoveryService register(String agentId, String serviceId, String routeHandle,
                                              String capability) {
        AgentCardDto card = AgentCardDto.builder()
                .serviceId(requireNonBlank(serviceId, "serviceId"))
                .routeHandle(requireNonBlank(routeHandle, "routeHandle"))
                .health("ONLINE")
                .contractVersion("v1")
                .capabilityVersion("v1")
                .weight(100)
                .region("local")
                .maxConcurrency(100)
                .build();
        return register(agentId, Set.of(requireNonBlank(capability, "capability")), card);
    }

    @Override
    public List<AgentCardDto> searchInstancesByAgentId(String tenantId, String agentId, String contractVersion) {
        requireNonBlank(agentId, "agentId");
        return filter(contractVersion, c -> Objects.equals(c.agentId, agentId));
    }

    @Override
    public List<AgentCardDto> searchByServiceId(String tenantId, String serviceId, String contractVersion) {
        requireNonBlank(serviceId, "serviceId");
        return filter(contractVersion, c -> Objects.equals(c.card.getServiceId(), serviceId));
    }

    @Override
    public List<AgentCardDto> searchByCapability(String tenantId, String capability, String contractVersion) {
        requireNonBlank(capability, "capability");
        return filter(contractVersion, c -> c.capabilities.contains(capability));
    }

    @Override
    public RouteResolution resolveRouteHandle(String routeHandle, String tenantId) {
        // T1 SSE bridge is gateway responsibility (deferred out of agent-bus this batch). Returns a
        // deterministic stub; the T4 pub/sub path never calls this (the opaque handle rides the envelope).
        requireNonBlank(routeHandle, "routeHandle");
        return new RouteResolution("instance-" + Math.abs(routeHandle.hashCode()),
                "http://stub-endpoint/" + routeHandle, "stub", "v1");
    }

    private List<AgentCardDto> filter(String contractVersion, java.util.function.Predicate<RegisteredCard> matches) {
        List<AgentCardDto> out = new ArrayList<>();
        for (RegisteredCard c : cards) {
            if (!matches.test(c)) {
                continue;
            }
            if (contractVersion != null && !contractVersion.equals(c.card.getContractVersion())) {
                continue;
            }
            out.add(c.card);
        }
        // mirror the real service sort: weight DESC (last_heartbeat DESC is not modelled here)
        out.sort(Comparator.comparingInt(AgentCardDto::getWeight).reversed());
        return List.copyOf(out);
    }

    private record RegisteredCard(String agentId, Set<String> capabilities, AgentCardDto card) {
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name + " is required");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
