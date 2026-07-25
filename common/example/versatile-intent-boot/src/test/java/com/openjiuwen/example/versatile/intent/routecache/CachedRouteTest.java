/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.routecache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Verifies accessor and expiry semantics of {@link CachedRoute}.
 *
 * @since 2026-07-25
 */
class CachedRouteTest {
    @Test
    void exposesAgentNameAndResponseContent() {
        CachedRoute route = new CachedRoute("agent_card_layer2_hotel", "L1 output", 1_000L);
        assertEquals("agent_card_layer2_hotel", route.agentName());
        assertEquals("L1 output", route.responseContent());
        assertEquals(1_000L, route.expiresAt());
    }

    @Test
    void isExpiredReturnsTrueWhenNowAfterExpiresAt() {
        CachedRoute route = new CachedRoute("a", "", 100L);
        assertTrue(route.isExpired(101L));
        assertFalse(route.isExpired(100L));
        assertFalse(route.isExpired(99L));
    }
}
