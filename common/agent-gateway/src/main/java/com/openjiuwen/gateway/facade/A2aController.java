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
import com.openjiuwen.gateway.governance.auth.AuthRule;
import com.openjiuwen.gateway.governance.auth.Principal;
import com.openjiuwen.gateway.governance.tenant.TenantResolver;
import com.openjiuwen.gateway.governance.validate.ParamValidator;

/**
 * A2A JSON-RPC facade entry — {@code POST /a2a} (FEAT-011 L2 §1.1 / §4.9 GW-1).
 *
 * <p>Governance runs in fixed order G1→G2→G3→… before any routing/forwarding.
 * Current slice: G1 auth (header), G2 tenant (authoritative, self-report
 * discarded), G3 param validation (JSON-RPC shape / method whitelist / agentId /
 * create-vs-resume classification). {@link com.openjiuwen.gateway.governance.GovernanceException}
 * maps to the stable HTTP error body via the advice. G4–G5 / routing / forwarding
 * are wired in later slices — this method currently returns a placeholder past G3.
 *
 * @since 0.1.0
 */
@RestController
public class A2aController {
    private final AuthRule authRule;
    private final TenantResolver tenantResolver;
    private final ParamValidator paramValidator;

    /**
     * Construct.
     *
     * @param authRule       G1 authentication rule
     * @param tenantResolver G2 tenant resolver
     * @param paramValidator G3 parameter validator
     */
    public A2aController(AuthRule authRule, TenantResolver tenantResolver, ParamValidator paramValidator) {
        this.authRule = authRule;
        this.tenantResolver = tenantResolver;
        this.paramValidator = paramValidator;
    }

    /**
     * Receive an A2A JSON-RPC request.
     *
     * @param authorization     raw {@code Authorization} header (may be absent)
     * @param selfReportedTenant raw {@code X-Tenant-Id} header (may be absent; discarded by G2)
     * @param jsonRpcBody       raw JSON-RPC envelope (parsed by G3)
     * @return response (placeholder until G4+/routing/forwarding are wired)
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

        // G4–G5 + path/routing/forwarding are wired in later slices.
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32601,"
                        + "\"message\":\"agent-gateway past G3; forwarding not complete (FEAT-011 WIP)\"}}");
    }
}
