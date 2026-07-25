/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.governance.auth;

import com.openjiuwen.gateway.governance.GovernanceException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * G1 — authentication (FEAT-011 L2 §3.3). Every HTTP request to the A2A facade
 * must carry a non-empty {@code Authorization: Bearer <token>}; the body never
 * carries identity. Judgment order (L2 §3.3.1):
 * <ol>
 *   <li>missing {@code Authorization} → 401 {@code AUTH_MISSING}</li>
 *   <li>not {@code Bearer <token>} or empty token → 401 {@code AUTH_INVALID}</li>
 *   <li>token not bound in the directory → 401 {@code AUTH_INVALID}</li>
 * </ol>
 * On success returns the {@link Principal} (G1 keeps {@code principalId}; G2
 * later consumes {@code tenantId}). On failure throws {@link GovernanceException};
 * the caller MUST NOT proceed to routing / forwarding.
 *
 * @since 0.1.0
 */
@Component
public class AuthRule {
    /**
     * Bearer scheme prefix (case-sensitive per L2 §3.3.1).
     */
    private static final String BEARER_PREFIX = "Bearer ";

    private final CredentialDirectory directory;

    /**
     * Construct with a credential directory.
     *
     * @param directory token → principal binding
     */
    public AuthRule(CredentialDirectory directory) {
        this.directory = directory;
    }

    /**
     * Authenticate an {@code Authorization} header value.
     *
     * @param authorizationHeader raw header value (may be {@code null})
     * @return the bound principal
     * @throws GovernanceException 401 {@code AUTH_MISSING} / {@code AUTH_INVALID} on failure
     */
    public Principal authenticate(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            throw new GovernanceException(HttpStatus.UNAUTHORIZED, "AUTH_MISSING",
                    "Missing Authorization");
        }
        if (!authorizationHeader.startsWith(BEARER_PREFIX)) {
            throw new GovernanceException(HttpStatus.UNAUTHORIZED, "AUTH_INVALID",
                    "Authorization scheme must be Bearer");
        }
        String token = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            throw new GovernanceException(HttpStatus.UNAUTHORIZED, "AUTH_INVALID",
                    "Bearer token must not be empty");
        }
        return directory.find(token)
                .orElseThrow(() -> new GovernanceException(HttpStatus.UNAUTHORIZED, "AUTH_INVALID",
                        "Unknown or invalid token"));
    }
}
