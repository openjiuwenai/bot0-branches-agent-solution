package com.openjiuwen.rdc.spi.registry;

/**
 * Resolved view of an opaque {@code routeHandle} — returned by
 * {@link AgentDiscoveryService#resolveRouteHandle(String, String)} to the
 * forwarding delivery infrastructure only.
 *
 * <p>Authority: ADR-0160 decision 5 + HD3-006 (opaque route handle). The
 * Orchestrator business logic never sees this record — only the forwarding
 * layer does, so the route handle encoding format can evolve
 * ({@code v1:} prefix in phase 2) without breaking cross-module consumers.
 *
 * @param endpointUrl     physical endpoint the forwarding layer should deliver to
 * @param routeKey        logical routing key carried inside the opaque handle
 * @param contractVersion contract version the registered agent pinned at
 *                        registration time; the forwarding layer forwards it
 *                        as-is to the agent
 */
public record RouteResolution(String endpointUrl, String routeKey, String contractVersion) {
}
