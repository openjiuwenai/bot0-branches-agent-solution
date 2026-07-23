/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.service.bus.consumer.stream.StreamReferenceService;

import org.junit.jupiter.api.Test;

/**
 * Tests StreamReferenceService behavior.
 *
 * @since 2026-07-22
 */
class StreamReferenceServiceTest {
    @Test
    void issuesAndValidatesScopedReference() {
        var service = new StreamReferenceService("secret", 60);
        String ref = service.issue("tenant-a", "task-1", 100);
        assertThat(service.validate(ref, "tenant-a", "task-1", 120).expiresAtEpochSeconds()).isEqualTo(160);
    }

    @Test
    void rejectsTamperScopeAndExpiry() {
        var service = new StreamReferenceService("secret", 60);
        String ref = service.issue("tenant-a", "task-1", 100);
        assertThatThrownBy(() -> service.validate(ref, "tenant-b", "task-1", 100))
                .hasMessage("STREAM_REF_SCOPE_INVALID");
        assertThatThrownBy(() -> service.validate(ref, "tenant-a", "task-1", 161)).hasMessage("STREAM_REF_EXPIRED");
        assertThatThrownBy(() -> service.validate(ref + "x", "tenant-a", "task-1", 100))
                .hasMessage("STREAM_REF_INVALID");
    }
}
