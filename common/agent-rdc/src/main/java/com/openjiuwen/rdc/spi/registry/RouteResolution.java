package com.openjiuwen.rdc.spi.registry;

/**
 * Resolved view of an opaque {@code routeHandle} — returned by
 * {@link AgentDiscoveryService#resolveRouteHandle(String, String)} to the
 * forwarding delivery infrastructure only.
 *
 * <p>Authority: ADR-0160 decision 5 + HD3-006 (opaque route handle). The
 * Orchestrator business logic never sees this record — only the forwarding
 * layer does, so the route handle encoding format can evolve
 * ({@code v2:} prefix in FEAT-016) without breaking cross-module consumers.
 *
 * <p>FEAT-016 阶段一: added {@code instanceId} as the <em>first</em> field.
 * The instanceId is decoded from the route handle (not read from the DB) and
 * is a forwarding-layer concern only — it is never visible to the agent or
 * the client. The forwarding layer uses it to address a specific instance
 * among N replicas of the same {@code serviceId}.
 *
 * @param instanceId      host-port instance identifier decoded from the handle
 *                        (forwarding-layer only; never visible to agent/client)
 * @param endpointUrl     physical endpoint the forwarding layer should deliver to
 * @param routeKey        logical routing key carried inside the opaque handle
 * @param contractVersion contract version the registered agent pinned at
 *                        registration time; the forwarding layer forwards it
 *                        as-is to the agent
 */
public record RouteResolution(String instanceId, String endpointUrl,
                              String routeKey, String contractVersion) {
}
