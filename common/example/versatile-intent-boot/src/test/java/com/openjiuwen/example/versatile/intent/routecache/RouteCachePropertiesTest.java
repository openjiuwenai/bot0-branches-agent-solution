/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.routecache;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies default state and setter behavior of {@link RouteCacheProperties}.
 *
 * @since 2026-07-25
 */
class RouteCachePropertiesTest {
    @Test
    void defaultsAreDisabledAndThirtyMinutes() {
        RouteCacheProperties props = new RouteCacheProperties();
        assertFalse(props.isEnabled());
        assertEquals(Duration.ofMinutes(30), props.getTtl());
    }

    @Test
    void settersMutateFields() {
        RouteCacheProperties props = new RouteCacheProperties();
        props.setEnabled(true);
        props.setTtl(Duration.ofMinutes(5));
        assertTrue(props.isEnabled());
        assertEquals(Duration.ofMinutes(5), props.getTtl());
    }
}
