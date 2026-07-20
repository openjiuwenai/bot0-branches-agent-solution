/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.rdc.card.AgentCardValidator;
import com.openjiuwen.rdc.model.AgentIdCodec;
import com.openjiuwen.rdc.model.DiscoveryCandidate;
import com.openjiuwen.rdc.model.DiscoveryConstraints;
import com.openjiuwen.rdc.model.DiscoveryOutcome;
import com.openjiuwen.rdc.model.DiscoveryQuery;
import com.openjiuwen.rdc.model.DiscoveryResult;
import com.openjiuwen.rdc.model.Freshness;
import com.openjiuwen.rdc.model.InvalidDiscoveryQueryException;
import com.openjiuwen.rdc.model.RegistrationInvalidException;
import com.openjiuwen.rdc.model.RegistrationStatus;
import com.openjiuwen.rdc.repository.AgentRegistryRepository.DiscoveryFilter;
import com.openjiuwen.rdc.repository.AgentRegistryRepository.LogicalRegistrationRow;
import com.openjiuwen.rdc.repository.AgentRegistryRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies Feat-015 0713 structured logical Agent Card discovery filtering and
 * outcome semantics against {@code agent_card_registration}.
 */
final class StructuredDiscoveryEngine {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int OUTCOME_EVAL_LIMIT = 500;

    private StructuredDiscoveryEngine() {
    }

    static DiscoveryResult discover(AgentRegistryRepository repository, DiscoveryQuery query) {
        query.validate();
        String tenantId = query.context().tenantId();
        String traceId = query.context().traceId();

        int offset;
        try {
            offset = ContinuationTokenCodec.decodeOffset(query.continuationToken(), query);
        } catch (IllegalArgumentException ex) {
            throw new InvalidDiscoveryQueryException("INVALID_QUERY", ex.getMessage(), query.context().traceId());
        }

        QuerySelector selector = resolveSelector(tenantId, query);
        DiscoveryFilter filter = new DiscoveryFilter(
                tenantId,
                selector.agentId(),
                selector.logicalServiceId(),
                query.a2aSkillId(),
                OUTCOME_EVAL_LIMIT);

        List<LogicalRegistrationRow> facts = repository.queryLogicalByTargetSelector(filter);
        if (facts.isEmpty()) {
            return DiscoveryResult.of(DiscoveryOutcome.NO_MATCH, List.of(), traceId);
        }

        List<LogicalRegistrationRow> registered = facts.stream()
                .filter(StructuredDiscoveryEngine::isRegistered)
                .toList();
        if (registered.isEmpty()) {
            return DiscoveryResult.of(DiscoveryOutcome.NO_MATCH, List.of(), traceId);
        }

        List<LogicalRegistrationRow> versioned = applyVersion(registered, query.constraints());
        if (versioned.isEmpty()) {
            return DiscoveryResult.of(DiscoveryOutcome.VERSION_UNAVAILABLE, List.of(), traceId);
        }

        List<LogicalRegistrationRow> constrained = applyExecutionConstraints(versioned, query);
        if (constrained.isEmpty()) {
            return DiscoveryResult.of(DiscoveryOutcome.CONSTRAINT_UNAVAILABLE, List.of(), traceId);
        }

        List<DiscoveryCandidate> deduped = deduplicateLogicalCards(constrained, query.a2aSkillId(), traceId);
        List<DiscoveryCandidate> page = new ArrayList<>();
        int end = Math.min(offset + query.limit(), deduped.size());
        for (int i = offset; i < end; i++) {
            page.add(deduped.get(i));
        }
        String nextToken = end < deduped.size()
                ? ContinuationTokenCodec.encode(query, end)
                : null;
        return new DiscoveryResult(DiscoveryOutcome.SUCCESS, page, nextToken, traceId);
    }

    private static boolean isRegistered(LogicalRegistrationRow row) {
        return RegistrationStatus.REGISTERED.name().equals(row.registrationStatus());
    }

    private static QuerySelector resolveSelector(String tenantId, DiscoveryQuery query) {
        String agentId = blankToNull(query.agentId());
        String serviceId = blankToNull(query.serviceId());
        String logicalServiceId = null;
        if (serviceId != null) {
            if (agentId != null) {
                logicalServiceId = serviceId;
            } else {
                agentId = AgentIdCodec.derive(tenantId, serviceId);
                logicalServiceId = serviceId;
            }
        }
        return new QuerySelector(agentId, logicalServiceId);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record QuerySelector(String agentId, String logicalServiceId) {
    }

    private static List<LogicalRegistrationRow> applyVersion(
            List<LogicalRegistrationRow> rows, DiscoveryConstraints constraints) {
        String contractVersion = constraints.contractVersion();
        String capabilityVersion = constraints.capabilityVersion();
        if ((contractVersion == null || contractVersion.isBlank())
                && (capabilityVersion == null || capabilityVersion.isBlank())) {
            return rows;
        }
        return rows.stream()
                .filter(row -> contractVersion == null || contractVersion.isBlank()
                        || contractVersion.equals(row.contractVersion()))
                .filter(row -> capabilityVersion == null || capabilityVersion.isBlank()
                        || capabilityVersion.equals(row.capabilityVersion()))
                .toList();
    }

    private static List<LogicalRegistrationRow> applyExecutionConstraints(
            List<LogicalRegistrationRow> rows, DiscoveryQuery query) {
        DiscoveryConstraints constraints = query.constraints();
        var tags = constraints.requiredSkillTags();
        var caps = constraints.requiredCapabilities();
        var inputModes = constraints.requiredInputModes();
        var outputModes = constraints.requiredOutputModes();
        var security = constraints.requiredSecuritySchemes();
        if (tags.isEmpty() && caps.isEmpty() && inputModes.isEmpty()
                && outputModes.isEmpty() && security.isEmpty()) {
            return rows;
        }
        List<LogicalRegistrationRow> matched = new ArrayList<>();
        for (LogicalRegistrationRow row : rows) {
            if (row.a2aAgentCardJson() == null || row.a2aAgentCardJson().isBlank()) {
                continue;
            }
            try {
                JsonNode root = MAPPER.readTree(row.a2aAgentCardJson());
                JsonNode skill = findSkill(root, query.a2aSkillId());
                if (!tags.isEmpty() && !AgentCardValidator.skillTagsContain(root, tags)) {
                    continue;
                }
                if (!caps.isEmpty()) {
                    boolean ok = true;
                    for (String cap : caps) {
                        if (!AgentCardValidator.hasCapability(root, cap)) {
                            ok = false;
                            break;
                        }
                    }
                    if (!ok) {
                        continue;
                    }
                }
                if (!inputModes.isEmpty()) {
                    boolean ok = true;
                    for (String mode : inputModes) {
                        if (!AgentCardValidator.supportsInputMode(root, skill, mode)) {
                            ok = false;
                            break;
                        }
                    }
                    if (!ok) {
                        continue;
                    }
                }
                if (!outputModes.isEmpty()) {
                    boolean ok = true;
                    for (String mode : outputModes) {
                        if (!AgentCardValidator.supportsOutputMode(root, skill, mode)) {
                            ok = false;
                            break;
                        }
                    }
                    if (!ok) {
                        continue;
                    }
                }
                if (!security.isEmpty()) {
                    boolean ok = true;
                    for (String scheme : security) {
                        if (!AgentCardValidator.supportsSecurityScheme(root, scheme)) {
                            ok = false;
                            break;
                        }
                    }
                    if (!ok) {
                        continue;
                    }
                }
                matched.add(row);
            } catch (JsonProcessingException ignored) {
                // skip malformed card snapshots
            }
        }
        return matched;
    }

    private static JsonNode findSkill(JsonNode root, String skillId) {
        if (skillId == null || skillId.isBlank()) {
            return null;
        }
        JsonNode skills = root.get("skills");
        if (skills == null || !skills.isArray()) {
            return null;
        }
        for (JsonNode skill : skills) {
            if (skillId.equals(skill.path("id").asText(null))) {
                return skill;
            }
        }
        return null;
    }

    private static List<DiscoveryCandidate> deduplicateLogicalCards(
            List<LogicalRegistrationRow> rows, String requestedSkillId, String traceId) {
        Map<String, DiscoveryCandidate> byLogicalKey = new LinkedHashMap<>();
        for (LogicalRegistrationRow row : rows) {
            String key = row.serviceId() + "|" + row.capabilityVersion() + "|" + row.cardDigest();
            byLogicalKey.computeIfAbsent(key, ignored -> toCandidate(row, requestedSkillId, traceId));
        }
        return List.copyOf(byLogicalKey.values());
    }

    private static DiscoveryCandidate toCandidate(LogicalRegistrationRow row,
                                                  String requestedSkillId,
                                                  String traceId) {
        validateRegistrationIntegrity(row, traceId);
        return DiscoveryCandidate.builder()
                .agentCardJson(row.a2aAgentCardJson())
                .agentId(row.agentId())
                .serviceId(row.serviceId())
                .matchedA2aSkillId(requestedSkillId)
                .contractVersion(row.contractVersion())
                .capabilityVersion(row.capabilityVersion())
                .registrationStatus(RegistrationStatus.valueOf(row.registrationStatus()))
                .freshness(Freshness.valueOf(row.freshness()))
                .lastValidatedAt(row.lastValidatedAt())
                .build();
    }

    /**
     * REGISTERED rows exposed to callers must carry a parseable card snapshot and
     * valid metadata enums (0713 {@code REGISTRATION_INVALID}).
     */
    private static void validateRegistrationIntegrity(LogicalRegistrationRow row, String traceId) {
        try {
            RegistrationStatus.valueOf(row.registrationStatus());
            Freshness.valueOf(row.freshness());
        } catch (IllegalArgumentException ex) {
            throw new RegistrationInvalidException(
                    "invalid registration metadata for serviceId=" + row.serviceId(),
                    traceId);
        }
        if (!RegistrationStatus.REGISTERED.name().equals(row.registrationStatus())) {
            return;
        }
        if (row.a2aAgentCardJson() == null || row.a2aAgentCardJson().isBlank()) {
            throw new RegistrationInvalidException(
                    "REGISTERED logical registration missing card snapshot: serviceId=" + row.serviceId(),
                    traceId);
        }
        try {
            MAPPER.readTree(row.a2aAgentCardJson());
        } catch (JsonProcessingException ex) {
            throw new RegistrationInvalidException(
                    "REGISTERED logical registration has malformed card snapshot: serviceId="
                            + row.serviceId(),
                    traceId);
        }
    }
}
