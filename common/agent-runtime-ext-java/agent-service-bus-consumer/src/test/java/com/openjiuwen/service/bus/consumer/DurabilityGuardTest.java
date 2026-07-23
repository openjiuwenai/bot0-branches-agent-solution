/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.service.bus.consumer.runtime.DurabilityGuard;
import com.openjiuwen.service.bus.consumer.store.InMemoryBusResponseProjectionStore;
import com.openjiuwen.service.bus.consumer.store.InMemoryBusTaskAdmissionStore;

import org.a2aproject.sdk.server.tasks.InMemoryTaskStore;
import org.junit.jupiter.api.Test;

/**
 * Tests DurabilityGuard behavior.
 *
 * @since 2026-07-22
 */
class DurabilityGuardTest {
    @Test
    void rejectsEphemeralStoresForReliableProfile() {
        assertThatThrownBy(() -> DurabilityGuard.verify(true, false, new InMemoryBusTaskAdmissionStore(),
                new InMemoryBusResponseProjectionStore())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsEphemeralA2aTaskStoreForReliableProfile() {
        assertThatThrownBy(() -> DurabilityGuard.verifyTaskStore(false, new InMemoryTaskStore()))
                .hasMessageContaining("TaskStore");
    }
}
