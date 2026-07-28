/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.reclassify;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Verifies {@link AmbiguousPayload} record component accessors.
 *
 * @since 2026-07-24
 */
class AmbiguousPayloadTest {
    @Test
    void holdsAllFields() {
        AmbiguousPayload payload = new AmbiguousPayload("1", "无法确定", "1");
        assertThat(payload.intentId()).isEqualTo("1");
        assertThat(payload.responseContent()).isEqualTo("无法确定");
        assertThat(payload.ambiguousIntentId()).isEqualTo("1");
    }
}
