/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.config;

/**
 * Bundled audit + outcome fields for {@link RegistryObservabilityConfig} (G.MET.01).
 * Null optional fields are rendered as {@code "-"} by the facade.
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
