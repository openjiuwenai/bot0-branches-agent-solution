/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.pev.agent;

import com.openjiuwen.agents.pev.kernel.NodeResult;
import com.openjiuwen.agents.pev.kernel.PevKernel;
import com.openjiuwen.agents.pev.kernel.ReplanAction;
import com.openjiuwen.agents.pev.kernel.RootCause;
import com.openjiuwen.core.session.Session;
import com.openjiuwen.core.session.stream.StreamMode;
import com.openjiuwen.core.singleagent.BaseAgent;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.AgentCallbackEvent;
import com.openjiuwen.core.singleagent.schema.AgentCard;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PEV agent — a general agent-service-app on agent-core-java, running the closed loop
 * Plan → Execute → Verify → Diagnose → Dispatch inside {@link #invoke}.
 *
 * <p>Design:
 * <ul>
 *   <li><b>Self-contained control flow</b>: {@code invoke} runs the whole PEV loop; the
 *       serving handler (when wired) just forwards requests to {@code agent.invoke}. PEV
 *       control flow is fully internal to the agent, not split across a handler override.</li>
 *   <li><b>Three injected stages</b>: {@link PevComponents.Planner/Executor/Verifier} —
 *       mockable per-stage for control-flow tests.</li>
 *   <li><b>Rail seam at phase boundaries</b>: {@link AgentCallbackEvent#BEFORE_INVOKE} /
 *       {@code AFTER_MODEL_CALL} (plan) / {@code AFTER_TOOL_CALL} (execute) /
 *       {@code AFTER_INVOKE} (terminal). Any {@link com.openjiuwen.core.singleagent.rail.AgentRail}
 *       registered via {@link #registerRail} observes PEV phases — the seam for composing
 *       cognitive rails (Beta) and autoharness rails.</li>
 *   <li><b>Sealed dispatch</b>: {@link PevKernel#toReplanAction} output switched over
 *       exhaustively; dropping a case arm fails to compile.</li>
 *   <li><b>terminalGuard</b>: caps the verify loop at {@code maxRetries}.</li>
 * </ul>
 *
 * <p>Honest boundaries (single-agent, structurally deferred):
 * <ul>
 *   <li>{@code stream} degrades to a single chunk after one invoke — real streaming needs
 *       the Executor to expose a {@code Flux}.</li>
 *   <li>LocalReplan re-executes failed nodes within the same agent (whole-step retry with
 *       feedback); precise per-node re-execution needs a DAG/multi-agent runtime.</li>
 * </ul>
 *
 * @since 2026-07
 */
public class PEVAgent extends BaseAgent {
    private static final String LINE_SEPARATOR = System.lineSeparator();

    private final PevComponents.Planner planner;
    private final PevComponents.Executor executor;
    private final PevComponents.Verifier verifier;
    private PevConfig config;

    public PEVAgent(AgentCard card, PevComponents.Planner planner, PevComponents.Executor executor,
            PevComponents.Verifier verifier) {
        super(card);
        this.planner = planner;
        this.executor = executor;
        this.verifier = verifier;
        this.config = PevConfig.defaults();
    }

    @Override
    public BaseAgent configure(Object cfg) {
        if (cfg instanceof PevConfig pc) {
            this.config = pc;
        }
        return this;
    }

    @Override
    public Object getConfig() {
        return config;
    }

    @Override
    public Object invoke(Object input, Session session) {
        String userInput = toUserInput(input);
        fire(AgentCallbackEvent.BEFORE_INVOKE, session, input);

        // Plan
        PevComponents.Plan plan = planner.plan(userInput);
        fire(AgentCallbackEvent.AFTER_MODEL_CALL, session, plan);

        // Execute + Verify-Diagnose-Dispatch loop
        Map<String, NodeResult> completed = new LinkedHashMap<>();
        boolean[] terminal = {false};
        boolean[] verifyPassed = {false};
        runVerifyLoop(userInput, plan, new VerifyLoopState(completed, terminal, verifyPassed, 0, session));

        String output = assembleOutput(completed);
        fire(AgentCallbackEvent.AFTER_INVOKE, session, output);
        return output;
    }

    @Override
    public Iterator<Object> stream(Object input, Session session, List<StreamMode> modes) {
        // Honest boundary: real streaming needs Executor to emit a Flux of node results.
        return List.of(invoke(input, session)).iterator();
    }

    private void runVerifyLoop(String userInput, PevComponents.Plan plan, VerifyLoopState state) {
        if (state.terminal[0]) {
            return;
        }

        Map<String, NodeResult> stepResults = executeStep(plan, state);
        Set<String> failedToolNodes = failedToolNodes(stepResults);
        PevKernel.VerifyResult vr = verify(userInput, state.completed);

        if (vr.isPassed() && !vr.hasParseFailure()) {
            state.verifyPassed[0] = true;
            state.terminal[0] = true;
            return;
        }

        RootCause cause = PevKernel.diagnoseRootCause(vr, failedToolNodes, state.completed);
        ReplanAction action = PevKernel.toReplanAction(cause, vr.feedback(), vr.failedNodes());
        dispatchReplanAction(userInput, plan, state, action);
    }

    private Map<String, NodeResult> executeStep(PevComponents.Plan plan, VerifyLoopState state) {
        Map<String, NodeResult> stepResults = executor.execute(plan.nodes());
        fire(AgentCallbackEvent.AFTER_TOOL_CALL, state.session, stepResults);
        state.completed.putAll(stepResults);
        return stepResults;
    }

    private PevKernel.VerifyResult verify(String userInput, Map<String, NodeResult> completed) {
        try {
            PevKernel.VerifyResult result = verifier.verify(userInput, completed);
            if (result == null) {
                return new PevKernel.VerifyResult(false, Set.of(), "verifier returned null", true);
            }
            return result;
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return new PevKernel.VerifyResult(false, Set.of(), "verifier threw: " + ex.getMessage(), false, true);
        }
    }

    private static Set<String> failedToolNodes(Map<String, NodeResult> stepResults) {
        Set<String> failedToolNodes = new HashSet<>();
        for (Map.Entry<String, NodeResult> e : stepResults.entrySet()) {
            if (e.getValue() instanceof NodeResult.DeviceFailure) {
                failedToolNodes.add(e.getKey());
            }
        }
        return failedToolNodes;
    }

    private void dispatchReplanAction(String userInput, PevComponents.Plan plan, VerifyLoopState state,
            ReplanAction action) {
        if (state.retryCount >= config.maxRetries && !(action instanceof ReplanAction.AcceptPartial)) {
            state.terminal[0] = true;
            return;
        }
        if (action instanceof ReplanAction.AcceptPartial) {
            state.terminal[0] = true;
            return;
        }
        if (action instanceof ReplanAction.LocalReplan localReplan) {
            handleLocalReplan(userInput, plan, state, localReplan);
            return;
        }
        if (action instanceof ReplanAction.GlobalReplan globalReplan) {
            PevComponents.Plan newPlan = planner.plan(userInput + " [correction: " + globalReplan.feedback() + "]");
            state.completed.clear();
            runVerifyLoop(userInput, newPlan, state.nextRetry());
            return;
        }
        throw new IllegalArgumentException("Unsupported replan action: " + action);
    }

    private void handleLocalReplan(String userInput, PevComponents.Plan plan, VerifyLoopState state,
            ReplanAction.LocalReplan localReplan) {
        List<PevComponents.PlanNode> redo = new ArrayList<>();
        Set<String> failed = localReplan.failedNodes() == null ? Set.of() : localReplan.failedNodes();
        for (PevComponents.PlanNode n : plan.nodes()) {
            if (failed.contains(n.id())) {
                redo.add(n);
                state.completed.remove(n.id());
            }
        }
        if (!redo.isEmpty()) {
            runVerifyLoop(userInput, new PevComponents.Plan(plan.goal() + " (局部重做)", redo), state.nextRetry());
        }
    }

    private void fire(AgentCallbackEvent event, Session session, Object payload) {
        AgentCallbackContext.AgentCallbackContextBuilder b = AgentCallbackContext.builder().agent(this).event(event)
                .session(session);
        if (payload != null) {
            b.extra(Map.of("payload", payload));
        }
        fireCallbackEvent(event, b.build());
    }

    private static String assembleOutput(Map<String, NodeResult> results) {
        if (results.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, NodeResult> e : results.entrySet()) {
            String display = (e.getValue() instanceof NodeResult.Success s)
                    ? String.valueOf(s.value())
                    : "[" + e.getValue().getClass().getSimpleName() + "]";
            sb.append(e.getKey()).append(": ").append(display).append(LINE_SEPARATOR);
        }
        return sb.toString().trim();
    }

    private static String toUserInput(Object input) {
        if (input == null) {
            return "";
        }
        if (input instanceof String s) {
            return s;
        }
        if (input instanceof Map<?, ?> m) {
            Object u = m.get("userInput");
            if (u != null) {
                return String.valueOf(u);
            }
        }
        return String.valueOf(input);
    }

    /**
     * PEV config — narrow, only the switches the control flow actually reads.
     */
    public static final class PevConfig {
        /**
         * Maximum replan retries before terminal degrade.
         */
        public final int maxRetries;

        public PevConfig(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        /**
         * Create default PEV config.
         *
         * @return default config
         */
        public static PevConfig defaults() {
            return new PevConfig(2);
        }
    }

    private static final class VerifyLoopState {
        private final Map<String, NodeResult> completed;
        private final boolean[] terminal;
        private final boolean[] verifyPassed;
        private final int retryCount;
        private final Session session;

        private VerifyLoopState(Map<String, NodeResult> completed, boolean[] terminal, boolean[] verifyPassed,
                int retryCount, Session session) {
            this.completed = completed;
            this.terminal = terminal;
            this.verifyPassed = verifyPassed;
            this.retryCount = retryCount;
            this.session = session;
        }

        private VerifyLoopState nextRetry() {
            return new VerifyLoopState(completed, terminal, verifyPassed, retryCount + 1, session);
        }
    }
}
