/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.observability;

import com.openjiuwen.core.singleagent.agents.ReActAgent;

/**
 * One-call auto-wire for react-rails observability. Installs the default host-logger listener
 * and registers the passive {@link ObservingRail} so every {@code requestForceFinish} transfer
 * is auto-surfaced — zero per-rail manual fire.
 *
 * <p>Consumers call {@code ReactRailsObservability.install(agent)} once at startup; new rails
 * that only call {@code ctx.requestForceFinish(map)} are automatically observed (the
 * {@link ObservingRail} peeks the ctx and emits a {@link RailEvent.ForceFinishEvent}).
 *
 * @since 2026-07
 */
public final class ReactRailsObservability {
    private ReactRailsObservability() {
    }

    /**
     * Installs the default host-logger listener and the passive ObservingRail onto the agent.
     *
     * <p><b>MANDATORY</b>: consumers MUST call {@code install(agent)} exactly once at startup.
     * Skipping {@code install} silently breaks attribution of all forceFinish transfers — the
     * passive {@link ObservingRail} that reads {@code ctx.requestForceFinish(map)} is never
     * registered, so {@code forceFinish} still executes (the control-flow exit happens) but no
     * {@link RailEvent.ForceFinishEvent} is ever fired, and the host logger is never attached.
     * The agent runs to completion with zero observable rail state, masking every Exit-1/Exit-3
     * transition. There is no compile-time guard: the absence is silent at runtime.
     *
     * <p>Idempotent on the listener (re-install replaces the host listener) but not on the rail
     * (each call registers a fresh {@link ObservingRail} at {@link Integer#MIN_VALUE} priority,
     * which would double-fire events); call exactly once per agent.
     *
     * @param agent the ReActAgent to observe (not {@code null})
     */
    public static void install(ReActAgent agent) {
        RailTelemetry.install(new HostLoggerRailEventListener());
        agent.registerRail(new ObservingRail());
    }
}
