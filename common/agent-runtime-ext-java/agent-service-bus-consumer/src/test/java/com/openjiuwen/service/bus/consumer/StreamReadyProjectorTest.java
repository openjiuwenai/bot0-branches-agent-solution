/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.bus.consumer.model.BusResponseProjection;
import com.openjiuwen.service.bus.consumer.store.InMemoryBusResponseProjectionStore;
import com.openjiuwen.service.bus.consumer.stream.StreamReadyProjector;
import com.openjiuwen.service.bus.consumer.stream.StreamReferenceService;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tests creation of opaque STREAM_READY control-plane projections.
 *
 * @since 2026-07-22
 */
class StreamReadyProjectorTest {
    @Test
    void projectsOpaqueStreamReferenceWithoutStreamFrames() {
        var seen = new AtomicReference<BusResponseProjection>();
        var coordinator = new BusTaskProjectionCoordinator(new InMemoryBusResponseProjectionStore(), seen::set);
        BusResponseProjection projection = new BusResponseProjection("event-1", "A2A_STREAM_READY", "tenant-a", "corr",
                "task-1", Instant.ofEpochSecond(100), Map.of());
        new StreamReadyProjector(coordinator, new StreamReferenceService(60)).project(projection);
        assertThat(seen.get().data()).containsKey("streamRef");
        assertThat(seen.get().data()).doesNotContainKey("token");
    }
}
