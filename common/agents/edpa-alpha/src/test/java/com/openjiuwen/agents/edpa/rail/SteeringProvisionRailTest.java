/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.rail;

import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.ModelCallInputs;
import com.openjiuwen.core.singleagent.rail.SteeringQueue;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Three-layer IFF bearing for {@link SteeringProvisionRail} — the issue-#13 fix that stops
 * {@code ctx.pushSteering} silently dropping on the {@code invoke(taskString, null)} path.
 *
 * <p><b>Layers covered here (deterministic, ctx-level, mutation-RED-able)</b>:
 * <ul>
 *   <li><b>Layer 1 — wiring</b>: {@code beforeInvoke} binds a queue on a bare ctx.
 *       Mutation: delete {@code ctx.bindSteeringQueue(...)} → {@code hasSteeringQueue} stays
 *       false → {@code assertThat(isTrue())} RED.</li>
 *   <li><b>Layer 2 — dispatch</b>: after provision, {@code pushSteering} lands in the drainable
 *       queue. Mutation: strip provision → queue null → {@code hasSteeringQueue} false → RED
 *       before the drain (and NPE on drain).</li>
 *   <li><b>Idempotency</b>: the {@code if(!hasSteeringQueue())} guard does not replace an
 *       already-bound queue. Mutation: delete the guard (always bind) → second provision
 *       replaces the queue → {@code isSameAs} RED.</li>
 * </ul>
 *
 * <p><b>Layer 3 — consumer injection (agent-core responsibility, out of EDPA bearing scope)</b>:
 * once provisioned (layer 1) and pushed (layer 2), the hint reaches the next round's model
 * messages via {@code ReActAgent.injectPendingSteering}@699 (drainSteering → UserMessage →
 * ModelContext.addMessages). That consumer is {@code agent-core-java} internal behavior —
 * bytecode-verified (frozen layer, not EDPA's to test). The real-LLM cognitive-loop e2e
 * confirms the fix at the wiring level ({@code hasSteeringQueue}: false→true, RED→GREEN);
 * convergence did not fire on that task so the end-to-end hint-in-messages was not directly
 * observed, but the chain provision→push→drain→inject is closed by layer 1/2 (deterministic)
 * + bytecode (agent-core consumer). A dedicated convergence-fire e2e to witness
 * hint-in-messages directly is deferred.
 *
 * @since 2026-07
 */
class SteeringProvisionRailTest {

    /** Layer 1 (wiring IFF): provision binds a queue on a bare ctx. */
    @Test
    void provisionBindsQueueOnBareCtx() {
        AgentCallbackContext ctx = bareCtx();
        assertThat(ctx.hasSteeringQueue()).isFalse(); // baseline: bare ctx (issue-#13 state)

        new SteeringProvisionRail().beforeInvoke(ctx);

        assertThat(ctx.hasSteeringQueue()).isTrue(); // mutation-RED anchor
    }

    /** Idempotency: re-provisioning does not replace an already-bound queue. */
    @Test
    void provisionIsIdempotentOnAlreadyBoundCtx() {
        AgentCallbackContext ctx = bareCtx();
        SteeringProvisionRail rail = new SteeringProvisionRail();
        rail.beforeInvoke(ctx);
        SteeringQueue first = ctx.getSteeringQueue();

        rail.beforeInvoke(ctx); // second provision — guard must no-op

        assertThat(ctx.getSteeringQueue()).isSameAs(first); // mutation-RED: delete guard → RED
    }

    /** Layer 2 (dispatch IFF): after provision, pushSteering lands in the drainable queue. */
    @Test
    void provisionedCtxPushSteeringIsDrainable() {
        AgentCallbackContext ctx = bareCtx();
        new SteeringProvisionRail().beforeInvoke(ctx);
        assertThat(ctx.hasSteeringQueue()).isTrue(); // mutation-RED anchor: strip provision → RED

        ctx.pushSteering("hint-A");
        ctx.pushSteering("hint-B");

        assertThat(ctx.getSteeringQueue().drainSteering()).containsExactly("hint-A", "hint-B");
    }

    /**
     * issue-#13 reproduction (the bug this rail fixes): a bare ctx with no provision silently
     * drops {@code pushSteering} — no queue is ever bound. Documents the RED state the rail
     * lifts to GREEN; observed live by {@code RailStateObserver} in the cognitive-loop e2e.
     */
    @Test
    void bareCtxPushSteeringSilentlyDrops() {
        AgentCallbackContext ctx = bareCtx(); // no provision

        ctx.pushSteering("hint-A"); // silent drop — returns without exception

        assertThat(ctx.hasSteeringQueue()).isFalse(); // queue never bound
    }

    private static AgentCallbackContext bareCtx() {
        return AgentCallbackContext.builder()
                .agent(new Object())
                .inputs(new ModelCallInputs())
                .build(); // no steeringQueue → hasSteeringQueue() false (issue-#13 baseline)
    }
}
