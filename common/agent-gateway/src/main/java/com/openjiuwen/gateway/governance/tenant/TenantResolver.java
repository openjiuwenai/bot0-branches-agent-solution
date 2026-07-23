/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.governance.tenant;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.openjiuwen.gateway.governance.GovernanceException;
import com.openjiuwen.gateway.governance.auth.Principal;

/**
 * G2 — tenant resolution &amp; self-report cleaning (FEAT-011 L2 §3.4). The
 * authoritative {@code tenantId} comes from the credential binding
 * ({@link Principal#tenantId()}), never from a caller-supplied header/body field.
 * A credential that passes G1 but carries no tenant binding fails here with
 * 403 {@code TENANT_UNRESOLVED} (distinct from G1's AUTH_*).
 *
 * <p>The self-reported tenant is accepted as a parameter precisely so it can be
 * discarded — authoritative always wins. This makes the cleaning contract
 * observable in tests (the result is the authoritative value regardless of the
 * self-report).
 *
 * @since 0.1.0
 */
@Component
public class TenantResolver {
    /**
     * Resolve the authoritative tenant for an authenticated principal.
     *
     * @param principal           G1 result (carries the bound tenant, may be {@code null})
     * @param selfReportedTenant  caller-supplied tenant (e.g. {@code X-Tenant-Id}); intentionally ignored
     * @return the authoritative tenant id
     * @throws GovernanceException 403 {@code TENANT_UNRESOLVED} if the credential has no tenant binding
     */
    public String resolve(Principal principal, String selfReportedTenant) {
        String authoritative = principal.tenantId();
        if (authoritative == null || authoritative.isBlank()) {
            throw new GovernanceException(HttpStatus.FORBIDDEN, "TENANT_UNRESOLVED",
                    "Credential has no tenant binding");
        }
        // Self-report is deliberately discarded; authoritative wins (L2 §3.4 P2).
        return authoritative;
    }
}
