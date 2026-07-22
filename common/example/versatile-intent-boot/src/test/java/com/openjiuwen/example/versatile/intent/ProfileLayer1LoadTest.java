/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Smoke test: verify the Spring context loads under the {@code layer1} profile.
 *
 * @since 0.1.0
 */
@SpringBootTest
@ActiveProfiles("layer1")
class ProfileLayer1LoadTest {
    @Test
    void contextLoads() {
    }
}
