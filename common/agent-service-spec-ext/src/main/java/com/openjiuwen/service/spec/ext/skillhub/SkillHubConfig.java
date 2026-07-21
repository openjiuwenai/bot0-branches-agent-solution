/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.ext.skillhub;

/**
 * Stable deployment-time SkillHub connection config (FEAT-005 §4.2, §5.2).
 *
 * <p>Pure POJO contract without Spring annotations so the spec-ext module stays
 * free of Spring dependencies. The implementation module
 * ({@code agent-service-adapters-agentcore-ext}) provides a
 * {@code @ConfigurationProperties} subclass bound to
 * {@code openjiuwen.service.middleware.skillhub}.
 *
 * <p>SPI methods receive this contract type as input; runtime request-level
 * user/session/task context is intentionally excluded (FEAT-005 §5.2).
 *
 * @since 2026-07-15
 */
public class SkillHubConfig {
    private boolean enabled = false;
    private String endpoint = "";

    /** bearer | system-token */
    private String authType = "bearer";

    /** Encrypted token; decrypted by CredentialDecryptor at use site. */
    private String encryptedToken = "";

    /** Local directory for downloaded skill packages. */
    private String localDir = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getAuthType() {
        return authType;
    }

    public void setAuthType(String authType) {
        this.authType = authType;
    }

    public String getEncryptedToken() {
        return encryptedToken;
    }

    public void setEncryptedToken(String encryptedToken) {
        this.encryptedToken = encryptedToken;
    }

    public String getLocalDir() {
        return localDir;
    }

    public void setLocalDir(String localDir) {
        this.localDir = localDir;
    }
}
