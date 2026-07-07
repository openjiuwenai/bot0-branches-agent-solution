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
 
  * @since 2026-07*/
public class PEVAgent extends BaseAgent {

    /** PEV config — narrow, only the switches the control flow actually reads. */
    public static final class PevConfig {
        public final int maxRetries;
        public PevConfig(int maxRetries) {
            this.maxRetries = maxRetries;
        }
        public static PevConfig defaults() {
            return new PevConfig(2);
        }
    }

    private final PevComponents.Planner planner;
    private final PevComponents.Executor executor;
    private final PevComponents.Verifier verifier;
    private PevConfig config;

    public PEVAgent(AgentCard card,
                    PevComponents.Planner planner,
                    PevComponents.Executor executor,
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
        runVerifyLoop(userInput, plan, completed, terminal, verifyPassed, 0, session);

        String output = assembleOutput(completed);
        fire(AgentCallbackEvent.AFTER_INVOKE, session, output);
        return output;
    }

    @Override
    public Iterator<Object> stream(Object input, Session session, List<StreamMode> modes) {
        // Honest boundary: real streaming needs Executor to emit a Flux of node results.
        return List.of(invoke(input, session)).iterator();
    }


    private void runVerifyLoop(String userInput, PevComponents.Plan plan,
                               Map<String, NodeResult> completed,
                               boolean[] terminal, boolean[] verifyPassed,
                               int retryCount, Session session) {
        if (terminal[0]) {
            return;
        }

        // Execute
        Map<String, NodeResult> stepResults = executor.execute(plan.nodes());
        fire(AgentCallbackEvent.AFTER_TOOL_CALL, session, stepResults);
        completed.putAll(stepResults);

        Set<String> failedToolNodes = new HashSet<>();
        for (Map.Entry<String, NodeResult> e : stepResults.entrySet()) {
            if (e.getValue() instanceof NodeResult.DeviceFailure) {
                failedToolNodes.add(e.getKey());
            }
        }

        // Verify (a throw / null → parseFailure signal → PerceptionUnreliable)
        PevKernel.VerifyResult vr;
        try {
            vr = verifier.verify(userInput, completed);
            if (vr == null) {
                vr = new PevKernel.VerifyResult(false, Set.of(), "verifier returned null", true);
            }
        } catch (RuntimeException rex) {
            vr = new PevKernel.VerifyResult(false, Set.of(), "verifier threw: " + rex.getMessage(), false, true);
        }

        if (vr.passed() && !vr.parseFailure()) {
            verifyPassed[0] = true;
            terminal[0] = true;
            return;
        }

        // Diagnose + Dispatch
        RootCause cause = PevKernel.diagnoseRootCause(vr, failedToolNodes, completed);
        ReplanAction action = PevKernel.toReplanAction(cause, vr.feedback(), vr.failedNodes());

        // terminalGuard: cap retries when still needing replan
        if (retryCount >= config.maxRetries && !(action instanceof ReplanAction.AcceptPartial)) {
            terminal[0] = true;
            return;
        }

        switch (action) {
            case ReplanAction.AcceptPartial ignored -> terminal[0] = true;
            case ReplanAction.LocalReplan lr -> {
                List<PevComponents.PlanNode> redo = new ArrayList<>();
                Set<String> failed = lr.failedNodes() == null ? Set.of() : lr.failedNodes();
                for (PevComponents.PlanNode n : plan.nodes()) {
                    if (failed.contains(n.id())) {
                        redo.add(n);
                        completed.remove(n.id());
                    }
                }
                if (!redo.isEmpty()) {
                    runVerifyLoop(userInput,
                            new PevComponents.Plan(plan.goal() + " (局部重做)", redo),
                            completed, terminal, verifyPassed, retryCount + 1, session);
                }
            }
            case ReplanAction.GlobalReplan gr -> {
                PevComponents.Plan newPlan = planner.plan(userInput + " [correction: " + gr.feedback() + "]");
                completed.clear();
                runVerifyLoop(userInput, newPlan, completed, terminal, verifyPassed, retryCount + 1, session);
            }
        }
    }


    private void fire(AgentCallbackEvent event, Session session, Object payload) {
        AgentCallbackContext.AgentCallbackContextBuilder b = AgentCallbackContext.builder()
                .agent(this)
                .event(event)
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
            sb.append(e.getKey()).append(": ").append(display).append("\n");
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
}