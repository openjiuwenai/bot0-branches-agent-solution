/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.pev.observability;

/**
 * Consumer of {@link PevTrace} (PEV observability sink). Instance-scoped (a PEVAgent field), not a
 * process-wide static — see {@link PevTrace} scope rationale.
 *
 * <p>A host wires a sink (host-logger / OTel exporter / Micrometer / test collector) via the PEVAgent
 * constructor; the default is {@link #noop()} (explicit opt-in, avoids the silent-install footgun a
 * mandatory-install entry point would create).
 *
 * <p>Listener isolation: {@code PEVAgent.emitTrace} wraps {@link #onTrace(PevTrace)} in a FutureTask
 * bridge so a throwing sink cannot kill the invoke control loop (mirrors react-rails
 * {@code RailTelemetry.invokeIsolated} — a general listener-isolation mechanism, also dodges the
 * broad-catch rule).
 *
 * @since 2026-07
 */
@FunctionalInterface
public interface PevTraceSink {
    /**
     * Receive one trace (the terminal byproduct of one invoke).
     *
     * @param trace the completed trace (never {@code null})
     */
    void onTrace(PevTrace trace);

    /**
     * No-op sink — discards every trace. The PEVAgent default.
     *
     * @return a shared no-op sink
     */
    static PevTraceSink noop() {
        return trace -> {
            // intentionally empty — explicit opt-in for trace consumption
        };
    }
}
