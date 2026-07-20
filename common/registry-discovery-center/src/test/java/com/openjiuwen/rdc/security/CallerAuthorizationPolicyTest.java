/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.rdc.model.CallerNotAuthorizedException;

import org.junit.jupiter.api.Test;

import java.util.Set;

class CallerAuthorizationPolicyTest {
    @Test
    void permissive_rejects_blank_caller() {
        CallerAuthorizationPolicy policy = new CallerAuthorizationPolicy.Permissive();
        assertThatThrownBy(() -> policy.authorize("tenant-a", "  ", "trace-1"))
                .isInstanceOf(CallerNotAuthorizedException.class);
    }

    @Test
    void permissive_allows_non_blank_caller() {
        CallerAuthorizationPolicy policy = new CallerAuthorizationPolicy.Permissive();
        assertThatCode(() -> policy.authorize("tenant-a", "gateway", "trace-1"))
                .doesNotThrowAnyException();
    }

    @Test
    void allowlist_rejects_unknown_caller() {
        RegistrySecurityProperties props = new RegistrySecurityProperties();
        props.getCallerAllowlist().put("tenant-a", Set.of("gateway"));
        CallerAuthorizationPolicy policy = new CallerAuthorizationPolicy.Allowlist(props);

        assertThatThrownBy(() -> policy.authorize("tenant-a", "event-bus", "trace-1"))
                .isInstanceOf(CallerNotAuthorizedException.class);
    }

    @Test
    void allowlist_allows_listed_caller() {
        RegistrySecurityProperties props = new RegistrySecurityProperties();
        props.getCallerAllowlist().put("tenant-a", Set.of("gateway", "event-bus"));
        CallerAuthorizationPolicy policy = new CallerAuthorizationPolicy.Allowlist(props);

        assertThatCode(() -> policy.authorize("tenant-a", "event-bus", "trace-1"))
                .doesNotThrowAnyException();
    }
}
