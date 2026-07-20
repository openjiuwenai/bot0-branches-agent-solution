/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.service;

import com.openjiuwen.rdc.model.AgentCardDiscoveryQuery;
import com.openjiuwen.rdc.model.AgentCardDiscoveryResult;
import com.openjiuwen.rdc.model.AgentCardDto;
import com.openjiuwen.rdc.model.DiscoveryQuery;
import com.openjiuwen.rdc.model.DiscoveryResult;
import com.openjiuwen.rdc.model.RouteResolution;

import java.util.List;

/**
 * agent-bus owned runtime route index lookup entry (HD3-001).
 *
 * <p>FEAT-016: three runtime query dimensions with nullable {@code contractVersion}
 * filter; v2: 6-field route handles with {@code instanceId}.
 *
 * <p>Feat-015: structured {@link #discover(DiscoveryQuery)} for logical Agent Card
 * catalog discovery.
 *
 * @since 0.1.0 (2026)
 */
public interface AgentDiscoveryService {
    /**
     * List ONLINE/DEGRADED/DRAINING instances for {@code (tenantId, agentId)}.
     */

    List<AgentCardDto> searchInstancesByAgentId(String tenantId, String agentId, String contractVersion);

    /**
     * Convenience overload without contract version filter.
     */

    default List<AgentCardDto> searchInstancesByAgentId(String tenantId, String agentId) {
        return searchInstancesByAgentId(tenantId, agentId, null);
    }
    /**
     * List ONLINE/DEGRADED/DRAINING instances for {@code (tenantId, serviceId)}.
     */

    List<AgentCardDto> searchByServiceId(String tenantId, String serviceId, String contractVersion);

    /**
     * List ONLINE/DEGRADED/DRAINING instances declaring {@code capability}.
     */

    List<AgentCardDto> searchByCapability(String tenantId, String capability, String contractVersion);

    /**
     * Structured logical Agent Card discovery per Feat-015.
     */

    DiscoveryResult discover(DiscoveryQuery query);

    default AgentCardDiscoveryResult discoverAgentCards(AgentCardDiscoveryQuery query) {
        DiscoveryResult result = discover(query.toDiscoveryQuery());
        return AgentCardDiscoveryResult.from(result);
    }

    RouteResolution resolveRouteHandle(String routeHandle, String tenantId);
}
