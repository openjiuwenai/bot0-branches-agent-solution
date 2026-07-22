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

/**
 * A2A JSON-RPC facade entry — {@code POST /a2a} (FEAT-011 L2 §1.1 / §4.9 GW-1).
 *
 * <p>S1-G1 wiring: G1 authentication runs first on every request
 * (header-based; the body is held opaquely and parsed later by G3, so malformed
 * JSON does not fail before auth). On {@link com.openjiuwen.gateway.governance.GovernanceException}
 * the advice returns the stable HTTP error body; on success the trusted context
 * carries the principal and the request proceeds (G2–G5 / routing / forwarding
 * are wired in subsequent slices — this method currently returns a placeholder
 * after G1).
 *
 * @since 0.1.0
 */
@RestController
public class A2aController {
    private final AuthRule authRule;

    /**
     * Construct.
     *
     * @param authRule G1 authentication rule
     */
    public A2aController(AuthRule authRule) {
        this.authRule = authRule;
    }

    /**
     * Receive an A2A JSON-RPC request.
     *
     * @param authorization raw {@code Authorization} header (may be absent)
     * @param jsonRpcBody raw JSON-RPC envelope (held opaquely at this layer)
     * @return response (placeholder until G2+/routing/forwarding are wired)
     */
    @PostMapping("/a2a")
    public ResponseEntity<String> postA2a(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody String jsonRpcBody) {
        // G1 — authentication (throws GovernanceException -> advice on failure).
        Principal principal = authRule.authenticate(authorization);

        GovernanceContext context = new GovernanceContext();
        context.setRawBody(jsonRpcBody);
        context.setPrincipalId(principal.principalId());

        // G2–G5 + path/routing/forwarding are wired in later slices.
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32601,"
                        + "\"message\":\"agent-gateway past G1; governance/forwarding not complete (FEAT-011 WIP)\"}}");
    }
}
