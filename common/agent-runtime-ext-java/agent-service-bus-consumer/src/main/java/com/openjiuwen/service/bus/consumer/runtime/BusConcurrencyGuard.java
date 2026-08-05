/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.runtime;

import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Provides bounded, runtime-neutral isolation for payload, A2A and projection calls.
 *
 * @since 2026-07-22
 */
public final class BusConcurrencyGuard {
    private final Semaphore payload;
    private final Semaphore bridge;
    private final Semaphore projection;
    private final ReentrantLock[] admissionStripes;

    /**
     * Creates a new instance.
     *
     * @param payloadMax
     *            the payloadMax value
     * @param bridgeMax
     *            the bridgeMax value
     * @param projectionMax
     *            the projectionMax value
     * @param stripes
     *            the stripes value
     */
    public BusConcurrencyGuard(int payloadMax, int bridgeMax, int projectionMax, int stripes) {
        if (payloadMax < 1 || bridgeMax < 1 || projectionMax < 1 || stripes < 1) {
            throw new IllegalArgumentException("concurrency limits must be positive");
        }
        payload = new Semaphore(payloadMax);
        bridge = new Semaphore(bridgeMax);
        projection = new Semaphore(projectionMax);
        admissionStripes = new ReentrantLock[stripes];
        for (int i = 0; i < stripes; i++) {
            admissionStripes[i] = new ReentrantLock();
        }
    }

    /**
     * Performs the call operation.
     *
     * @param lane
     *            the lane value
     * @param action
     *            the action value
     *
     * @return the operation result
     */
    public <T> T call(Lane lane, Supplier<T> action) {
        Semaphore semaphore = switch (lane) {
            case PAYLOAD -> payload;
            case BRIDGE -> bridge;
            case PROJECTION -> projection;
        };
        if (!semaphore.tryAcquire()) {
            throw new BusyException(lane.name());
        }
        try {
            return action.get();
        } finally {
            semaphore.release();
        }
    }

    /**
     * Performs the admission operation.
     *
     * @param tenantId
     *            the tenantId value
     * @param idempotencyKey
     *            the idempotencyKey value
     * @param action
     *            the action value
     *
     * @return the operation result
     */
    public <T> T admission(String tenantId, String idempotencyKey, Supplier<T> action) {
        int hash = 31 * tenantId.hashCode() + idempotencyKey.hashCode();
        ReentrantLock lock = admissionStripes[(hash & Integer.MAX_VALUE) % admissionStripes.length];
        if (!lock.tryLock()) {
            throw new BusyException("ADMISSION");
        }
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    /** Identifies an independently limited execution lane. */
    public enum Lane {
        PAYLOAD, BRIDGE, PROJECTION
    }

    /** Indicates that an execution lane has reached its configured capacity. */
    public static final class BusyException extends IllegalStateException {
        /**
         * Creates a lane-capacity exception.
         *
         * @param lane
         *            saturated lane
         */
        public BusyException(String lane) {
            super("BUS_BACKPRESSURE_" + lane);
        }
    }
}
