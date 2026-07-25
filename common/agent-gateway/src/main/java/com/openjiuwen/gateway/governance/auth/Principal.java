/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.governance.auth;

/**
 * Authenticated principal resolved from a validated Bearer credential
 * (FEAT-011 L2 §3.3.1 G1). Carries the identity the gateway trusts after G1,
 * and the authoritative tenant binding G2 consumes (L2 §3.4.1 "凭据绑定租户").
 *
 * <p>{@code tenantId} may be absent for a credential that passes G1 but has no
 * tenant binding; G2 then rejects with {@code TENANT_UNRESOLVED} rather than
 * G1 failing — keeping G1 (auth) and G2 (tenant) error codes distinct.
 *
 * @param principalId trusted principal identity (G1 writes this to context)
 * @param tenantId authoritative tenant bound to the credential (G2 consumes; may be {@code null})
 * @since 0.1.0
 */
public record Principal(String principalId, String tenantId) {
}
