/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.controller;

import com.openjiuwen.rdc.model.AgentCardDto;
import com.openjiuwen.rdc.model.InvalidDiscoveryQueryException;
import com.openjiuwen.rdc.model.RouteResolution;
import com.openjiuwen.rdc.repository.AgentRegistryRepository;
import com.openjiuwen.rdc.service.AgentDiscoveryService;
import com.openjiuwen.rdc.service.PgMvpDiscoveryServiceImpl;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Runtime instance route query HTTP entry points
 * ({@code /api/registry/instances/...}, {@code /route-handle/resolve}).
 *
 * <p>Logical Agent Card discovery remains on {@link MvpRegistryController}
 * ({@code POST /api/registry/discover}).
 *
 * @since 0.1.0 (2026)
 */
@RestController
@RequestMapping("/api/registry")
public class InstanceRouteController {
    private static final String TRACE_PARENT_HEADER = "traceparent";
    private static final String X_TRACE_ID_HEADER = "X-Trace-Id";

    private final AgentDiscoveryService discovery;
    private final AgentRegistryRepository repository;

    public InstanceRouteController(AgentDiscoveryService discovery,
                                   AgentRegistryRepository repository) {
        this.discovery = discovery;
        this.repository = repository;
    }

    /**
     * listInstances.
     *
     * @param tenantId tenantId
     * @param agentId agentId
     * @param false false
     * @return result
     * @since 0.1.0
     * @param "contractVersion" "contractVersion"
     */
    @GetMapping("/instances/{tenantId}/{agentId}")
    public List<AgentCardDto> listInstances(
            @PathVariable String tenantId,
            @PathVariable String agentId,
            @RequestParam(value = "contractVersion", required = false) String contractVersion) {
        requireNonBlank("tenantId and agentId are required", tenantId, agentId);
        return discovery.searchInstancesByAgentId(tenantId, agentId, contractVersion);
    }

    /**
     * listInstancesByService.
     *
     * @param tenantId tenantId
     * @param serviceId serviceId
     * @param false false
     * @return result
     * @since 0.1.0
     * @param "contractVersion" "contractVersion"
     */
    @GetMapping("/instances/by-service/{tenantId}/{serviceId}")
    public List<AgentCardDto> listInstancesByService(
            @PathVariable String tenantId,
            @PathVariable String serviceId,
            @RequestParam(value = "contractVersion", required = false) String contractVersion) {
        requireNonBlank("tenantId and serviceId are required", tenantId, serviceId);
        return discovery.searchByServiceId(tenantId, serviceId, contractVersion);
    }

    /**
     * listInstancesByCapability.
     *
     * @param tenantId tenantId
     * @param capability capability
     * @param false false
     * @return result
     * @since 0.1.0
     * @param "contractVersion" "contractVersion"
     */
    @GetMapping("/instances/by-capability/{tenantId}/{capability}")
    public List<AgentCardDto> listInstancesByCapability(
            @PathVariable String tenantId,
            @PathVariable String capability,
            @RequestParam(value = "contractVersion", required = false) String contractVersion) {
        requireNonBlank("tenantId and capability are required", tenantId, capability);
        return discovery.searchByCapability(tenantId, capability, contractVersion);
    }

    /**
     * resolveRouteHandle.
     *
     * @param request request
     * @param TRACE_PARENT_HEADER TRACE_PARENT_HEADER
     * @param false false
     * @return result
     * @since 0.1.0
     */
    @PostMapping("/route-handle/resolve")
    public RouteResolution resolveRouteHandle(
            @RequestBody ResolveRequest request,
            @RequestHeader(value = TRACE_PARENT_HEADER, required = false) String traceparent,
            @RequestHeader(value = X_TRACE_ID_HEADER, required = false) String xTraceId,
            @RequestHeader(value = "X-Caller-Ref", required = false) String callerRefHeader) {
        String traceId = resolveTraceId(traceparent, xTraceId);
        if (request == null
                || request.routeHandle() == null || request.routeHandle().isBlank()
                || request.tenantId() == null || request.tenantId().isBlank()) {
            throw new InvalidDiscoveryQueryException("INVALID_QUERY", "routeHandle and tenantId are required", traceId);
        }
        String callerRef = callerRefHeader != null && !callerRefHeader.isBlank()
                ? callerRefHeader.trim() : "http-client";
        Instant deadline = request.context() != null && request.context().deadline() != null
                ? request.context().deadline()
                : Instant.now().plusSeconds(30);
        if (discovery instanceof PgMvpDiscoveryServiceImpl pgDiscovery) {
            return pgDiscovery.resolveRouteHandle(
                    request.routeHandle(), request.tenantId(), callerRef, traceId, deadline);
        }
        return discovery.resolveRouteHandle(request.routeHandle(), request.tenantId());
    }

    /**
     * deregisterSingleInstance.
     *
     * @param pathVars pathVars
     * @since 0.1.0
     */
    @DeleteMapping("/deregister/{tenantId}/{agentId}/{serviceId}/{instanceId}")
    public void deregisterSingleInstance(@PathVariable Map<String, String> pathVars) {
        String tenantId = pathVars.get("tenantId");
        String agentId = pathVars.get("agentId");
        String serviceId = pathVars.get("serviceId");
        String instanceId = pathVars.get("instanceId");
        requireNonBlank("tenantId, agentId, serviceId and instanceId are required",
                tenantId, agentId, serviceId, instanceId);
        repository.delete(tenantId, agentId, serviceId, instanceId);
    }

    /**
     * ResolveRequest.
     *
     * @param routeHandle routeHandle
     * @param tenantId tenantId
     * @param context context
     * @return result
     * @since 0.1.0
     */
    public record ResolveRequest(
            String routeHandle,
            String tenantId,
            ContextRequest context
    ) {
    }

    /**
     * ContextRequest.
     *
     * @param tenantId tenantId
     * @param callerRef callerRef
     * @param requestId requestId
     * @param deadline deadline
     * @return result
     * @since 0.1.0
     */
    public record ContextRequest(
            String tenantId,
            String callerRef,
            String requestId,
            Instant deadline
    ) {

    }
    private static String resolveTraceId(String traceparent, String xTraceId) {
        if (traceparent != null && !traceparent.isBlank()) {
            String[] parts = traceparent.trim().split("-");
            if (parts.length >= 3 && !parts[2].isBlank()) {
                return parts[2];
            }
        }
        if (xTraceId != null && !xTraceId.isBlank()) {
            return xTraceId.trim();
        }
        return UUID.randomUUID().toString();
    }

    private static void requireNonBlank(String message, String... values) {
            for (String value : values) {
                if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(message);
            }
        }
    }
}
