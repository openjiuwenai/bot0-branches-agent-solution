/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.rail;

import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.AgentRail;
import com.openjiuwen.harness.task_loop.LoopQueues;

/**
 * Provisions a {@link LoopQueues} steering queue on the String-invoke path where
 * {@code ReActAgent.invoke(taskString, null)} never binds one — the issue-#13 structural gap.
 *
 * <p><b>Root cause this rail closes</b>: {@code ReActAgent.invoke} only copies a
 * {@code loop_queues} entry out of the input {@code Map} (offset 131, inside the Map branch
 * [48,137]). On the String branch (offset 45 {@code ifeq 140}) that copy is physically
 * unreachable, so {@code ctx.extra} stays an empty {@code HashMap}, {@code bindSteeringQueue}
 * reads {@code null}, and {@code ctx.steeringQueue} stays default {@code null}. Every later
 * {@code ctx.pushSteering} (ExploreRail / ProactiveConvergenceRail) then hits the
 * {@code if (steeringQueue == null) return;} guard and silently drops. Confirmed at runtime
 * by {@code RailStateObserver} ({@code hasSteeringQueue=false} for the whole trajectory).
 *
 * <p>This rail binds {@code new LoopQueues()} (the only public {@code SteeringQueue} impl) in
 * {@link #beforeInvoke}, which runs at invoke offset ~220 — AFTER the no-op
 * {@code bindSteeringQueue}@200 and BEFORE the first model call, so
 * {@code injectPendingSteering}@699 can drain the queue and steering actually reaches the next
 * round's messages.
 *
 * <p>Priority 1 → orders before ExploreRail/ProactiveConvergenceRail. Idempotent
 * ({@code if(!hasSteeringQueue())}) so it no-ops on the Map path where DeepAgent already
 * provisions {@code loop_queues}, and safe on re-entry. Read-only on agent-core-java: consumes
 * the public {@code bindSteeringQueue}/{@code hasSteeringQueue} setters and the public
 * {@code LoopQueues} no-arg ctor — does not modify any frozen layer.
 *
 * <p><b>Honest boundary</b>: this is an EDPA-local workaround, not a cure. The same structural
 * gap hits any String-invoke host (issue #13's {@code JiuwenCoreAgentHandler} A2A path). The
 * root fix belongs in {@code agent-core-java} ({@code ReActAgent.invoke} auto-provision) and is
 * deferred as a frozen-layer cross-repo decision.
 *
 * @since 2026-07
 */
public class SteeringProvisionRail extends AgentRail {
    /**
     * Construct with priority 1 (orders before all cognitive rails).
     */
    public SteeringProvisionRail() {
        setPriority(1);
    }

    /**
     * Binds a fresh steering queue when the host invoke path skipped provisioning.
     *
     * @param ctx the agent callback context carrying {@code extra} and the steering queue
     */
    @Override
    public void beforeInvoke(AgentCallbackContext ctx) {
        if (!ctx.hasSteeringQueue()) {
            ctx.bindSteeringQueue(new LoopQueues());
        }
    }
}
