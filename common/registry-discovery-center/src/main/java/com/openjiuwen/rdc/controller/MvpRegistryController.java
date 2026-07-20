/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.controller;

import com.openjiuwen.rdc.config.RegistryObservabilityConfig;
import com.openjiuwen.rdc.config.RegistryOpContext;
import com.openjiuwen.rdc.repository.AgentRegistryRepository;
import com.openjiuwen.rdc.model.AgentCardDto;
import com.openjiuwen.rdc.service.AgentDiscoveryService;
import com.openjiuwen.rdc.model.AgentRegistryEntry;
import com.openjiuwen.rdc.model.InstanceIdCodec;
import com.openjiuwen.rdc.model.RouteResolution;
import com.openjiuwen.rdc.model.ServiceIdCodec;
import com.openjiuwen.rdc.model.TenantIsolationViolationException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * HTTP entry point for the agent registry MVP (ADR-0160 decisions 4 / 6 / 7,
 * revised by REQ-2026-006, then FEAT-016).
 *
 * <p>Exposes:
 * <ul>
 *   <li>{@code POST /api/registry/register} — upsert an {@link AgentRegistryEntry}</li>
 *   <li>{@code DELETE /api/registry/deregister/{tenantId}/{agentId}} — delete
 *       all instances for the pair (REQ-2026-006 semantic generalization)</li>
 *   <li>{@code DELETE /api/registry/deregister/{tenantId}/{agentId}/{serviceId}}
 *       — delete all instances under the triple (FEAT-016: 4-field PK means
 *       this now deletes every concrete instance sharing the triple)</li>
 *   <li>{@code DELETE /api/registry/deregister/{tenantId}/{agentId}/{serviceId}/{instanceId}}
 *       — delete a single concrete instance (FEAT-016 new: rolling deploy of
 *       one replica must not evict other replicas of the same serviceId)</li>
 *   <li>{@code GET /api/registry/instances/{tenantId}/{agentId}} — list all
 *       ONLINE/DEGRADED/DRAINING instances for the pair; optional
 *       {@code ?contractVersion=} query param filters by contract version
 *       (FEAT-016: DRAINING now included; contractVersion filter added)</li>
 *   <li>{@code GET /api/registry/instances/by-service/{tenantId}/{serviceId}}
 *       — list all ONLINE/DEGRADED/DRAINING instances for the logical service
 *       identifier; optional {@code ?contractVersion=} filter (FEAT-016 new)</li>
 *   <li>{@code GET /api/registry/instances/by-capability/{tenantId}/{capability}}
 *       — list all ONLINE/DEGRADED/DRAINING instances declaring the capability
 *       in their {@code capabilities[]} array; optional
 *       {@code ?contractVersion=} filter (FEAT-016 new)</li>
 *   <li>{@code POST /api/registry/route-handle/resolve} — resolve an opaque
 *       routeHandle to a physical endpoint (REQ-2026-006 new)</li>
 * </ul>
 *
 * <p>FEAT-016 register body semantics:
 * <ul>
 *   <li>{@code serviceId} — caller-overridable (public setter on
 *       {@link AgentRegistryEntry}). When the caller provides a non-blank
 *       value the controller preserves it; when the caller omits it the
 *       controller derives a default from {@code endpointUrl} host only via
 *       {@link ServiceIdCodec#applyTo(AgentRegistryEntry)}. Multiple agents /
 *       instances can share the same {@code serviceId} to form a logical
 *       service group.</li>
 *   <li>{@code instanceId} — always server-derived from {@code endpointUrl}
 *       (host-port) via {@link InstanceIdCodec#applyTo(AgentRegistryEntry)};
 *       the entry's setter is package-private so HTTP callers cannot forge
 *       it. {@code applyTo} overwrites any caller-injected value — the
 *       server-derived {@code instanceId} is the final value that gets
 *       upserted. The {@code @JsonIgnoreProperties(ignoreUnknown=true)}
 *       defense-in-depth on the {@code @RequestBody} side is handled by
 *       Jackson ignoring unknown JSON fields (the POJO's setter is
 *       package-private, so Jackson cannot bind a caller-supplied
 *       {@code instanceId} field anyway).</li>
 *   <li>{@code capabilities} — passes through verbatim. The caller may
 *       provide a {@code List<String>} of capability tags; the repository
 *       persists them into the {@code VARCHAR(64)[]} column. No derivation
 *       or normalization is applied at the controller boundary.</li>
 * </ul>
 *
 * <p>HD3-006 (discovery 不暴露 endpoint): the {@code GET /instances/*}
 * responses carry only opaque {@code routeHandle}s — no endpoint URL, no
 * routeKey. The {@code serviceId} field IS exposed in the DTO per L2 §2.3.2
 * (logical service identifier, caller-visible for grouping). The caller
 * resolves a handle via {@code POST /route-handle/resolve} to get the
 * physical endpoint.
 *
 * <p>Trace ID propagation (PR #389 review issue #8): the controller reads
 * inbound {@code traceparent} (W3C) / {@code X-Trace-Id} headers and uses
 * that as the audit/metrics trace ID. When no header is present, a fresh
 * UUID is generated.
 *
 * <p>Authority: ADR-0160 decisions 4 / 6 / 7 + HD3-001 / 002 / 003 / 006 +
 * REQ-2026-006 (multi-instance + 3 new endpoints) + FEAT-016 (4-field PK,
 * caller-overridable serviceId, always-derived instanceId, capabilities
 * column, 3 query dimensions with contractVersion filter, DRAINING
 * visibility, single-instance delete).
 *
 * @since 2026-07-10
 */
@RestController
@RequestMapping("/api/registry")
public class MvpRegistryController {
    private static final String TRACE_PARENT_HEADER = "traceparent";
    private static final String X_TRACE_ID_HEADER = "X-Trace-Id";

    private final AgentRegistryRepository repository;
    private final AgentDiscoveryService discovery;
    private final RegistryObservabilityConfig observability;
    private final ObjectMapper objectMapper;

    /**
     * Construct the controller with its collaborators.
     *
     * @param repository   persistent registry store
     * @param discovery    discovery / route-handle resolution port
     * @param observability register / deregister metrics recorder
     * @param objectMapper  Jackson serializer for the a2aAgentCard JSON column
     */
    public MvpRegistryController(AgentRegistryRepository repository,
                                 AgentDiscoveryService discovery,
                                 RegistryObservabilityConfig observability,
                                 ObjectMapper objectMapper) {
        this.repository = repository;
        this.discovery = discovery;
        this.observability = observability;
        this.objectMapper = objectMapper;
    }

    /**
     * Upsert an {@link AgentRegistryEntry} into the registry. FEAT-016:
     * derives {@code instanceId} server-side, applies a default
     * {@code serviceId} when the caller omits it, persists the
     * {@code a2aAgentCard} as JSON, then records register observability.
     *
     * @param card       registry entry to upsert; must carry tenantId + agentId
     * @param traceparent optional W3C traceparent header; the third dash
     *                    segment is used as the audit trace id
     * @param xTraceId   optional fallback trace id header
     * @return HTTP 200 on success
     */
    @PostMapping("/register")
    public ResponseEntity<Void> register(
            @RequestBody AgentRegistryEntry card,
            @RequestHeader(value = TRACE_PARENT_HEADER, required = false) String traceparent,
            @RequestHeader(value = X_TRACE_ID_HEADER, required = false) String xTraceId) {
        if (card == null || !card.hasRegistryKey()) {
            throw new IllegalArgumentException(
                    "AgentRegistryEntry must carry tenantId + agentId (registry key)");
        }
        String traceId = resolveTraceId(traceparent, xTraceId);
        MDC.put("traceId", traceId);
        long start = System.nanoTime();
        String outcome = "error";
        try {
            applyDefaults(card);
            // FEAT-016: serviceId is caller-overridable. When the caller
            // provides a non-blank value the controller preserves it; when
            // the caller omits it the controller derives a default from
            // endpointUrl host only via ServiceIdCodec.applyTo.
            if (card.getServiceId() == null || card.getServiceId().isBlank()) {
                ServiceIdCodec.applyTo(card);
            }
            // FEAT-016: instanceId is ALWAYS server-derived from endpointUrl
            // (host-port). The entry's setter is package-private so HTTP
            // callers cannot forge it; InstanceIdCodec is the single
            // derivation bridge. applyTo overwrites any caller-injected
            // value (defense-in-depth — Jackson cannot bind the
            // package-private setter anyway).
            InstanceIdCodec.applyTo(card);
            // FEAT-016: capabilities pass through verbatim — the entry's
            // capabilities list (caller-optional) is read by repository.upsert
            // and persisted into the VARCHAR(64)[] column. No derivation or
            // normalization at this boundary.
            String a2aCardJson = serializeA2aCard(card.getA2aAgentCard()).orElse(null);
            repository.upsert(card, a2aCardJson);
            outcome = "success";
            return ResponseEntity.ok().build();
        } finally {
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            RegistryOpContext ctx = RegistryOpContext.of(traceId, card.getTenantId(), card.getAgentId())
                    .contractVersion(card.getContractVersion())
                    .capabilityVersion(card.getCapabilityVersion())
                    .health("ONLINE")
                    .build();
            observability.observeRegister(ctx, outcome, latencyMs);
            MDC.remove("traceId");
        }
    }

    /**
     * List all ONLINE/DEGRADED/DRAINING instances for the given
     * {@code (tenantId, agentId)} pair. REQ-2026-006 new endpoint. Returns
     * a JSON array of {@link AgentCardDto}, each carrying an opaque
     * {@code routeHandle}. HD3-006: no endpoint URL / routeKey in the
     * response — the caller resolves a handle via
     * {@code POST /route-handle/resolve}.
     *
     * <p>FEAT-016: DRAINING is now included in discovery results (was
     * excluded in REQ-2026-006); the caller sees DRAINING as a
     * limited-availability health state. Added nullable
     * {@code ?contractVersion=} query param — when present, filters results
     * by {@code AND contract_version = :contractVersion}; when absent, no
     * filter is applied.
     *
     * @param tenantId        registry key mandatory dimension
     * @param agentId         agent identifier within the tenant
     * @param contractVersion optional contract version filter; {@code null}
     *                        applies no filter
     * @param traceparent     optional W3C traceparent header
     * @param xTraceId        optional fallback trace id header
     * @return immutable list of matching {@link AgentCardDto}; empty on no match
     */
    @GetMapping("/instances/{tenantId}/{agentId}")
    public List<AgentCardDto> listInstances(
            @PathVariable String tenantId,
            @PathVariable String agentId,
            @RequestParam(value = "contractVersion", required = false) String contractVersion,
            @RequestHeader(value = TRACE_PARENT_HEADER, required = false) String traceparent,
            @RequestHeader(value = X_TRACE_ID_HEADER, required = false) String xTraceId) {
        if (tenantId == null || tenantId.isBlank()
                || agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("tenantId and agentId are required path variables");
        }
        return discovery.searchInstancesByAgentId(tenantId, agentId, contractVersion);
    }

    /**
     * List all ONLINE/DEGRADED/DRAINING instances for the given
     * {@code (tenantId, serviceId)} pair. FEAT-016 new endpoint — query by
     * logical service identifier (host only, caller-overridable). Returns a
     * JSON array of {@link AgentCardDto}, each carrying an opaque
     * {@code routeHandle}. Optional {@code ?contractVersion=} query param
     * filters by contract version.
     *
     * @param tenantId        registry key mandatory dimension
     * @param serviceId       logical service identifier (host only)
     * @param contractVersion optional contract version filter; {@code null}
     *                        applies no filter
     * @param traceparent     optional W3C traceparent header
     * @param xTraceId        optional fallback trace id header
     * @return immutable list of matching {@link AgentCardDto}; empty on no match
     */
    @GetMapping("/instances/by-service/{tenantId}/{serviceId}")
    public List<AgentCardDto> listInstancesByService(
            @PathVariable String tenantId,
            @PathVariable String serviceId,
            @RequestParam(value = "contractVersion", required = false) String contractVersion,
            @RequestHeader(value = TRACE_PARENT_HEADER, required = false) String traceparent,
            @RequestHeader(value = X_TRACE_ID_HEADER, required = false) String xTraceId) {
        if (tenantId == null || tenantId.isBlank()
                || serviceId == null || serviceId.isBlank()) {
            throw new IllegalArgumentException("tenantId and serviceId are required path variables");
        }
        return discovery.searchByServiceId(tenantId, serviceId, contractVersion);
    }

    /**
     * List all ONLINE/DEGRADED/DRAINING instances that declare the given
     * {@code capability} in their {@code capabilities[]} array. FEAT-016 new
     * endpoint — exact-match array-contains query replacing the free-text
     * search removed in REQ-2026-004. Returns a JSON array of
     * {@link AgentCardDto}, each carrying an opaque {@code routeHandle}.
     * Optional {@code ?contractVersion=} query param filters by contract
     * version.
     *
     * @param tenantId        registry key mandatory dimension
     * @param capability      capability tag to match (exact string)
     * @param contractVersion optional contract version filter; {@code null}
     *                        applies no filter
     * @param traceparent     optional W3C traceparent header
     * @param xTraceId        optional fallback trace id header
     * @return immutable list of matching {@link AgentCardDto}; empty on no match
     */
    @GetMapping("/instances/by-capability/{tenantId}/{capability}")
    public List<AgentCardDto> listInstancesByCapability(
            @PathVariable String tenantId,
            @PathVariable String capability,
            @RequestParam(value = "contractVersion", required = false) String contractVersion,
            @RequestHeader(value = TRACE_PARENT_HEADER, required = false) String traceparent,
            @RequestHeader(value = X_TRACE_ID_HEADER, required = false) String xTraceId) {
        if (tenantId == null || tenantId.isBlank()
                || capability == null || capability.isBlank()) {
            throw new IllegalArgumentException("tenantId and capability are required path variables");
        }
        return discovery.searchByCapability(tenantId, capability, contractVersion);
    }

    /**
     * Resolve an opaque {@code routeHandle} into a physical endpoint.
     * REQ-2026-006 new endpoint. The caller (Orchestrator forwarding layer)
     * passes a handle from {@code GET /instances} and the tenant id; the
     * response carries the endpoint URL, route key, and contract version.
     *
     * @param request     body carrying the route handle and caller tenant id
     * @param traceparent optional W3C traceparent header
     * @param xTraceId    optional fallback trace id header
     * @return resolved endpoint / route key / contract version
     */
    @PostMapping("/route-handle/resolve")
    public RouteResolution resolveRouteHandle(
            @RequestBody ResolveRequest request,
            @RequestHeader(value = TRACE_PARENT_HEADER, required = false) String traceparent,
            @RequestHeader(value = X_TRACE_ID_HEADER, required = false) String xTraceId) {
        if (request == null
                || request.routeHandle() == null || request.routeHandle().isBlank()
                || request.tenantId() == null || request.tenantId().isBlank()) {
            throw new IllegalArgumentException("routeHandle and tenantId are required");
        }
        return discovery.resolveRouteHandle(request.routeHandle(), request.tenantId());
    }

    /**
     * Deregister all instances for the given {@code (tenantId, agentId)}
     * pair. REQ-2026-006 semantic generalization: previously deleted a single
     * row; now deletes every instance matching the pair. Single-instance
     * callers are backward compatible — they get all instances removed.
     *
     * @param tenantId    registry key mandatory dimension
     * @param agentId     agent identifier within the tenant
     * @param traceparent optional W3C traceparent header
     * @param xTraceId    optional fallback trace id header
     * @return HTTP 204 No Content regardless of whether any row matched
     */
    @DeleteMapping("/deregister/{tenantId}/{agentId}")
    public ResponseEntity<Void> deregister(
            @PathVariable String tenantId,
            @PathVariable String agentId,
            @RequestHeader(value = TRACE_PARENT_HEADER, required = false) String traceparent,
            @RequestHeader(value = X_TRACE_ID_HEADER, required = false) String xTraceId) {
        if (tenantId == null || tenantId.isBlank()
                || agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("tenantId and agentId are required path variables");
        }
        String traceId = resolveTraceId(traceparent, xTraceId);
        MDC.put("traceId", traceId);
        long start = System.nanoTime();
        String outcome = "error";
        try {
            boolean isDeleted = repository.delete(tenantId, agentId);
            outcome = isDeleted ? "success" : "not_found";
            return ResponseEntity.noContent().build();
        } finally {
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            observability.observeDeregister(
                    RegistryOpContext.of(traceId, tenantId, agentId).build(), outcome, latencyMs);
            MDC.remove("traceId");
        }
    }

    /**
     * Deregister a single instance by triple
     * {@code (tenantId, agentId, serviceId)}. REQ-2026-006 new endpoint:
     * rolling deploy of one replica must not evict other replicas.
     * {@code serviceId} is in the path — HD3-006 allows it because
     * {@code serviceId} is a logical service identifier (host-only,
     * caller-overridable), not the physical endpoint.
     *
     * <p>FEAT-016: with the 4-field PK, this now deletes <em>all</em>
     * concrete instances under the triple. Use the 4-field
     * {@link #deregisterSingleInstance} endpoint to target a specific
     * concrete instance.
     *
     * @param tenantId    registry key mandatory dimension
     * @param agentId     agent identifier within the tenant
     * @param serviceId   logical service identifier (host only)
     * @param traceparent optional W3C traceparent header
     * @param xTraceId    optional fallback trace id header
     * @return HTTP 204 No Content regardless of whether any row matched
     */
    @DeleteMapping("/deregister/{tenantId}/{agentId}/{serviceId}")
    public ResponseEntity<Void> deregisterSingle(
            @PathVariable String tenantId,
            @PathVariable String agentId,
            @PathVariable String serviceId,
            @RequestHeader(value = TRACE_PARENT_HEADER, required = false) String traceparent,
            @RequestHeader(value = X_TRACE_ID_HEADER, required = false) String xTraceId) {
        if (tenantId == null || tenantId.isBlank()
                || agentId == null || agentId.isBlank()
                || serviceId == null || serviceId.isBlank()) {
            throw new IllegalArgumentException("tenantId, agentId and serviceId are required path variables");
        }
        String traceId = resolveTraceId(traceparent, xTraceId);
        MDC.put("traceId", traceId);
        long start = System.nanoTime();
        String outcome = "error";
        try {
            boolean isDeleted = repository.delete(tenantId, agentId, serviceId);
            outcome = isDeleted ? "success" : "not_found";
            return ResponseEntity.noContent().build();
        } finally {
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            observability.observeDeregister(
                    RegistryOpContext.of(traceId, tenantId, agentId).build(), outcome, latencyMs);
            MDC.remove("traceId");
        }
    }

    /**
     * Deregister a single concrete instance by 4-field PK
     * {@code (tenantId, agentId, serviceId, instanceId)}. FEAT-016 new
     * endpoint — rolling deploy of a single replica must not evict other
     * replicas of the same {@code serviceId}. The 3-field
     * {@link #deregisterSingle} endpoint now deletes every instance under
     * the triple; this 4-field variant targets exactly one concrete
     * instance (host-port).
     *
     * <p>{@code instanceId} is the server-derived host-port identifier
     * (populated by {@link InstanceIdCodec#applyTo(AgentRegistryEntry)}
     * during register). The caller obtains it from a prior
     * {@code GET /instances/*} response's {@code routeHandle} (decoded
     * client-side) or from the {@code AgentCardDto.serviceId} grouping.
     *
     * @param pathVars    path-variable map carrying tenantId, agentId,
     *                    serviceId and instanceId
     * @param traceparent optional W3C traceparent header
     * @param xTraceId    optional fallback trace id header
     * @return HTTP 204 No Content regardless of whether any row matched
     */
    @DeleteMapping("/deregister/{tenantId}/{agentId}/{serviceId}/{instanceId}")
    public ResponseEntity<Void> deregisterSingleInstance(
            @PathVariable Map<String, String> pathVars,
            @RequestHeader(value = TRACE_PARENT_HEADER, required = false) String traceparent,
            @RequestHeader(value = X_TRACE_ID_HEADER, required = false) String xTraceId) {
        String tenantId = pathVars.get("tenantId");
        String agentId = pathVars.get("agentId");
        String serviceId = pathVars.get("serviceId");
        String instanceId = pathVars.get("instanceId");
        requireNonBlank("tenantId, agentId, serviceId and instanceId are required",
                tenantId, agentId, serviceId, instanceId);
        String traceId = resolveTraceId(traceparent, xTraceId);
        MDC.put("traceId", traceId);
        long start = System.nanoTime();
        String outcome = "error";
        try {
            boolean isDeleted = repository.delete(tenantId, agentId, serviceId, instanceId);
            outcome = isDeleted ? "success" : "not_found";
            return ResponseEntity.noContent().build();
        } finally {
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            observability.observeDeregister(
                    RegistryOpContext.of(traceId, tenantId, agentId).build(), outcome, latencyMs);
            MDC.remove("traceId");
        }
    }

    /**
     * Map {@link IllegalArgumentException} to HTTP 400. Handles both
     * malformed route-handle errors ({@code malformed_handle}) and generic
     * invalid-request errors ({@code invalid_request}).
     *
     * @param ex the illegal-argument exception raised by a controller method
     * @return error code + message body
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleBadRequest(IllegalArgumentException ex) {
        String code = ex.getMessage() != null && ex.getMessage().contains("route handle")
                ? "malformed_handle" : "invalid_request";
        return Map.of("error", code, "message", ex.getMessage());
    }

    /**
     * Map {@link TenantIsolationViolationException} to HTTP 400
     * {@code tenant_isolation_violation}.
     *
     * @param ex the tenant-isolation violation raised by the discovery layer
     * @return error code + message body
     */
    @ExceptionHandler(TenantIsolationViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleTenantViolation(TenantIsolationViolationException ex) {
        return Map.of("error", "tenant_isolation_violation", "message", ex.getMessage());
    }

    /**
     * Map {@link NoSuchElementException} to HTTP 404 {@code entry_not_found}.
     *
     * @param ex the no-such-element exception raised when a lookup misses
     * @return error code + message body
     */
    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> handleNotFound(NoSuchElementException ex) {
        return Map.of("error", "entry_not_found", "message", ex.getMessage());
    }

    /**
     * Apply default values to optional selection-hint fields the push caller
     * may have omitted. The {@code agent_registry_mvp} columns
     * {@code max_concurrency} and {@code weight} are NOT NULL, and
     * {@link com.openjiuwen.rdc.repository.JdbcAgentRegistryRepository#upsert}
     * binds these columns explicitly (so the DB-level DEFAULT does not apply
     * when the entry carries null). Push callers therefore rely on this
     * boundary to materialise the README-documented defaults (10 / 100).
     *
     * @param card entry whose optional fields may need defaulting; modified
     *             in place
     */
    private static void applyDefaults(AgentRegistryEntry card) {
        if (card.getMaxConcurrency() == null) {
            card.setMaxConcurrency(10);
        }
        if (card.getWeight() == null) {
            card.setWeight(100);
        }
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

    /**
     * Validate that none of the supplied values are {@code null} or blank.
     *
     * @param message error message for the thrown exception
     * @param values  values to check
     * @throws IllegalArgumentException if any value is {@code null} or blank
     */
    private static void requireNonBlank(String message, String... values) {
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(message);
            }
        }
    }

    private java.util.Optional<String> serializeA2aCard(org.a2aproject.sdk.spec.AgentCard card) {
        if (card == null) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(objectMapper.writeValueAsString(card));
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Failed to serialize a2aAgentCard to JSON", ex);
        }
    }

    /**
     * Request body for {@code POST /route-handle/resolve}. The
     * {@code tenantId} is cross-checked against the tenant encoded in the
     * route handle — mismatch raises {@link TenantIsolationViolationException}
     * (HTTP 400 {@code tenant_isolation_violation}).
     */
    public record ResolveRequest(String routeHandle, String tenantId) {
    }
}
