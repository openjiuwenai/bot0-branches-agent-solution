/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link StickyIndex}, incl. the {@code (tenantId, taskId)} composite key (FEAT-011
 * §8.1 #1/#8) and the {@code findOwner} pair used by the BUS GetTask path to route a query to the
 * task's owning runtime (route handle + target service id).
 *
 * @since 2026-08-17
 */
class StickyIndexTest {
    private final StickyIndex sticky = new StickyIndex(3_600_000L, 0L); // no background sweeper

    @Test
    void findOwnerReturnsRouteHandleAndTargetServiceId() {
        sticky.put("tenant-1", "task-1", "handle-1", "svc-owner-1");
        var owner = sticky.findOwner("tenant-1", "task-1");
        assertThat(owner).isPresent();
        assertThat(owner.get().routeHandle()).isEqualTo("handle-1");
        assertThat(owner.get().targetServiceId()).isEqualTo("svc-owner-1");
    }

    @Test
    void findStillReturnsRouteHandleOnlyForBackwardCompat() {
        sticky.put("tenant-1", "task-2", "handle-2", "svc-owner-2");
        assertThat(sticky.find("tenant-1", "task-2")).contains("handle-2");
    }

    @Test
    void findOwnerMissesForUnknownTask() {
        assertThat(sticky.findOwner("tenant-1", "ghost")).isEmpty();
    }

    /**
     * §8.1 #1/#8: a query from tenant-B for a task owned by tenant-A MUST miss (composite key) —
     * the binding is never returned to the wrong tenant, so the runtime is never called and no
     * cross-tenant snapshot/stream leaks. The Gateway maps this miss to {@code TASK_NOT_FOUND}.
     */
    @Test
    void findMissesForCrossTenantTask() {
        sticky.put("tenant-A", "task-x", "h-A", "svc-A");
        // same taskId, different tenant → miss (different composite key)
        assertThat(sticky.find("tenant-B", "task-x")).isEmpty();
        assertThat(sticky.findOwner("tenant-B", "task-x")).isEmpty();
        // the owning tenant still resolves its own binding
        assertThat(sticky.find("tenant-A", "task-x")).contains("h-A");
    }

    /**
     * §8.1 #1: same taskId across two tenants does NOT collide — each tenant's task is a distinct
     * composite-key entry (a bare-taskId map would overwrite, leaking/corrupting the binding).
     */
    @Test
    void sameTaskIdAcrossTenantsDoesNotCollide() {
        sticky.put("tenant-A", "shared-id", "h-A", "svc-A");
        sticky.put("tenant-B", "shared-id", "h-B", "svc-B");
        assertThat(sticky.find("tenant-A", "shared-id")).contains("h-A");
        assertThat(sticky.find("tenant-B", "shared-id")).contains("h-B");
    }
}
