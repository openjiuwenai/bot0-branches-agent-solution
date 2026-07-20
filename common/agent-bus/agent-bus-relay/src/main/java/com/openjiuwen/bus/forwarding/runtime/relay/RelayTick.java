package com.openjiuwen.bus.forwarding.runtime.relay;

/**
 * One relay tick — the consume→govern→re-produce step a driver periodically fires.
 * Functional seam over the {@code final} {@link EventBusRelayWorker} so the loop
 * ({@link RelayDispatchLoop}) and the driver ({@code RelayScheduler}) can be driven
 * with a fake, and so the worker stays the single concrete relay-tick implementation.
 *
 * <p>Authority: {@code docs/superpowers/specs/2026-07-15-relay-scheduler-design.md} §4.1.
 */
@FunctionalInterface
public interface RelayTick {
    EventBusRelayWorker.RelayTickResult runOnce(String tenantId, long nowMillisEpoch, int limit);
}
