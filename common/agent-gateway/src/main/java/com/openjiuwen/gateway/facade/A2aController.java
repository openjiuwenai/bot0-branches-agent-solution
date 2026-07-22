/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.facade;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.openjiuwen.gateway.governance.GovernanceContext;
import com.openjiuwen.gateway.governance.GovernanceException;
import com.openjiuwen.gateway.governance.auth.AuthRule;
import com.openjiuwen.gateway.governance.auth.Principal;
import com.openjiuwen.gateway.governance.idempotency.IdempotencyRule;
import com.openjiuwen.gateway.governance.tenant.TenantResolver;
import com.openjiuwen.gateway.governance.validate.ParamValidator;
import com.openjiuwen.gateway.obs.GovernanceAuditor;

import java.util.UUID;

/**
 * A2A JSON-RPC facade entry — {@code POST /a2a} (FEAT-011 L2 §1.1 / §4.9 GW-1).
 *
 * <p>Governance runs in fixed order G1→G2→G3→G4, with G5 audit covering both the
 * pass and reject paths. A request-scoped traceId is resolved at entry (from
 * {@code traceparent}, else self-generated) and threaded into the context, the
 * audit records, and the error body. {@link GovernanceException} maps to the
 * stable HTTP error body via the advice. Routing / forwarding are wired in later
 * slices — this method currently returns a placeholder past G4.
 *
 * @since 0.1.0
 */
@RestController
public class A2aController {
    private final AuthRule authRule;
    private final TenantResolver tenantResolver;
    private final ParamValidator paramValidator;
    private final IdempotencyRule idempotencyRule;
    private final GovernanceAuditor auditor;

    /**
     * Construct.
     *
     * @param authRule        G1 authentication rule
     * @param tenantResolver  G2 tenant resolver
     * @param paramValidator  G3 parameter validator
     * @param idempotencyRule G4 create idempotency
     * @param auditor         G5 governance auditor
     */
    public A2aController(AuthRule authRule, TenantResolver tenantResolver, ParamValidator paramValidator,
                         IdempotencyRule idempotencyRule, GovernanceAuditor auditor) {
        this.authRule = authRule;
        this.tenantResolver = tenantResolver;
        this.paramValidator = paramValidator;
        this.idempotencyRule = idempotencyRule;
        this.auditor = auditor;
    }

    /**
     * Receive an A2A JSON-RPC request.
     *
     * @param authorization     raw {@code Authorization} header (may be absent)
     * @param traceparent       W3C {@code traceparent} header (may be absent)
     * @param selfReportedTenant raw {@code X-Tenant-Id} header (may be absent; discarded by G2)
     * @param jsonRpcBody       raw JSON-RPC envelope (parsed by G3)
     * @return response (placeholder until routing/forwarding are wired)
     */
    @PostMapping("/a2a")
    public ResponseEntity<String> postA2a(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "traceparent", required = false) String traceparent,
            @RequestHeader(value = "X-Tenant-Id", required = false) String selfReportedTenant,
            @RequestBody String jsonRpcBody) {
        GovernanceContext context = new GovernanceContext();
        context.setRawBody(jsonRpcBody);
        context.setTraceId(resolveTraceId(traceparent));

        try {
            // G1 — authentication.
            Principal principal = authRule.authenticate(authorization);
            // G2 — authoritative tenant resolution; self-report discarded.
            String tenantId = tenantResolver.resolve(principal, selfReportedTenant);
            // G3 — parameter validation + create/resume classification.
            paramValidator.validate(jsonRpcBody, context);

            context.setPrincipalId(principal.principalId());
            context.setTenantId(tenantId);

            // G4 — create idempotency (resume skips).
            if (context.taskId() == null) {
                IdempotencyRule.Decision idem = idempotencyRule.check(tenantId, context.messageId(), jsonRpcBody);
                switch (idem.outcome()) {
                    case NEW, SKIP -> { /* proceed to later stages */ }
                    case REPLAY -> {
                        return ResponseEntity.ok(idem.result());
                    }
                    case CONFLICT -> throw new GovernanceException(HttpStatus.CONFLICT,
                            "IDEMPOTENCY_PAYLOAD_MISMATCH",
                            "Create idempotency key conflict: payload differs from the first attempt");
                    case IN_FLIGHT_DUPLICATE -> throw new GovernanceException(HttpStatus.CONFLICT,
                            "IDEMPOTENCY_IN_FLIGHT",
                            "A create with this idempotency key is already in progress");
                }
            }
        } catch (GovernanceException ex) {
            ex.setTraceId(context.traceId());
            auditor.auditRejected(context, ex);
            throw ex;
        }

        // G5 — audit the governance pass.
        auditor.auditPassed(context);

        // Routing / forwarding are wired in later slices.
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32601,"
                        + "\"message\":\"agent-gateway governance complete; forwarding not wired (FEAT-011 WIP)\"}}");
    }

    /**
     * Resolve the request trace id from W3C {@code traceparent} (segment 1), or
     * self-generate one (L2 §3.7 P5 — missing traceparent does not fail).
     */
    private static String resolveTraceId(String traceparent) {
        if (traceparent != null && !traceparent.isBlank()) {
            String[] parts = traceparent.split("-");
            if (parts.length >= 2 && !parts[1].isBlank()) {
                return parts[1];
            }
        }
        return UUID.randomUUID().toString();
    }
}
