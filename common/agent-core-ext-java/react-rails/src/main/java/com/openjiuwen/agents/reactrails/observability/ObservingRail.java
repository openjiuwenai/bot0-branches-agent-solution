/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.observability;

import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.AgentRail;

import java.util.Map;

/**
 * Passive observer rail that auto-surfaces every {@code requestForceFinish} transfer without any
 * per-rail manual fire. Registered at the lowest priority ({@link Integer#MIN_VALUE}) so it runs
 * last in every hook fan-out (CallbackFramework sorts descending: higher first), peeking the ctx
 * after business rails have written their forceFinish request.
 *
 * <p>This is the "no-hand-fire" cure for A-class forceFinish transfers: a new rail only needs to
 * call {@code ctx.requestForceFinish(map)} and the ObservingRail emits the ForceFinishEvent — zero
 * manual fire code in the rail.
 *
 * <p><b>Attribution</b>: each rail sets {@link #SOURCE_RAIL_KEY} in its forceFinish result map
 * to its own class name (JVM-guaranteed unique). ObservingRail reads it for deterministic
 * attribution — no inference from keysets, no ambiguity. Rails that forget to set it fall back
 * to {@code railName="ObservingRail"} (honest degradation, not silent). C-class details (verify
 * violations / steering hint / replan count / device phase) remain the responsibility of
 * origin-point fire in each rail, since they bypass ctx.
 *
 * @since 2026-07
 */
public class ObservingRail extends AgentRail {
    /** Result-map key each rail sets to its own name for deterministic attribution. */
    public static final String SOURCE_RAIL_KEY = "source_rail";

    /** Result-map key used by react-rails to mark a verified terminal. */
    private static final String VERIFIED_KEY = "criteria_verified";

    /**
     * Construct at the lowest priority so this rail runs last in every hook fan-out.
     */
    public ObservingRail() {
        setPriority(Integer.MIN_VALUE);
    }

    @Override
    public void afterModelCall(AgentCallbackContext ctx) {
        observeForceFinish(ctx);
    }

    @Override
    public void afterToolCall(AgentCallbackContext ctx) {
        observeForceFinish(ctx);
    }

    @Override
    public void onToolException(AgentCallbackContext ctx) {
        observeForceFinish(ctx);
    }

    /**
     * Peeks the ctx for a pending forceFinish and, if present, emits a ForceFinishEvent.
     *
     * @param ctx the callback context after business rails have run
     */
    private void observeForceFinish(AgentCallbackContext ctx) {
        if (!ctx.hasForceFinishRequest()) {
            return;
        }
        Map<String, Object> result = ctx.getForceFinishRequest().getResult();
        boolean verified = result != null && Boolean.TRUE.equals(result.get(VERIFIED_KEY));
        String source = extractSource(result);
        RailTelemetry.current().fire(
                new RailEvent.ForceFinishEvent(source, verified, result));
    }

    /**
     * Extracts the source rail name from the result map, falling back to "ObservingRail"
     * when the rail did not set {@link #SOURCE_RAIL_KEY} (honest degradation, not silent).
     *
     * @param result the forceFinish result map
     * @return the source rail name, or "ObservingRail" if unattributed
     */
    private static String extractSource(Map<String, Object> result) {
        if (result == null) {
            return "ObservingRail";
        }
        Object src = result.get(SOURCE_RAIL_KEY);
        return src != null ? String.valueOf(src) : "ObservingRail";
    }
}
