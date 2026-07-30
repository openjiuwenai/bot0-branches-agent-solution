/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.governance.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.openjiuwen.gateway.governance.GovernanceException;
import com.openjiuwen.gateway.governance.auth.Principal;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for G2 {@link TenantResolver} (FEAT-011 L2 §3.4 T-G2-1..T-G2-4).
 */
class TenantResolverTest {
    private final TenantResolver resolver = new TenantResolver();
    private final Principal bound = new Principal("principal-1", "tenant-1");
    private final Principal unbound = new Principal("principal-2", null);

    private static GovernanceException govern(Runnable action) {
        Throwable thrown = catchThrowable(action::run);
        assertThat(thrown).as("expected a GovernanceException").isNotNull();
        if (thrown instanceof GovernanceException ge) {
            return ge;
        }
        throw new AssertionError("expected GovernanceException but got: " + thrown);
    }

    @Test
    void boundCredentialResolvesAuthoritativeTenant() {
        assertThat(resolver.resolve(bound, null)).isEqualTo("tenant-1");
    }

    @Test
    void unboundCredentialReturns403TenantUnresolved() {
        GovernanceException ge = govern(() -> resolver.resolve(unbound, null));
        assertThat(ge.code()).isEqualTo("TENANT_UNRESOLVED");
        assertThat(ge.httpStatus().value()).isEqualTo(403);
    }

    @Test
    void conflictingSelfReportIsIgnored() {
        // Self-report differs from authoritative -> authoritative still wins.
        assertThat(resolver.resolve(bound, "tenant-other")).isEqualTo("tenant-1");
    }

    @Test
    void matchingSelfReportStillYieldsAuthoritative() {
        // Self-report equals authoritative -> result is still the authoritative parse, not "trusted self-report".
        assertThat(resolver.resolve(bound, "tenant-1")).isEqualTo("tenant-1");
    }
}
