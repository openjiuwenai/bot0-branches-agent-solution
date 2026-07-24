/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.bus.wait;

import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;
import com.openjiuwen.bus.forwarding.spi.InvocationResponseStatus;

/**
 * Maps a projection event to one of the five client-facing states (FEAT-012 §4.6).
 *
 * @since 2026-07-24
 */
public final class FiveStateFolder {

    /**
     * Folds a bus projection event type to the client-facing response status.
     *
     * @param eventType inbound projection event type
     * @return the corresponding invocation response status
     */
    public static InvocationResponseStatus fold(AgentBusEventType eventType) {
        return switch (eventType) {
            case INVOCATION_RESPONSE, INVOCATION_TERMINAL -> InvocationResponseStatus.COMPLETED_RESPONSE;
            case INVOCATION_ACCEPTED -> InvocationResponseStatus.ACCEPTED_WITH_TASK;
            case INVOCATION_REJECTED -> InvocationResponseStatus.REJECTED;
            case INVOCATION_FAILED -> InvocationResponseStatus.FAILED;
            case INVOCATION_STREAM_READY -> InvocationResponseStatus.STREAM_READY;
            default -> throw new IllegalArgumentException("Unexpected projection event: " + eventType);
        };
    }

    /**
     * @param s invocation response status
     * @return {@code true} when the status is a terminal fold (response/rejected/failed)
     */
    public static boolean isTerminal(InvocationResponseStatus s) {
        return s == InvocationResponseStatus.COMPLETED_RESPONSE
                || s == InvocationResponseStatus.REJECTED
                || s == InvocationResponseStatus.FAILED;
    }
}
