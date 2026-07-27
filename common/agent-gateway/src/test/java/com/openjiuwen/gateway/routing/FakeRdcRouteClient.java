/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.routing;

import java.util.List;

/**
 * Test double for {@link RdcRouteClient} used by module-integration tests
 * (S2 / S5). Configurable candidates + resolve endpoint (or resolve failure),
 * and records the last queried tenant/agent for assertions.
 *
 * @since 0.1.0
 */
public class FakeRdcRouteClient implements RdcRouteClient {
    private List<AgentCardRoute> candidates = List.of();
    private ResolvedRoute resolved = null;
    private String lastTenantId;
    private String lastAgentId;

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

    /**
     * Return the tenant id recorded from the last search call.
     *
     * @return the tenant id of the last search
     */
    public String lastTenantId() {
        return lastTenantId;
    }

    /**
     * Return the agent id recorded from the last search call.
     *
     * @return the agent id of the last search
     */
    public String lastAgentId() {
        return lastAgentId;
    }

    /**
     * Clear recorded search arguments (so tests asserting "no search" start clean).
     */
    public void reset() {
        this.lastTenantId = null;
        this.lastAgentId = null;
    }

    @Override
    public List<AgentCardRoute> searchInstancesByAgentId(String tenantId, String agentId) {
        this.lastTenantId = tenantId;
        this.lastAgentId = agentId;
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
