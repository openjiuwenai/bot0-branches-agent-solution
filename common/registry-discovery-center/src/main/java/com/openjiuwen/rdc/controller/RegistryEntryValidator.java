/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.controller;

import com.openjiuwen.rdc.model.AgentRegistryEntry;
import com.openjiuwen.rdc.model.RegistryEntryInvalidException;

import java.net.URI;

/**
 * Validates push {@link AgentRegistryEntry} before persistence (0711 {@code REGISTRY_ENTRY_INVALID}).
 *
 * @since 0.1.0 (2026)
 */
final class RegistryEntryValidator {
    private RegistryEntryValidator() {
    }

    static void validate(AgentRegistryEntry entry, String traceId) {
        if (entry == null || !entry.hasRegistryKey()) {
            throw new RegistryEntryInvalidException(
                    "AgentRegistryEntry must carry tenantId + agentId (registry key)", traceId);
        }
        requireText(entry.getTenantId(), "tenantId", traceId);
        requireText(entry.getAgentId(), "agentId", traceId);
        requireText(entry.getAgentName(), "agentName", traceId);
        if (entry.getFrameworkType() == null) {
            throw new RegistryEntryInvalidException("frameworkType is required", traceId);
        }
        requireText(entry.getRouteKey(), "routeKey", traceId);
        requireText(entry.getContractVersion(), "contractVersion", traceId);
        requireText(entry.getCapabilityVersion(), "capabilityVersion", traceId);
        requireEndpoint(entry.getEndpointUrl(), traceId);
    }

    private static void requireText(String value, String field, String traceId) {
        if (value == null || value.isBlank()) {
            throw new RegistryEntryInvalidException(field + " must be non-empty", traceId);
        }
    }

    private static void requireEndpoint(String endpointUrl, String traceId) {
        if (endpointUrl == null || endpointUrl.isBlank()) {
            throw new RegistryEntryInvalidException("endpointUrl is required", traceId);
        }
        try {
            URI uri = URI.create(endpointUrl.trim());
            String scheme = uri.getScheme();
            if (scheme == null
                    || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                throw new RegistryEntryInvalidException(
                        "endpointUrl must use http or https scheme", traceId);
            }
        } catch (IllegalArgumentException ex) {
            throw new RegistryEntryInvalidException("endpointUrl is not a valid URI", traceId);
        }
    }
}
