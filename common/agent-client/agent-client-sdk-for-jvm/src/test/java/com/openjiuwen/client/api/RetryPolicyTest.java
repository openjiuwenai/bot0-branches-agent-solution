/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.time.Duration;

/**
 * RetryPolicy 的单元测试，验证默认策略、自定义策略和非法参数校验。
 *
 * @since 2026-08-19
 */
class RetryPolicyTest {
    @Test
    void defaultsMatchFeat006RecoverySemantics() {
        RetryPolicy policy = RetryPolicy.defaults();

        assertEquals(3, policy.maxConsecutiveFailures());
        assertEquals(Duration.ofMillis(200), policy.delayForFailure(1));
        assertEquals(Duration.ofMillis(400), policy.delayForFailure(2));
        assertEquals(Duration.ofMillis(800), policy.delayForFailure(3));
        assertEquals(Duration.ofMillis(800), policy.delayForFailure(20));
    }

    @Test
    void customPolicyAppliesCapAndJitterBounds() {
        RetryPolicy policy = RetryPolicy.builder()
                .initialDelay(Duration.ofMillis(50))
                .maxDelay(Duration.ofMillis(120))
                .multiplier(3.0d)
                .jitterFactor(0.25d)
                .maxConsecutiveFailures(5)
                .build();

        for (int i = 0; i < 100; i++) {
            long firstDelay = policy.delayForFailure(1).toMillis();
            long cappedDelay = policy.delayForFailure(10).toMillis();
            assertTrue(firstDelay >= 38L && firstDelay <= 63L);
            assertTrue(cappedDelay >= 90L && cappedDelay <= 120L);
        }
        assertEquals(5, policy.maxConsecutiveFailures());
    }

    @Test
    void rejectsInvalidPolicy() {
        assertThrows(IllegalArgumentException.class,
                () -> RetryPolicy.builder().initialDelay(Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> RetryPolicy.builder().multiplier(0.5d));
        assertThrows(IllegalArgumentException.class,
                () -> RetryPolicy.builder().jitterFactor(1.0d));
        assertThrows(IllegalArgumentException.class,
                () -> RetryPolicy.builder().maxConsecutiveFailures(0));
        assertThrows(IllegalArgumentException.class, () -> RetryPolicy.builder()
                .initialDelay(Duration.ofSeconds(2))
                .maxDelay(Duration.ofSeconds(1))
                .build());
        assertThrows(IllegalArgumentException.class,
                () -> RetryPolicy.defaults().delayForFailure(0));
    }
}
