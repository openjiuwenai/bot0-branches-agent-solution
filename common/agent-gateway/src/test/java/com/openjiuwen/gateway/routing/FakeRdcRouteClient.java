/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.routing;

import java.util.List;

/**
 * Test double for {@link RdcRouteClient} used by module-integration tests
 * (S2 / S5). Configurable: the candidates a search returns and the endpoint a
 * resolve returns (or a resolve failure). No network.
 *
 * @since 0.1.0
 */
public class FakeRdcRouteClient implements RdcRouteClient {
    private List<AgentCardRoute> candidates = List.of();
    private ResolvedRoute resolved = null;

    /**
     * Configure the candidates returned by the next search.
     *
     * @param candidates candidate list
     */
    public void setCandidates(List<AgentCardRoute> candidates) {
        this.candidates = candidates;
    }

    /**
     * Configure the endpoint a resolve returns; {@code null} means resolve fails.
     *
     * @param resolved resolved endpoint, or {@code null} to fail resolve
     */
    public void setResolved(ResolvedRoute resolved) {
        this.resolved = resolved;
    }

    @Override
    public List<AgentCardRoute> searchInstancesByAgentId(String tenantId, String agentId) {
        return candidates;
    }

    @Override
    public ResolvedRoute resolveRouteHandle(String routeHandle, String tenantId) {
        if (resolved == null) {
            throw new RouteResolutionException("fake resolve not configured");
        }
        return resolved;
    }
}
