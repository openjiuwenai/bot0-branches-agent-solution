/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.routing;

import java.util.List;

/**
 * Gateway's outbound port to the Registry &amp; Discovery Center (FEAT-011 L2
 * §4.5.2 — edge I-02 / resolve). The 730 default implementation calls the RDC
 * HTTP API; tests stub this port. The gateway MUST NOT depend on RDC as an
 * in-process artifact (decision D2 / L2 §1.6: independently deployable).
 *
 * <p>Contract mirrors FEAT-016 {@code AgentDiscoveryService} over HTTP:
 * <ul>
 *   <li>{@code GET /api/registry/instances/{tenantId}/{agentId}}</li>
 *   <li>{@code POST /api/registry/route-handle/resolve}</li>
 * </ul>
 *
 * @since 0.1.0
 */
public interface RdcRouteClient {
    /**
     * List routable instances for a known target agent. RDC returns them sorted
     * (weight DESC, last_heartbeat DESC) and already filtered to
     * discoverable health; the gateway picks the first and does not re-filter
     * (FEAT-011 L2 §4.4 P1, decision D3). Empty list when none / invisible.
     *
     * @param tenantId authoritative tenant (G2)
     * @param agentId effective logical target agent
     * @return sorted candidates; empty (never {@code null}) when no route
     */
    List<AgentCardRoute> searchInstancesByAgentId(String tenantId, String agentId);

    /**
     * Resolve an opaque routeHandle to a forward-layer endpoint. Called only by
     * the forward layer; the resolved physical endpoint is never returned to the
     * client / agent (FEAT-011 L2 §0.2 OUT-8).
     *
     * @param routeHandle opaque handle from a candidate
     * @param tenantId authoritative tenant (must match the handle's tenant)
     * @return resolved forward-layer endpoint
     */
    ResolvedRoute resolveRouteHandle(String routeHandle, String tenantId);
}
