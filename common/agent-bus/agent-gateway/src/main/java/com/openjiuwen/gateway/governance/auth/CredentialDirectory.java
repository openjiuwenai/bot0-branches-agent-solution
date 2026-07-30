/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.governance.auth;

import java.util.Optional;

/**
 * Resolves a validated Bearer token to its bound {@link Principal} (identity +
 * authoritative tenant). This is the gateway-side view of the token → principal
 * binding (FEAT-011 L2 §3.3.2 / §3.4.1 "凭据绑定租户").
 *
 * <p>730 default implementation is configuration-backed (test / agreed Bearer
 * tokens bound to a tenant); the production IdP / claim mapping is a later
 * concern and does not affect this port's shape. Unknown or invalid tokens
 * resolve to {@link Optional#empty()} and G1 maps that to {@code AUTH_INVALID}.
 *
 * @since 0.1.0
 */
public interface CredentialDirectory {
    /**
     * Look up the principal bound to a token.
     *
     * @param token non-empty bearer token
     * @return bound principal, or empty if the token is unknown / revoked
     */
    Optional<Principal> find(String token);
}
