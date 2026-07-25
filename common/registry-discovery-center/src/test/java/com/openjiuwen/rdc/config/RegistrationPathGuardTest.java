/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * RegistrationPathGuard coverage (PR #73 review scheme B).
 *
 * @since 0.1.0 (2026)
 */
class RegistrationPathGuardTest {
    @Test
    void dual_enable_fails_fast() {
        assertThatThrownBy(() -> RegistrationPathGuard.apply(true, true, true, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot enable both");
    }

    @Test
    void pull_only_allowed_with_deprecation_warn() {
        assertThatCode(() -> RegistrationPathGuard.apply(
                true, false, true, LoggerFactory.getLogger("test")))
                .doesNotThrowAnyException();
    }

    @Test
    void deployment_only_allowed() {
        assertThatCode(() -> RegistrationPathGuard.apply(
                false, true, false, LoggerFactory.getLogger("test")))
                .doesNotThrowAnyException();
    }

    @Test
    void both_disabled_allowed() {
        assertThatCode(() -> RegistrationPathGuard.apply(false, false, true, null))
                .doesNotThrowAnyException();
    }
}
