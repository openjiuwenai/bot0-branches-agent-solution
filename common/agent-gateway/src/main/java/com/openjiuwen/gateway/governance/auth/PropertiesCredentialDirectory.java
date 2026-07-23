/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.governance.auth;

import java.util.Optional;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 730 configuration-backed {@link CredentialDirectory}: a single agreed test
 * credential (FEAT-011 L2 §3.3.2 / §3.4.1 "联调可用测试 token + 固定租户").
 *
 * <p>Reads {@code gateway.test-credential.{token,principalId,tenantId}}. When
 * unconfigured, every token resolves empty and G1 rejects with
 * {@code AUTH_INVALID} — a safe default (no credential ⇒ no entry). The
 * production IdP / claim mapping is a later concern and does not change the port
 * shape or the G1/G2 contract.
 *
 * @since 0.1.0
 */
@Component
public class PropertiesCredentialDirectory implements CredentialDirectory {
    private final String token;
    private final String principalId;
    private final String tenantId;

    /**
     * Construct from environment-backed configuration.
     *
     * @param env Spring environment
     */
    public PropertiesCredentialDirectory(Environment env) {
        this.token = env.getProperty("gateway.test-credential.token");
        this.principalId = env.getProperty("gateway.test-credential.principalId");
        this.tenantId = env.getProperty("gateway.test-credential.tenantId");
    }

    @Override
    public Optional<Principal> find(String presented) {
        if (token == null || token.isEmpty() || !token.equals(presented)) {
            return Optional.empty();
        }
        return Optional.of(new Principal(principalId, tenantId));
    }
}
