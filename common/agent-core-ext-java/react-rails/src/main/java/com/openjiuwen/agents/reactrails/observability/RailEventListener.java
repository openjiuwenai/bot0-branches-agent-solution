/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.observability;

/**
 * SPI for observing rail state transitions. Implementations forward {@link RailEvent}s to a
 * backend of choice (host logger, OTel span/metric, Micrometer, alerting, test collector).
 *
 * <p>Listeners are invoked by {@link RailTelemetry#fire(RailEvent)} inside a try/catch — a
 * throwing listener is isolated and never propagates back into the rail's bearing control flow.
 *
 * @since 2026-07
 */
@FunctionalInterface
public interface RailEventListener {
    /**
     * Receives one rail state-transition event.
     *
     * @param event the emitted rail event (never {@code null})
     */
    void onRailEvent(RailEvent event);
}
