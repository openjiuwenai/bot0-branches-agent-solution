/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.stream;

import com.openjiuwen.service.bus.consumer.BusTaskProjectionCoordinator;
import com.openjiuwen.service.bus.consumer.model.BusResponseProjection;

import java.util.Map;

/**
 * Emits the control-plane STREAM_READY fact while keeping stream data outside the bus.
 *
 * @since 2026-07-22
 */
public final class StreamReadyProjector {
    private final BusTaskProjectionCoordinator coordinator;
    private final StreamReferenceService references;

    /**
     * Creates a new instance.
     *
     * @param coordinator
     *            the coordinator value
     * @param references
     *            the references value
     */
    public StreamReadyProjector(BusTaskProjectionCoordinator coordinator, StreamReferenceService references) {
        this.coordinator = coordinator;
        this.references = references;
    }

    /**
     * Performs the project operation.
     *
     * @param projection
     *            the projection value
     *
     * @return the operation result
     */
    public boolean project(BusResponseProjection projection) {
        String streamRef = references.issue(projection.tenantId(), projection.taskId(),
                projection.occurredAt().getEpochSecond());
        BusResponseProjection ready = new BusResponseProjection(projection.eventId(), projection.eventType(),
                projection.tenantId(), projection.correlationId(), projection.taskId(), projection.occurredAt(),
                Map.of("streamRef", streamRef), projection.traceId(), projection.sourceServiceId(),
                projection.targetServiceId(), projection.routeHandle(), projection.idempotencyKey(),
                projection.causationMessageId(), "STREAM_READY", projection.revision());
        return coordinator.project(ready);
    }
}
