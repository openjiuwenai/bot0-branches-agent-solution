/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.pev.agent;

import com.openjiuwen.agents.pev.kernel.NodeResult;
import com.openjiuwen.agents.pev.kernel.PevKernel;
import com.openjiuwen.agents.pev.kernel.ReplanAction;
import com.openjiuwen.agents.pev.kernel.RootCause;
import com.openjiuwen.agents.pev.observability.PevTrace;
import com.openjiuwen.agents.pev.observability.PevTraceSink;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

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
 *   <li><b>Closed dispatch</b>: {@link PevKernel#toReplanAction} returns a sealed action
 *       hierarchy whose currently permitted variants are handled explicitly.</li>
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
    private final PevTraceSink sink;
    private PevConfig config;

    public PEVAgent(AgentCard card, PevComponents.Planner planner, PevComponents.Executor executor,
            PevComponents.Verifier verifier) {
        this(card, planner, executor, verifier, PevTraceSink.noop());
    }

    /**
     * Constructs a PEVAgent with an explicit trace sink (host-logger / OTel / test collector).
     * Default sink is {@link PevTraceSink#noop()} (explicit opt-in — avoids the silent-install
     * footgun a mandatory-install entry point would create).
     *
     * @param card agent card
     * @param planner plan stage
     * @param executor execute stage
     * @param verifier verify stage
     * @param sink trace sink (null → noop)
     */
    public PEVAgent(AgentCard card, PevComponents.Planner planner, PevComponents.Executor executor,
            PevComponents.Verifier verifier, PevTraceSink sink) {
        super(card);
        this.planner = planner;
        this.executor = executor;
        this.verifier = verifier;
        this.sink = sink == null ? PevTraceSink.noop() : sink;
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

        // Execute + Verify-Diagnose-Dispatch loop, observed as a deterministic phase trace.
        Map<String, NodeResult> completed = new LinkedHashMap<>();
        boolean[] terminal = {false};
        List<PevTrace.Phase> phases = new ArrayList<>();
        phases.add(new PevTrace.Planned(plan));
        PevTrace.TerminalReason[] terminalReason = {null};
        runVerifyLoop(userInput, plan,
                new VerifyLoopState(completed, terminal, phases, terminalReason, 0, session));

        String output = assembleOutput(completed);
        PevTrace trace = new PevTrace(List.copyOf(phases), terminalReason[0],
                (int) phases.stream().filter(p -> p instanceof PevTrace.Verified).count());
        emitTrace(trace, session, output);
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
        state.phases.add(new PevTrace.Executed(stepResults));
        Set<String> failedToolNodes = failedToolNodes(stepResults);
        PevKernel.VerifyResult vr = verify(userInput, state.completed);
        state.phases.add(new PevTrace.Verified(vr));

        if (vr.isPassed() && !vr.hasParseFailure()) {
            state.terminalReason[0] = PevTrace.TerminalReason.PASSED;
            state.terminal[0] = true;
            return;
        }

        RootCause cause = PevKernel.diagnoseRootCause(vr, failedToolNodes, state.completed);
        state.phases.add(new PevTrace.Diagnosed(cause));
        ReplanAction action = PevKernel.toReplanAction(cause, vr.feedback(), vr.failedNodes());
        state.phases.add(new PevTrace.Dispatched(action));
        dispatchReplanAction(userInput, plan, state, action);
    }

    private Map<String, NodeResult> executeStep(PevComponents.Plan plan, VerifyLoopState state) {
        Map<String, NodeResult> stepResults = executor.execute(plan.nodes());
        fire(AgentCallbackEvent.AFTER_TOOL_CALL, state.session, stepResults);
        state.completed.putAll(stepResults);
        return stepResults;
    }

    private PevKernel.VerifyResult verify(String userInput, Map<String, NodeResult> completed) {
        CompletableFuture<PevKernel.VerifyResult> verification = CompletableFuture
                .supplyAsync(() -> verifier.verify(userInput, completed), Runnable::run);
        try {
            PevKernel.VerifyResult result = verification.join();
            if (result == null) {
                return new PevKernel.VerifyResult(false, Set.of(), "verifier returned null", true);
            }
            return result;
        } catch (CompletionException ex) {
            Throwable failure = ex.getCause() == null ? ex : ex.getCause();
            return handleVerifierFailure(failure);
        }
    }

    private static PevKernel.VerifyResult handleVerifierFailure(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            return new PevKernel.VerifyResult(false, Set.of(), "verifier threw: " + runtimeFailure.getMessage(), false,
                    true);
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("Verifier violated its unchecked-exception contract", failure);
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
            state.terminalReason[0] = PevTrace.TerminalReason.MAX_RETRIES_EXCEEDED;
            state.terminal[0] = true;
            return;
        }
        if (action instanceof ReplanAction.AcceptPartial) {
            state.terminalReason[0] = PevTrace.TerminalReason.ACCEPT_PARTIAL;
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
        } else {
            // Degenerate path (pre-existing edge case, now honestly observable): the verifier
            // reported failed nodes not present in the plan (verifier/executor contract mismatch),
            // so LocalReplan has nothing to redo and the loop falls through without a clean
            // PASSED/ACCEPT_PARTIAL/MAX_RETRIES terminal. Mark INCONCLUSIVE so the trace truthfully
            // reports the unterminated state instead of null (violating the PevTrace contract).
            // Pure observability — does not change invoke's output (terminal[0] is not re-checked
            // after runVerifyLoop returns).
            state.terminalReason[0] = PevTrace.TerminalReason.INCONCLUSIVE;
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

    /**
     * Emit the terminal trace: fan to the sink (FutureTask-isolated so a throwing sink cannot kill
     * the invoke control loop — mirrors react-rails {@code RailTelemetry.invokeIsolated}; also
     * dodges the broad-catch rule), then fire AFTER_INVOKE with the trace as an additive typed
     * {@code "trace"} key alongside {@code "payload"} (rails reading only {@code "payload"} are
     * unaffected; the typed key is the seam for future trace-consuming rails).
     *
     * @param trace the completed invoke trace
     * @param session invoke session
     * @param output assembled output (the existing "payload" value, unchanged)
     */
    private void emitTrace(PevTrace trace, Session session, String output) {
        FutureTask<Void> sinkTask = new FutureTask<>(() -> sink.onTrace(trace), null);
        sinkTask.run();
        try {
            sinkTask.get();
        } catch (InterruptedException | ExecutionException ignored) {
            // sink threw or was interrupted — isolated, never reaches the bearing control flow
        }
        AgentCallbackContext ctx = AgentCallbackContext.builder().agent(this)
                .event(AgentCallbackEvent.AFTER_INVOKE).session(session)
                .extra(Map.of("payload", output, "trace", trace)).build();
        fireCallbackEvent(AgentCallbackEvent.AFTER_INVOKE, ctx);
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
        private final List<PevTrace.Phase> phases;
        private final PevTrace.TerminalReason[] terminalReason;
        private final int retryCount;
        private final Session session;

        private VerifyLoopState(Map<String, NodeResult> completed, boolean[] terminal,
                List<PevTrace.Phase> phases, PevTrace.TerminalReason[] terminalReason, int retryCount,
                Session session) {
            this.completed = completed;
            this.terminal = terminal;
            this.phases = phases;
            this.terminalReason = terminalReason;
            this.retryCount = retryCount;
            this.session = session;
        }

        private VerifyLoopState nextRetry() {
            return new VerifyLoopState(completed, terminal, phases, terminalReason, retryCount + 1, session);
        }
    }
}
