/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.model;

/**
 * Carries BusConsumptionDecision data.
 *
 * @since 2026-07-22
 */
public record BusConsumptionDecision(Type type, String reason) {

    /**
     * Broker settlement decisions returned by the runtime consumer.
     */
    public enum Type {
        ACK_CONSUMED, ACK_REJECTED, RETRY
    }

    /**
     * Performs the consumed operation.
     *
     * @return the operation result
     */
    public static BusConsumptionDecision consumed() {
        return new BusConsumptionDecision(Type.ACK_CONSUMED, null);
    }

    /**
     * Performs the rejected operation.
     *
     * @param reason
     *            the reason value
     *
     * @return the operation result
     */
    public static BusConsumptionDecision rejected(String reason) {
        return new BusConsumptionDecision(Type.ACK_REJECTED, reason);
    }

    /**
     * Performs the retry operation.
     *
     * @param reason
     *            the reason value
     *
     * @return the operation result
     */
    public static BusConsumptionDecision retry(String reason) {
        return new BusConsumptionDecision(Type.RETRY, reason);
    }
}
