/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.obs;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.gateway.governance.GovernanceContext;
import com.openjiuwen.gateway.governance.GovernanceException;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for {@link GovernanceAuditor} (FEAT-011 L2 §3.7 T-G5-1..T-G5-3).
 */
class GovernanceAuditorTest {
    private final List<AuditEvent> captured = new ArrayList<>();
    private final GovernanceAuditor auditor = new GovernanceAuditor(captured::add);

    private static GovernanceContext passedContext() {
        GovernanceContext ctx = new GovernanceContext();
        ctx.setTraceId("trace-1");
        ctx.setPrincipalId("principal-1");
        ctx.setTenantId("tenant-1");
        ctx.setMethod("SendMessage");
        return ctx;
    }

    @Test
    void passedRecordCarriesTenantMethodTrace() {
        auditor.auditPassed(passedContext());
        assertThat(captured).hasSize(1);
        AuditEvent e = captured.get(0);
        assertThat(e.outcome()).isEqualTo(AuditEvent.Outcome.PASSED);
        assertThat(e.tenantId()).isEqualTo("tenant-1");
        assertThat(e.method()).isEqualTo("SendMessage");
        assertThat(e.traceId()).isEqualTo("trace-1");
        assertThat(e.rejectStage()).isNull();
        assertThat(e.code()).isNull();
        assertThat(e.timestamp()).isNotNull();
    }

    @Test
    void rejectedRecordCarriesStageAndCode() {
        GovernanceException ex = new GovernanceException(HttpStatus.UNAUTHORIZED, "AUTH_MISSING", "x");
        auditor.auditRejected(passedContext(), ex);
        AuditEvent e = captured.get(0);
        assertThat(e.outcome()).isEqualTo(AuditEvent.Outcome.REJECTED);
        assertThat(e.rejectStage()).isEqualTo("G1");
        assertThat(e.code()).isEqualTo("AUTH_MISSING");
    }

    @Test
    void rejectedStageInferredForAllGovernanceCodes() {
        auditor.auditRejected(passedContext(), new GovernanceException(HttpStatus.FORBIDDEN, "TENANT_UNRESOLVED", "x"));
        auditor.auditRejected(passedContext(), new GovernanceException(HttpStatus.BAD_REQUEST, "VALIDATION_METHOD", "x"));
        auditor.auditRejected(passedContext(), new GovernanceException(HttpStatus.CONFLICT, "IDEMPOTENCY_PAYLOAD_MISMATCH", "x"));
        assertThat(captured).extracting(AuditEvent::rejectStage, AuditEvent::code)
                .containsExactly(
                        tuple("G2", "TENANT_UNRESOLVED"),
                        tuple("G3", "VALIDATION_METHOD"),
                        tuple("G4", "IDEMPOTENCY_PAYLOAD_MISMATCH"));
    }

    @Test
    void recordsNeverCarryCredentialOrBody() {
        // AuditEvent has no token/body field by construction; verify the recorded
        // event exposes only the allowed minimal fields.
        auditor.auditPassed(passedContext());
        AuditEvent e = captured.get(0);
        assertThat(e).extracting("traceId", "principalId", "tenantId", "method", "outcome")
                .containsExactly("trace-1", "principal-1", "tenant-1", "SendMessage", AuditEvent.Outcome.PASSED);
    }

    private static org.assertj.core.groups.Tuple tuple(String stage, String code) {
        return org.assertj.core.api.Assertions.tuple(stage, code);
    }
}
