/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link StickyIndex}, incl. the {@code findOwner} pair used by the BUS GetTask
 * path to route a query to the task's owning runtime (route handle + target service id).
 *
 * @since 2026-08-17
 */
class StickyIndexTest {
    private final StickyIndex sticky = new StickyIndex(3_600_000L, 0L); // no background sweeper

    @Test
    void findOwnerReturnsRouteHandleAndTargetServiceId() {
        sticky.put("task-1", "handle-1", "svc-owner-1");
        var owner = sticky.findOwner("task-1");
        assertThat(owner).isPresent();
        assertThat(owner.get().routeHandle()).isEqualTo("handle-1");
        assertThat(owner.get().targetServiceId()).isEqualTo("svc-owner-1");
    }

    @Test
    void findStillReturnsRouteHandleOnlyForBackwardCompat() {
        sticky.put("task-2", "handle-2", "svc-owner-2");
        assertThat(sticky.find("task-2")).contains("handle-2");
    }

    @Test
    void findOwnerMissesForUnknownTask() {
        assertThat(sticky.findOwner("ghost")).isEmpty();
    }
}
