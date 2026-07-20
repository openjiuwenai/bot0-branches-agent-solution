/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Security-related registry configuration (caller allowlist).
 *
 * @since 0.1.0
 */
@Component
@ConfigurationProperties(prefix = "rdc.registry.security")
public class RegistrySecurityProperties {

    /** tenantId → allowed callerRef values. Empty map = permissive (only non-blank caller required). */
    private Map<String, Set<String>> callerAllowlist = new HashMap<>();

    public Map<String, Set<String>> getCallerAllowlist() {
        return callerAllowlist;
    }

    public void setCallerAllowlist(Map<String, Set<String>> callerAllowlist) {
        this.callerAllowlist = callerAllowlist != null ? callerAllowlist : new HashMap<>();
    }

    public boolean isAllowlistConfigured() {
        return callerAllowlist != null && !callerAllowlist.isEmpty();
    }
}
