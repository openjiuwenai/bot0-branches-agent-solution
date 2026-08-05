/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.obs;

import java.time.Instant;

/**
 * Governance audit record (FEAT-011 L2 §3.7). Captures the minimal facts for a
 * request: who / which tenant / what method / passed or rejected (with stage +
 * code) / traceId / timestamp.
 *
 * <p>By construction this carries NO credential (no Bearer token) and NO prompt
 * or body text (T-G5-3). Fields that are unknown at the failure point (e.g. an
 * early G1 reject has no tenant/method yet) are {@code null}.
 *
 * @param traceId      correlation id (from traceparent or self-generated)
 * @param principalId  authenticated principal (G1); null before G1 passes
 * @param tenantId     authoritative tenant (G2); null before G2 passes
 * @param method       JSON-RPC method (G3); null before G3 passes
 * @param outcome      PASSED or REJECTED
 * @param rejectStage  G1/G2/G3/G4 when REJECTED; null when PASSED
 * @param code         stable error code when REJECTED; null when PASSED
 * @param timestamp    event time
 * @since 0.1.0
 */
public record AuditEvent(
        String traceId,
        String principalId,
        String tenantId,
        String method,
        Outcome outcome,
        String rejectStage,
        String code,
        Instant timestamp) {
    /**
     * Governance outcome.
     */
    public enum Outcome {
        PASSED,
        REJECTED
    }
}
