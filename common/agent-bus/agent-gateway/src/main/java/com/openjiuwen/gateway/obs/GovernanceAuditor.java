/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.obs;

import com.openjiuwen.gateway.governance.GovernanceContext;
import com.openjiuwen.gateway.governance.GovernanceException;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * Builds {@link AuditEvent}s from the governance context and dispatches them to
 * the {@link AuditSink} (FEAT-011 L2 §3.7). Called by the facade: PASSED after
 * G1–G4 all pass, REJECTED at the failure point. The reject stage is inferred
 * from the stable error-code prefix (AUTH_*→G1, TENANT_*→G2, VALIDATION_*→G3,
 * IDEMPOTENCY_*→G4) so existing G throws need not carry a stage field.
 *
 * @since 0.1.0
 */
@Component
public class GovernanceAuditor {
    private final AuditSink sink;

    /**
     * Construct.
     *
     * @param sink audit sink
     */
    public GovernanceAuditor(AuditSink sink) {
        this.sink = sink;
    }

    /**
     * Audit a governance pass (after G4).
     *
     * @param ctx governance context
     */
    public void auditPassed(GovernanceContext ctx) {
        sink.record(new AuditEvent(ctx.traceId(), ctx.principalId(), ctx.tenantId(), ctx.method(),
                AuditEvent.Outcome.PASSED, null, null, Instant.now()));
    }

    /**
     * Audit a governance rejection (at the failing step).
     *
     * @param ctx governance context (may be partially populated)
     * @param ex  the failure carrying the stable code
     */
    public void auditRejected(GovernanceContext ctx, GovernanceException ex) {
        sink.record(new AuditEvent(ctx.traceId(), ctx.principalId(), ctx.tenantId(), ctx.method(),
                AuditEvent.Outcome.REJECTED, stageFromCode(ex.code()).orElse(null), ex.code(), Instant.now()));
    }

    private static Optional<String> stageFromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        if (code.startsWith("AUTH_")) {
            return Optional.of("G1");
        }
        if (code.startsWith("TENANT_")) {
            return Optional.of("G2");
        }
        if (code.startsWith("VALIDATION_")) {
            return Optional.of("G3");
        }
        if (code.startsWith("IDEMPOTENCY_")) {
            return Optional.of("G4");
        }
        return Optional.empty();
    }
}
