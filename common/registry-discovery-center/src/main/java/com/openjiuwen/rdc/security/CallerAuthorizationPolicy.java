/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.security;

import com.openjiuwen.rdc.model.CallerNotAuthorizedException;

/**
 * Tenant-scoped caller authorization per Feat-015 0711 §5.1.6.
 *
 * @since 0.1.0 (2026)
 */
public interface CallerAuthorizationPolicy {
    /**
     * authorize.
     *
     * @param tenantId tenantId
     * @param callerRef callerRef
     * @param traceId traceId
     * @since 0.1.0
     */
    void authorize(String tenantId, String callerRef, String traceId);

    /**
     * Allow-all policy when no allowlist is configured.
     */
    final class Permissive implements CallerAuthorizationPolicy {

        /**
         * authorize.
         *
         * @param tenantId tenantId
         * @param callerRef callerRef
         * @param traceId traceId
         * @since 0.1.0
         */
        @Override
        public void authorize(String tenantId, String callerRef, String traceId) {
            if (callerRef == null || callerRef.isBlank()) {
                throw new CallerNotAuthorizedException(tenantId, "<empty>", traceId);
            }
        }
    }

    /**
     * Deny callers not listed under the tenant in {@link RegistrySecurityProperties}.
     */
    final class Allowlist implements CallerAuthorizationPolicy {
        private final RegistrySecurityProperties properties;

        public Allowlist(RegistrySecurityProperties properties) {
            this.properties = properties;
        }

        /**
         * authorize.
         *
         * @param tenantId tenantId
         * @param callerRef callerRef
         * @param traceId traceId
         * @since 0.1.0
         */
        @Override
        public void authorize(String tenantId, String callerRef, String traceId) {
            if (callerRef == null || callerRef.isBlank()) {
                throw new CallerNotAuthorizedException(tenantId, "<empty>", traceId);
            }
            var allowed = properties.getCallerAllowlist().get(tenantId);
            if (allowed == null || allowed.isEmpty()) {
                return;
            }
            if (!allowed.contains(callerRef)) {
                throw new CallerNotAuthorizedException(tenantId, callerRef, traceId);
            }
        }
    }
}
