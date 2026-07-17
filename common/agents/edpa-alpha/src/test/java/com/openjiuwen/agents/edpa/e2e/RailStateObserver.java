/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.e2e;

import com.openjiuwen.core.foundation.llm.schema.UserMessage;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.AgentRail;
import com.openjiuwen.core.singleagent.rail.ModelCallInputs;
import com.openjiuwen.core.singleagent.rail.SteeringQueue;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Test-only diagnostic rail that non-destructively peeks whether the steering queue is
 * bound (wiring half) and whether a pushed convergence hint reaches the next model call's
 * messages (consumer half). Surfacing probe for the issue-#13 silent-drop on the
 * {@code invoke(taskString, null)} path — registered ONLY in the cognitive-loop e2e,
 * never in {@code EdpaAutoConfiguration} prod path.
 *
 * <p>Priority 5 so it orders before ExploreRail/ProactiveConvergenceRail. Peek-only: it
 * never binds, and the only steering read is drain-then-re-push (a faithful non-destructive
 * snapshot, since {@link SteeringQueue} exposes no {@code size()}/{@code peek()}).
 *
 * @since 2026-07
 */
public class RailStateObserver extends AgentRail {
    private static final Logger LOG = Logger.getLogger(RailStateObserver.class.getName());

    /** Fixed prefix of {@code ProactiveConvergenceRail.buildConvergenceFeedback}. */
    private static final String CONVERGENCE_HINT_PREFIX = "【主动收敛】";

    private final List<String> trace = new ArrayList<>();
    private boolean hintReachedAnyModelCall;

    /**
     * Creates a test-only observer registered at priority 5 so it orders before cognitive rails.
     */
    public RailStateObserver() {
        setPriority(5);
    }

    @Override
    public void beforeInvoke(AgentCallbackContext ctx) {
        record("beforeInvoke hasSteeringQueue=%s hasForceFinishRequest=%s",
                new Object[]{ctx.hasSteeringQueue(), ctx.hasForceFinishRequest()});
    }

    @Override
    public void afterModelCall(AgentCallbackContext ctx) {
        boolean bound = ctx.hasSteeringQueue();
        boolean hintThisRound = hintReachedThisRound(ctx);
        if (hintThisRound) {
            hintReachedAnyModelCall = true;
        }
        String snapshot = bound ? rePushSnapshot(ctx.getSteeringQueue()) : "(no queue)";
        record("afterModelCall hasSteeringQueue=%s steeringSnapshot=%s hintThisRound=%s hintReachedAny=%s",
                new Object[]{bound, snapshot, hintThisRound, hintReachedAnyModelCall});
    }

    /**
     * Reports whether the convergence hint reached any round's model messages (consumer-closed).
     */
    public boolean isHintReachedAnyModelCall() {
        return hintReachedAnyModelCall;
    }

    /**
     * Returns the ordered diagnostic lines collected for logging and assertion.
     */
    public List<String> getTrace() {
        return List.copyOf(trace);
    }

    private static boolean hintReachedThisRound(AgentCallbackContext ctx) {
        if (!(ctx.getInputs() instanceof ModelCallInputs in)) {
            return false;
        }
        List<Object> messages = in.getMessages();
        if (messages == null) {
            return false;
        }
        return messages.stream()
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .anyMatch(um -> um.getContentAsString().contains(CONVERGENCE_HINT_PREFIX));
    }

    private static String rePushSnapshot(SteeringQueue queue) {
        List<String> drained = queue.drainSteering();
        for (String s : drained) {
            queue.pushSteering(s);
        }
        return drained.toString();
    }

    private void record(String pattern, Object[] args) {
        String line = "[RailStateObserver] " + String.format(pattern, args);
        LOG.log(Level.INFO, "{0}", line);
        trace.add(line);
    }
}
