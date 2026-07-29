/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import java.time.Instant;

/**
 * RegistryRequestContext validation coverage (null/blank → INVALID_QUERY).
 *
 * @since 0.1.0 (2026)
 */
class RegistryRequestContextTest {
    @Test
    void validate_passes_when_all_fields_present() {
        RegistryRequestContext ctx = new RegistryRequestContext(
                "tenant-A", "gateway", "trace-1", "req-1", Instant.now().plusSeconds(30));
        assertThatCode(ctx::validate).doesNotThrowAnyException();
    }

    @Test
    void validate_null_tenantId_raises_invalid_query_not_npe() {
        RegistryRequestContext ctx = new RegistryRequestContext(
                null, "gateway", "trace-1", "req-1", Instant.now().plusSeconds(30));
        assertThatThrownBy(ctx::validate)
                .isInstanceOf(InvalidDiscoveryQueryException.class)
                .satisfies(ex -> {
                    if (ex instanceof InvalidDiscoveryQueryException iq) {
                        assertThat(iq.failureCode()).isEqualTo("INVALID_QUERY");
                        assertThat(iq.getMessage()).contains("tenantId");
                    }
                });
    }

    @Test
    void validate_blank_tenantId_raises_invalid_query() {
        RegistryRequestContext ctx = new RegistryRequestContext(
                "  ", "gateway", "trace-1", "req-1", Instant.now().plusSeconds(30));
        assertThatThrownBy(ctx::validate)
                .isInstanceOf(InvalidDiscoveryQueryException.class)
                .satisfies(ex -> {
                    if (ex instanceof InvalidDiscoveryQueryException iq) {
                        assertThat(iq.failureCode()).isEqualTo("INVALID_QUERY");
                    }
                });
    }
}
