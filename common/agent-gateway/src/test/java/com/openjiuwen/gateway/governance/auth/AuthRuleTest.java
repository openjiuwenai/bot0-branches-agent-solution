/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.governance.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.openjiuwen.gateway.governance.GovernanceException;

/**
 * Unit tests for G1 {@link AuthRule} judgment order (FEAT-011 L2 §3.3.1
 * T-G1-1..T-G1-4). Pure logic — no Spring.
 */
class AuthRuleTest {
    private final CredentialDirectory directory = token ->
            "good-token".equals(token)
                    ? Optional.of(new Principal("principal-1", "tenant-1"))
                    : Optional.empty();
    private final AuthRule rule = new AuthRule(directory);

    /** Runs an action that must throw a GovernanceException and returns it typed. */
    private static GovernanceException govern(Runnable action) {
        Throwable thrown = catchThrowable(action::run);
        assertThat(thrown).as("expected a GovernanceException").isNotNull();
        assertThat(thrown).isInstanceOf(GovernanceException.class);
        return (GovernanceException) thrown;
    }

    @Test
    void missingHeaderReturnsAuthMissing() {
        GovernanceException ge = govern(() -> rule.authenticate(null));
        assertThat(ge.code()).isEqualTo("AUTH_MISSING");
        assertThat(ge.httpStatus().value()).isEqualTo(401);
    }

    @Test
    void blankHeaderReturnsAuthMissing() {
        assertThat(govern(() -> rule.authenticate("   ")).code()).isEqualTo("AUTH_MISSING");
    }

    @Test
    void nonBearerSchemeReturnsAuthInvalid() {
        GovernanceException ge = govern(() -> rule.authenticate("Basic abc"));
        assertThat(ge.code()).isEqualTo("AUTH_INVALID");
        assertThat(ge.httpStatus().value()).isEqualTo(401);
    }

    @Test
    void emptyBearerTokenReturnsAuthInvalid() {
        assertThat(govern(() -> rule.authenticate("Bearer ")).code()).isEqualTo("AUTH_INVALID");
    }

    @Test
    void unknownTokenReturnsAuthInvalid() {
        assertThat(govern(() -> rule.authenticate("Bearer nope")).code()).isEqualTo("AUTH_INVALID");
    }

    @Test
    void validTokenReturnsPrincipal() {
        Principal principal = rule.authenticate("Bearer good-token");
        assertThat(principal.principalId()).isEqualTo("principal-1");
        assertThat(principal.tenantId()).isEqualTo("tenant-1");
    }
}
