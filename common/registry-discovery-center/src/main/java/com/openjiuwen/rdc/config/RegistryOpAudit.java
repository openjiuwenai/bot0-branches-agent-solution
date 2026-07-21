/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.config;

/**
 * Bundled audit + outcome fields for {@link RegistryObservabilityConfig} (G.MET.01).
 * Null optional fields are rendered as {@code "-"} by the facade.
 *
 * @param traceId traceId
 * @param tenantId tenantId
 * @param agentId agentId
 * @param contractVersion contractVersion
 * @param capabilityVersion capabilityVersion
 * @param health health
 * @param routeHandleId routeHandleId
 * @param outcome outcome
 * @param latencyMs latencyMs
 * @return result
 * @since 0.1.0
 */
public record RegistryOpAudit(
        String traceId,
        String tenantId,
        String agentId,
        String contractVersion,
        String capabilityVersion,
        String health,
        String routeHandleId,
        String outcome,
        long latencyMs) {
}
