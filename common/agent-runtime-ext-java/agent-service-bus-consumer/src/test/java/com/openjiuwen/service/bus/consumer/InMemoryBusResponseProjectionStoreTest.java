/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.service.bus.consumer.model.BusResponseProjection;
import com.openjiuwen.service.bus.consumer.store.InMemoryBusResponseProjectionStore;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

/**
 * Tests InMemoryBusResponseProjectionStore behavior.
 *
 * @since 2026-07-22
 */
class InMemoryBusResponseProjectionStoreTest {
    @Test
    void appendIsTenantScopedAtomicAndEnforcesTaskOrder() {
        var store = new InMemoryBusResponseProjectionStore();
        assertThat(store.append(projection("tenant-a", "accepted", "ACCEPTED", 0))).isTrue();
        assertThat(store.append(projection("tenant-a", "accepted", "ACCEPTED", 0))).isFalse();
        assertThat(store.append(projection("tenant-b", "accepted", "ACCEPTED", 0))).isTrue();
        assertThat(store.append(projection("tenant-a", "input-1", "INPUT_REQUIRED", 1))).isTrue();
        assertThatThrownBy(() -> store.append(projection("tenant-a", "input-old", "INPUT_REQUIRED", 1)))
                .hasMessageContaining("revision");
        assertThat(store.append(projection("tenant-a", "terminal", "TERMINAL", 2))).isTrue();
        assertThatThrownBy(() -> store.append(projection("tenant-a", "late", "RESPONSE", 0)))
                .hasMessageContaining("TERMINAL");
        assertThat(store.pending("tenant-a", 10)).hasSize(3);
        assertThat(store.pending("tenant-b", 10)).hasSize(1);
    }

    private static BusResponseProjection projection(String tenant, String eventId, String kind, long revision) {
        return new BusResponseProjection(eventId, "INVOCATION_" + kind, tenant, "corr", "task", Instant.now(), Map.of(),
                null, null, null, null, null, "message", kind, revision);
    }
}
