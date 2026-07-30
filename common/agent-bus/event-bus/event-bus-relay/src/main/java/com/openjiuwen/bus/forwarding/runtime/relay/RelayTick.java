/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.bus.forwarding.runtime.relay;

/**
 * One relay tick — the consume→govern→re-produce step a driver periodically fires.
 * Functional seam over the {@code final} {@link EventBusRelayWorker} so the loop
 * ({@link RelayDispatchLoop}) and the driver ({@code RelayScheduler}) can be driven
 * with a fake, and so the worker stays the single concrete relay-tick implementation.
 *
 * <p>Authority: {@code docs/superpowers/specs/2026-07-15-relay-scheduler-design.md} §4.1.
 *
 * @since 0.1.0
 */
@FunctionalInterface
public interface RelayTick {
    /**
     * Fire one relay tick — consume inbound, govern, and re-produce.
     *
     * @param tenantId the tenant scope for this tick
     * @param nowMillisEpoch the tick-start instant (claim / governance time basis)
     * @param limit max records to process in this tick
     * @return the tick's aggregate result (claimed / produced / failed counts)
     */
    EventBusRelayWorker.RelayTickResult runOnce(String tenantId, long nowMillisEpoch, int limit);
}
