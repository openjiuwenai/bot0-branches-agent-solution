/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.relay;

import com.openjiuwen.service.bus.consumer.observability.BusConsumerTelemetry;
import com.openjiuwen.service.bus.consumer.port.BusResponseProjectionStore;

import java.util.Objects;

/**
 * Replays persisted projections left behind by a process crash before publish/mark-published.
 *
 * @since 2026-07-22
 */
public final class BusProjectionRepairer {
    private final BusResponseProjectionStore store;
    private final BusResponseRelay relay;
    private final String tenantId;
    private final BusConsumerTelemetry telemetry;

    /**
     * Creates a new instance.
     *
     * @param store
     *            the store value
     * @param relay
     *            the relay value
     * @param tenantId
     *            the tenantId value
     */
    public BusProjectionRepairer(BusResponseProjectionStore store, BusResponseRelay relay, String tenantId) {
        this(store, relay, tenantId, new BusConsumerTelemetry(null, null));
    }

    /**
     * Creates a new instance.
     *
     * @param store
     *            the store value
     * @param relay
     *            the relay value
     * @param tenantId
     *            the tenantId value
     * @param telemetry
     *            the telemetry value
     */
    public BusProjectionRepairer(BusResponseProjectionStore store, BusResponseRelay relay, String tenantId,
            BusConsumerTelemetry telemetry) {
        this.store = Objects.requireNonNull(store);
        this.relay = Objects.requireNonNull(relay);
        this.tenantId = Objects.requireNonNull(tenantId);
        this.telemetry = Objects.requireNonNull(telemetry);
    }

    /**
     * Performs the repair operation.
     *
     * @param limit
     *            the limit value
     *
     * @return the operation result
     */
    public int repair(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        var pending = store.pending(tenantId, limit);
        pending.forEach(relay::retry);
        telemetry.repair("projection", pending.isEmpty() ? "empty" : "scheduled");
        return pending.size();
    }
}
