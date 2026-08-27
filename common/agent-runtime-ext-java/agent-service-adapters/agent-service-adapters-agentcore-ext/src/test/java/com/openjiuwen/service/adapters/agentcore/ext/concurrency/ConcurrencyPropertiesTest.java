/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ConcurrencyProperties}.
 *
 * @since 0.1.0
 */
class ConcurrencyPropertiesTest {
    @Test
    void defaultValue_isUnlimited() {
        ConcurrencyProperties properties = new ConcurrencyProperties();
        assertThat(properties.getMaxConcurrentTasks()).isEqualTo(-1);
    }

    @Test
    void bindsPositiveInteger() {
        ConcurrencyProperties properties = new ConcurrencyProperties();
        properties.setMaxConcurrentTasks(50);
        assertThat(properties.getMaxConcurrentTasks()).isEqualTo(50);
    }
}
