/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.model;

/**
 * Carries Admission data.
 *
 * @since 2026-07-22
 */
public record Admission(String tenantId, String idempotencyKey, String requestDigest, String taskId,
        String sourceFamily, String correlationId, String traceId, String sourceServiceId, String targetServiceId,
        String routeHandle, State state) {

    /**
     * Admission persistence states.
     */
    public enum State {
        RESERVED, ADMITTED
    }
}
