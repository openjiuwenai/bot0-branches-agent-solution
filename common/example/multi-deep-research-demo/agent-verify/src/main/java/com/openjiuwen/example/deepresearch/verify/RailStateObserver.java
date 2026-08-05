/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.deepresearch.verify;

import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.AgentRail;
import com.openjiuwen.core.singleagent.rail.SteeringQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Diagnostic observer rail — used to prove PR #21 Bug #2 (A2A adapter drops
 * rail forceFinish metadata) and Bug #3 (pushSteering silent no-op on no-tool
 * ReActAgent) with observation-based evidence rather than source-level inference.
 *
 * <p>Registered <em>after</em> {@code CriteriaReplanBridgeRail} in
 * {@link VerifyAgentFactory#build(VerifyAgentProperties)}. Because callback
 * registration order is preserved (see startup log
 * {@code Registered callback: verify-agent_after_model_call -> ...}), this rail
 * runs after the bridge rail has decided one of its three exits.
 *
 * <p>What it logs, per iteration:
 * <ul>
 *   <li><b>EXIT_TAKEN=Exit-1-verified</b> + full forceFinish Map (proof rail
 *       emitted PASS terminal payload)</li>
 *   <li><b>EXIT_TAKEN=Exit-3-degraded</b> + full forceFinish Map (proof rail
 *       emitted FAIL/degraded terminal payload with unmet_criteria)</li>
 *   <li><b>EXIT_TAKEN=Exit-2-pushSteering</b> + drained-then-repushed hints
 *       (proof rail queued a correction hint; the fact next iteration never
 *       arrives is Bug #3)</li>
 *   <li><b>EXIT_TAKEN=NONE</b> — no rail action this iteration (should never
 *       happen for the verify-agent since final answers always trigger a gate)</li>
 * </ul>
 *
 * <p>Compare these logs against the outgoing A2A HTTP response body — every
 * key logged here that's missing from the response body was dropped by the
 * A2A adapter layer.
 *
 * @since 2026-07-21
 */
public final class RailStateObserver extends AgentRail {
    private static final Logger LOG = LoggerFactory.getLogger(RailStateObserver.class);

    @Override
    public void afterModelCall(AgentCallbackContext ctx) {
        boolean hasForceFinish = ctx.hasForceFinishRequest();
        Map<String, Object> ffMap = hasForceFinish ? ctx.getForceFinishRequest().getResult() : null;

        List<String> pendingSteering = peekSteeringNonDestructively(ctx);

        if (hasForceFinish) {
            String exit = inferExit(ffMap);
            LOG.info("[RailStateObserver] EXIT_TAKEN={} forceFinishMapKeys={} forceFinishMap={}",
                    exit, ffMap.keySet(), ffMap);
        } else if (!pendingSteering.isEmpty()) {
            LOG.info("[RailStateObserver] EXIT_TAKEN=Exit-2-pushSteering pendingSteeringSize={} pendingSteering={}",
                    pendingSteering.size(), pendingSteering);
        } else {
            LOG.info("[RailStateObserver] EXIT_TAKEN=NONE (no rail action recorded this iteration)");
        }
    }

    /**
     * SteeringQueue only exposes {@code drainSteering()} which is destructive.
     * Preserve original state by draining then re-pushing every item so
     * downstream {@code injectPendingSteering()} still sees them.
     *
     * @param ctx the agent callback context whose steering queue is being peeked
     * @return the current pending steering hints, or an empty list if no queue is attached
     */
    private static List<String> peekSteeringNonDestructively(AgentCallbackContext ctx) {
        if (!ctx.hasSteeringQueue()) {
            return List.of();
        }
        SteeringQueue queue = ctx.getSteeringQueue();
        List<String> drained = queue.drainSteering();
        for (String hint : drained) {
            queue.pushSteering(hint);
        }
        return drained;
    }

    private static String inferExit(Map<String, Object> map) {
        Object verified = map.get("criteria_verified");
        Object degraded = map.get("degraded");
        if (Boolean.TRUE.equals(verified) && !Boolean.TRUE.equals(degraded)) {
            return "Exit-1-verified";
        }
        if (Boolean.TRUE.equals(degraded)) {
            return "Exit-3-degraded";
        }
        return "unknown(mapKeys=" + map.keySet() + ")";
    }
}
