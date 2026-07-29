/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.model;

/**
 * Derives stable {@code agent_id} from deployment service identity per
 * Feat-015 0711 scope §5.1.1 ({@code tenantId + serviceId}).
 *
 * @since 0.1.0 (2026)
 */
public final class AgentIdCodec {
    private AgentIdCodec() {
    }

    /**
     * Within a tenant, the deployment {@code serviceId} is the stable Agent
     * Service identity. Card {@code name} is display-only.
     *
     * @param tenantId tenantId
     * @param deploymentServiceId deploymentServiceId
     * @return result
     * @since 0.1.0
     */
    public static String derive(String tenantId, String deploymentServiceId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (deploymentServiceId == null || deploymentServiceId.isBlank()) {
            throw new IllegalArgumentException("deploymentServiceId must not be blank");
        }
        return deploymentServiceId.trim();
    }
}
