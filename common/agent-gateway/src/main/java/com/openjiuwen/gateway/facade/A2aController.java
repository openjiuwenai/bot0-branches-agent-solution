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

/**
 * A2A JSON-RPC facade entry — {@code POST /a2a} (FEAT-011 L2 §1.1 / §4.9 GW-1).
 *
 * <p>Governance runs in fixed order G1→G2→G3→G4→… before any routing/forwarding.
 * Current slice: G1 auth, G2 tenant, G3 validation (create/resume classification),
 * G4 create idempotency (resume skips). {@link GovernanceException} maps to the
 * stable HTTP error body via the advice. G5 / routing / forwarding are wired in
 * later slices — this method currently returns a placeholder past G4.
 *
 * @since 0.1.0
 */
@RestController
public class A2aController {
    private final AuthRule authRule;
    private final TenantResolver tenantResolver;
    private final ParamValidator paramValidator;
    private final IdempotencyRule idempotencyRule;

    /**
     * Construct.
     *
     * @param authRule       G1 authentication rule
     * @param tenantResolver G2 tenant resolver
     * @param paramValidator G3 parameter validator
     * @param idempotencyRule G4 create idempotency
     */
    public A2aController(AuthRule authRule, TenantResolver tenantResolver,
                         ParamValidator paramValidator, IdempotencyRule idempotencyRule) {
        this.authRule = authRule;
        this.tenantResolver = tenantResolver;
        this.paramValidator = paramValidator;
        this.idempotencyRule = idempotencyRule;
    }

    /**
     * Receive an A2A JSON-RPC request.
     *
     * @param authorization     raw {@code Authorization} header (may be absent)
     * @param selfReportedTenant raw {@code X-Tenant-Id} header (may be absent; discarded by G2)
     * @param jsonRpcBody       raw JSON-RPC envelope (parsed by G3)
     * @return response (placeholder until G5/routing/forwarding are wired)
     */
    @PostMapping("/a2a")
    public ResponseEntity<String> postA2a(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Tenant-Id", required = false) String selfReportedTenant,
            @RequestBody String jsonRpcBody) {
        GovernanceContext context = new GovernanceContext();
        context.setRawBody(jsonRpcBody);

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

        // G5 + path/routing/forwarding are wired in later slices.
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32601,"
                        + "\"message\":\"agent-gateway past G4; forwarding not complete (FEAT-011 WIP)\"}}");
    }
}
