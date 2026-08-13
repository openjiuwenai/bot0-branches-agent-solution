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
        String routeHandle, Object requestId, State state) {

    /**
     * Compatibility constructor for admissions without a JSON-RPC request id.
     *
     * @param tenantId tenant identity
     * @param idempotencyKey idempotency identity
     * @param requestDigest request digest
     * @param taskId Task identity
     * @param sourceFamily source event family
     * @param correlationId correlation identity
     * @param traceId trace identity
     * @param sourceServiceId source service identity
     * @param targetServiceId target service identity
     * @param routeHandle opaque route handle
     * @param state admission state
     */
    public Admission(String tenantId, String idempotencyKey, String requestDigest, String taskId,
            String sourceFamily, String correlationId, String traceId, String sourceServiceId, String targetServiceId,
            String routeHandle, State state) {
        this(tenantId, idempotencyKey, requestDigest, taskId, sourceFamily, correlationId, traceId, sourceServiceId,
                targetServiceId, routeHandle, null, state);
    }

    /**
     * Admission persistence states.
     */
    public enum State {
        RESERVED, ADMITTED
    }
}
