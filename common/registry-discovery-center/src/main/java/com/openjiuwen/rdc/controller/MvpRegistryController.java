/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.rdc.config.RegistryObservabilityConfig;
import com.openjiuwen.rdc.config.RegistryOpAudit;
import com.openjiuwen.rdc.deployment.DeploymentDiscoveryProperties;
import com.openjiuwen.rdc.model.AgentCardDiscoveryQuery;
import com.openjiuwen.rdc.model.AgentCardDiscoveryResult;
import com.openjiuwen.rdc.model.AgentRegistryEntry;
import com.openjiuwen.rdc.model.DiscoveryConstraints;
import com.openjiuwen.rdc.model.HealthRequirement;
import com.openjiuwen.rdc.model.InstanceIdCodec;
import com.openjiuwen.rdc.model.InvalidDiscoveryQueryException;
import com.openjiuwen.rdc.model.PushRegistrationDisabledException;
import com.openjiuwen.rdc.model.RegistryEntryInvalidException;
import com.openjiuwen.rdc.model.RegistryRequestContext;
import com.openjiuwen.rdc.model.ServiceIdCodec;
import com.openjiuwen.rdc.repository.AgentRegistryRepository;
import com.openjiuwen.rdc.repository.RegistryPersistenceGuard;
import com.openjiuwen.rdc.service.AgentDiscoveryService;
import com.openjiuwen.rdc.service.RegistryEntryValidator;

import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * HTTP entry point for agent registry push/pull registration, deregister (agent /
 * service scope), and logical Agent Card discovery ({@code POST /discover}).
 *
 * <p>Runtime instance listing and route-handle resolve live on
 * {@link InstanceRouteController} under the same {@code /api/registry} prefix.
 *
 * <p>Exposes:
 * <ul>
 *   <li>{@code POST /api/registry/register} — upsert an {@link AgentRegistryEntry}</li>
 *   <li>{@code DELETE /api/registry/deregister/{tenantId}/{agentId}} — delete
 *       all instances for the pair (REQ-2026-006 semantic generalization)</li>
 *   <li>{@code DELETE /api/registry/deregister/{tenantId}/{agentId}/{serviceId}}
 *       — delete instances for the triple</li>
 *   <li>{@code POST /api/registry/discover} — Feat-015 logical Agent Card discovery</li>
 * </ul>
 *
 * <p>REQ-2026-006: the register endpoint derives {@code serviceId} from
 * {@code endpointUrl} via {@link ServiceIdCodec#applyTo(AgentRegistryEntry)}
 * after deserialization. The entry's {@code setServiceId} is package-private
 * (H2-1 方案 a) so HTTP callers cannot forge it; {@code applyTo} is the
 * single derivation bridge. The {@code @JsonIgnoreProperties(ignoreUnknown=true)}
 * on the {@code @RequestBody} is defense-in-depth — even if Jackson could
 * access the package-private setter via reflection, {@code applyTo}
 * overwrites the value afterwards.
 *
 * <p>Trace ID propagation (PR #389 review issue #8): the controller reads
 * inbound {@code traceparent} (W3C) / {@code X-Trace-Id} headers and uses
 * that as the audit/metrics trace ID. When no header is present, a fresh
 * UUID is generated.
 *
 * <p>Authority: ADR-0160 decisions 4 / 6 / 7 + HD3-001 / 002 / 003 / 006 +
 * REQ-2026-006 (multi-instance) + Feat-015 discover.
 *
 * @since 0.1.0
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
    private final DeploymentDiscoveryProperties deploymentDiscoveryProperties;

    public MvpRegistryController(AgentRegistryRepository repository,
                                 AgentDiscoveryService discovery,
                                 RegistryObservabilityConfig observability,
                                 ObjectMapper objectMapper,
                                 DeploymentDiscoveryProperties deploymentDiscoveryProperties) {
        this.repository = repository;
        this.discovery = discovery;
        this.observability = observability;
        this.objectMapper = objectMapper;
        this.deploymentDiscoveryProperties = deploymentDiscoveryProperties != null
                ? deploymentDiscoveryProperties
                : new DeploymentDiscoveryProperties();
    }

    /**
     * register.
     *
     * @param card card
     * @param traceparent traceparent
     * @param xTraceId xTraceId
     * @return result
     * @since 0.1.0
     */
    @PostMapping("/register")
    @Deprecated
    public ResponseEntity<Void> register(
            @RequestBody AgentRegistryEntry card,
            @RequestHeader(value = TRACE_PARENT_HEADER, required = false) String traceparent,
            @RequestHeader(value = X_TRACE_ID_HEADER, required = false) String xTraceId) {
        if (deploymentDiscoveryProperties.isEnabled()) {
            throw new PushRegistrationDisabledException(
                    "push registration disabled when rdc.deployment-discovery.enabled=true; "
                            + "use provider reconciliation path per Feat-015 0711");
        }
        if (card == null || !card.hasRegistryKey()) {
            throw new RegistryEntryInvalidException(
                    "AgentRegistryEntry must carry tenantId + agentId (registry key)",
                    resolveTraceId(traceparent, xTraceId));
        }
        String traceId = resolveTraceId(traceparent, xTraceId);
        MDC.put("traceId", traceId);
        long start = System.nanoTime();
        String outcome = "error";
        try {
            RegistryEntryValidator.validate(card, traceId);
            applyDefaults(card);
            if (card.getServiceId() == null || card.getServiceId().isBlank()) {
                ServiceIdCodec.applyTo(card);
            }
            InstanceIdCodec.applyTo(card);
            String a2aCardJson = serializeA2aCard(card.getA2aAgentCard()).orElse(null);
            RegistryPersistenceGuard.run(traceId, () -> repository.upsert(card, a2aCardJson));
            outcome = "success";
            return ResponseEntity.ok()
                    .header("Deprecation", "true")
                    .build();
        } finally {
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            observability.observeRegister(new RegistryOpAudit(
                    traceId, card.getTenantId(), card.getAgentId(),
                    card.getContractVersion(), card.getCapabilityVersion(),
                    "ONLINE", null, outcome, latencyMs));
            MDC.remove("traceId");
        }
    }

    /**
     * Structured Agent Card discovery per Feat-015 0713 {@code DiscoverAgentCards}.
     *
     * @param request request
     * @param traceparent traceparent
     * @param xTraceId xTraceId
     * @param callerRefHeader callerRefHeader
     * @return result
     * @since 0.1.0
     */
    @PostMapping("/discover")
    public AgentCardDiscoveryResult discover(@RequestBody DiscoverRequest request,
                                    @RequestHeader(value = TRACE_PARENT_HEADER, required = false) String traceparent,
                                    @RequestHeader(value = X_TRACE_ID_HEADER, required = false) String xTraceId,
                                    @RequestHeader(value = "X-Caller-Ref", required = false) String callerRefHeader) {
        String traceId = resolveTraceId(traceparent, xTraceId);
        if (request == null || request.context() == null) {
            throw new InvalidDiscoveryQueryException("INVALID_QUERY", "context is required", traceId);
        }
        String callerRef = callerRefHeader != null && !callerRefHeader.isBlank()
                ? callerRefHeader.trim()
                : (request.context().callerRef() != null && !request.context().callerRef().isBlank()
                ? request.context().callerRef().trim()
                : "http-client");
        RegistryRequestContext ctx = new RegistryRequestContext(
                request.context().tenantId(),
                callerRef,
                traceId,
                request.context().requestId() != null ? request.context().requestId() : traceId,
                request.context().deadline() != null ? request.context().deadline() : Instant.now().plusSeconds(30));
        DiscoveryConstraints constraints = buildConstraints(request.constraints());
        AgentCardDiscoveryQuery query = AgentCardDiscoveryQuery.builder()
                .context(ctx)
                .agentId(request.agentId())
                .serviceId(request.serviceId())
                .a2aSkillId(request.a2aSkillId())
                .constraints(constraints)
                .limit(request.limit() != null ? request.limit() : 20)
                .continuationToken(request.continuationToken())
                .build();
        return discovery.discoverAgentCards(query);
    }

    /**
     * Deregister all instances for the given {@code (tenantId, agentId)} pair.
     *
     * @param tenantId tenantId
     * @param agentId agentId
     * @param traceparent traceparent
     * @param xTraceId xTraceId
     * @return result
     * @since 0.1.0
     */
    @DeleteMapping("/deregister/{tenantId}/{agentId}")
    public ResponseEntity<Void> deregister(@PathVariable String tenantId,
                                           @PathVariable String agentId,
                                           @RequestHeader(value = TRACE_PARENT_HEADER, required = false)
                                                   String traceparent,
                                           @RequestHeader(value = X_TRACE_ID_HEADER, required = false)
                                                   String xTraceId) {
        if (tenantId == null || tenantId.isBlank()
                || agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("tenantId and agentId are required path variables");
        }
        String traceId = resolveTraceId(traceparent, xTraceId);
        MDC.put("traceId", traceId);
        long start = System.nanoTime();
        String outcome = "error";
        try {
            boolean deleted = RegistryPersistenceGuard.execute(
                    traceId, () -> repository.delete(tenantId, agentId));
            outcome = deleted ? "success" : "not_found";
            return ResponseEntity.noContent().build();
        } finally {
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            observability.observeDeregister(traceId, tenantId, agentId, outcome, latencyMs);
            MDC.remove("traceId");
        }
    }

    /**
     * Deregister a single instance by triple {@code (tenantId, agentId, serviceId)}.
     *
     * @param tenantId tenantId
     * @param agentId agentId
     * @param serviceId serviceId
     * @param traceparent traceparent
     * @param xTraceId xTraceId
     * @return result
     * @since 0.1.0
     */
    @DeleteMapping("/deregister/{tenantId}/{agentId}/{serviceId}")
    public ResponseEntity<Void> deregisterSingle(@PathVariable String tenantId,
                                                 @PathVariable String agentId,
                                                 @PathVariable String serviceId,
                                                 @RequestHeader(value = TRACE_PARENT_HEADER, required = false)
                                                         String traceparent,
                                                 @RequestHeader(value = X_TRACE_ID_HEADER, required = false)
                                                         String xTraceId) {
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
            boolean deleted = RegistryPersistenceGuard.execute(
                    traceId, () -> repository.delete(tenantId, agentId, serviceId));
            outcome = deleted ? "success" : "not_found";
            return ResponseEntity.noContent().build();
        } finally {
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            observability.observeDeregister(traceId, tenantId, agentId, outcome, latencyMs);
            MDC.remove("traceId");
        }
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
     * @param card card
     * @since 0.1.0
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

    private Optional<String> serializeA2aCard(org.a2aproject.sdk.spec.AgentCard card) {
            if (card == null) {
                return Optional.empty();
            }
        try {
                return Optional.of(objectMapper.writeValueAsString(card));
                } catch (JsonProcessingException ex) {
                throw new IllegalArgumentException("Failed to serialize a2aAgentCard to JSON", ex);
            }
    }

    /**
     * Shared context fields for discover request bodies.
     *
     * @param context context
     * @param agentId agentId
     * @param serviceId serviceId
     * @param a2aSkillId a2aSkillId
     * @param constraints constraints
     * @param limit limit
     * @param continuationToken continuationToken
     * @since 0.1.0
     */
    public record DiscoverRequest(
            ContextRequest context,
            String agentId,
            String serviceId,
            String a2aSkillId,
            ConstraintsRequest constraints,
            Integer limit,
            String continuationToken
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

    /**
     * ConstraintsRequest.
     *
     * @param contractVersion contractVersion
     * @param capabilityVersion capabilityVersion
     * @param requiredSkillTags requiredSkillTags
     * @param requiredCapabilities requiredCapabilities
     * @param requiredInputModes requiredInputModes
     * @param requiredOutputModes requiredOutputModes
     * @param requiredSecuritySchemes requiredSecuritySchemes
     * @param healthRequirement healthRequirement
     * @return result
     * @since 0.1.0
     */
    public record ConstraintsRequest(
            String contractVersion,
            String capabilityVersion,
            Set<String> requiredSkillTags,
            Set<String> requiredCapabilities,
            Set<String> requiredInputModes,
            Set<String> requiredOutputModes,
            Set<String> requiredSecuritySchemes,
            HealthRequirement healthRequirement
    ) {
    }

    private static DiscoveryConstraints buildConstraints(ConstraintsRequest constraints) {
        if (constraints == null) {
    return DiscoveryConstraints.none();
}
        return DiscoveryConstraints.builder()
                .contractVersion(constraints.contractVersion())
                .capabilityVersion(constraints.capabilityVersion())
                .requiredSkillTags(constraints.requiredSkillTags())
                .requiredCapabilities(constraints.requiredCapabilities())
                .requiredInputModes(constraints.requiredInputModes())
                .requiredOutputModes(constraints.requiredOutputModes())
                .requiredSecuritySchemes(constraints.requiredSecuritySchemes())
                .healthRequirement(constraints.healthRequirement())
                .build();
    }
}
