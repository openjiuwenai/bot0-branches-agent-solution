/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.rail;

import com.openjiuwen.agents.edpa.explore.ExplorationResult;
import com.openjiuwen.agents.edpa.explore.ExploreBudget;
import com.openjiuwen.agents.edpa.explore.Explorer;
import com.openjiuwen.agents.reactrails.observability.RailEvent;
import com.openjiuwen.agents.reactrails.observability.RailTelemetry;
import com.openjiuwen.agents.reactrails.state.RailInvocationState;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.BaseMessage;
import com.openjiuwen.core.foundation.llm.schema.UserMessage;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.AgentRail;
import com.openjiuwen.core.singleagent.rail.ModelCallInputs;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * EDPA Explore-phase rail — injects Explorer findings into the next LLM
 * iteration via {@link AgentCallbackContext#pushSteering}.
 *
 * <p><b>Channel separation (zero-conflict with PreCompletionChecklistRail)</b>:
 * this rail NEVER calls {@code SystemPromptInjectingModel.setInjectionMode} or
 * {@code setPhaseOverride}. Those static channels are owned exclusively by
 * PreCompletionChecklistRail (priority 80). ExploreRail (priority 90)
 * communicates findings solely through the per-instance steering queue, which
 * is orthogonal to the static SystemMessage-replacement path.
 *
 * <p><b>1-round-delay alignment</b>: findings are pushed in
 * {@link #afterModelCall} so they are consumed by
 * {@code injectPendingSteering} (offset 675) at the START of the next
 * iteration, before {@code callModel} (offset 687). Pushing in beforeModelCall
 * would miss the 675 site (it has already run for the current iteration) and
 * delay by 2 rounds.
 *
 * <p><b>Honest boundary (铁律①)</b>: only pushes on tool-call rounds within
 * the explore window. Final-answer rounds are skipped — ReActAgent's own loop-limit
 * steering cannot redirect a loop terminating at offset 844/857.
 *
 * <p><b>IFF 契约</b>:
 * <ul>
 *   <li>Strip {@code ctx.pushSteering(formatted)} → steering queue empty next
 *       round → mock-assert LLM input contains no findings → RED.</li>
 *   <li>Strip {@code explorer.explore(...)} call → ExplorationResult empty →
 *       no push → RED.</li>
 * </ul>
 *
 * @since 2026-07
 */
public class ExploreRail extends AgentRail {
    /** Extra-context key for explore round observation (non-control-flow). */
    public static final String EXTRA_EXPLORE_ROUND = "edpa_explore_round";

    /**
     * Priority 90 — fires BEFORE PreCompletionChecklistRail(80) in both hooks.
     * Higher value = earlier fire (verified against VotingCritic=100 >
     * Checklist=80 > Stagnation=50).
     */
    private static final int PRIORITY = 90;

    private static final String LINE_SEPARATOR = System.lineSeparator();

    private final Explorer explorer;

    private final ExploreBudget budget;

    /** Per-invocation state key (4-lens M6: migrated from bare instance fields to RailInvocationState). */
    private final String stateKey = RailInvocationState.newKey(ExploreRail.class);

    /**
     * Constructs an Explore-phase rail.
     *
     * @param explorer the Explore-phase SPI implementation (e.g. LlmExplorer)
     * @param budget exploration budget (max rounds / subagents / timeout)
     */
    public ExploreRail(Explorer explorer, ExploreBudget budget) {
        if (explorer == null) {
            throw new IllegalArgumentException("explorer must not be null");
        }
        if (budget == null) {
            throw new IllegalArgumentException("budget must not be null");
        }
        this.explorer = explorer;
        this.budget = budget;
        setPriority(PRIORITY);
    }

    // ============================================================
    // beforeModelCall: light coordination ONLY — no static-channel writes
    // ============================================================

    /**
     * Does NOT call setInjectionMode / setPhaseOverride (owned by
     * PreCompletionChecklistRail). Only publishes explore progress to
     * ctx.getExtra for downstream observation.
     *
     * <p>Reason: PreCompletionChecklistRail(priority 80) fires AFTER this rail
     * in beforeModelCall. If ExploreRail(90) wrote the static mode here, the
     * checklist rail would overwrite it (last-writer-wins) — a channel
     * collision. ExploreRail communicates solely via the steering queue in
     * afterModelCall, which is orthogonal to the static SystemMessage path.
     *
     * @param ctx callback context for the pending model call
     */
    @Override
    public void beforeModelCall(AgentCallbackContext ctx) {
        ctx.getExtra().put(EXTRA_EXPLORE_ROUND, state(ctx).exploreRound);
        // Intentionally no setInjectionMode here — zero conflict with priority-80 rail.
    }

    // ============================================================
    // afterModelCall: EXPLORE + pushSteering (main hook)
    // ============================================================

    /**
     * After the model responds, if still within the explore window AND this
     * was a tool-call round, run the Explorer and push findings as steering
     * for the next iteration.
     *
     * <p>Final-answer rounds are skipped (steering cannot redirect a
     * terminating loop — proven by ReActAgent's max-iterations guard).
     *
     * <p>Timing (bytecode-verified against agent-core-java 0.1.12):
     * <ul>
     *   <li>This hook fires after {@code callModel}(offset 687) returned.</li>
     *   <li>{@code consumeForceFinish}(offset 700) has NOT yet run.</li>
     *   <li>Next iteration: {@code injectPendingSteering}(offset 675) drains
     *       the steering queue into a UserMessage BEFORE
     *       {@code callModel}(offset 687) — so the pushed findings reach the
     *       LLM exactly one round later.</li>
     * </ul>
     *
     * @param ctx callback context carrying model-call inputs
     */
    @Override
    public void afterModelCall(AgentCallbackContext ctx) {
        Optional<ModelCallInputs> inputs = extractModelCallInputs(ctx);
        if (inputs.isEmpty()) {
            return;
        }
        Optional<AssistantMessage> msg = extractAssistantMessage(inputs.get());
        if (msg.isEmpty()) {
            return;
        }

        InvocationState s = state(ctx);

        // [1][2] Skip non-explorable rounds (window exhausted or final-answer)
        if (!isExplorableRound(msg.get(), s)) {
            return;
        }

        // [3] Resolve user input once (messages[1] = UserMessage(initial query),
        //     per HistoryCompressorRail convention)
        String userInput = resolveUserInput(ctx, s);
        if (userInput.isEmpty()) {
            return;
        }

        // [4] Explore — may use LLM/tools/subagents within budget
        ExplorationResult result = exploreSafely(userInput);
        if (!hasFindings(result)) {
            return;
        }

        s.exploreRound++;

        // [5] Push findings into steering queue → consumed by
        //     injectPendingSteering(offset 675) NEXT iteration
        String findings = formatFindings(result);
        ctx.pushSteering(findings);
        RailTelemetry.current().fire(new RailEvent.SteeringEvent("ExploreRail",
                "EXPLORE_FINDINGS", findings.substring(0, Math.min(80, findings.length())),
                ctx.hasSteeringQueue()));
    }

    /**
     * Extracts the {@link ModelCallInputs} payload from the callback context.
     *
     * @param ctx callback context for the completed model call
     * @return an Optional containing the model-call inputs, or empty when the
     *         payload type is unexpected
     */
    private Optional<ModelCallInputs> extractModelCallInputs(AgentCallbackContext ctx) {
        if (ctx.getInputs() instanceof ModelCallInputs inputs) {
            return Optional.of(inputs);
        }
        return Optional.empty();
    }

    /**
     * Extracts the {@link AssistantMessage} produced by the model.
     *
     * @param inputs model-call inputs carrying the response
     * @return an Optional containing the assistant message, or empty when the
     *         response is not an AssistantMessage
     */
    private Optional<AssistantMessage> extractAssistantMessage(ModelCallInputs inputs) {
        if (inputs.getResponse() instanceof AssistantMessage msg) {
            return Optional.of(msg);
        }
        return Optional.empty();
    }

    /**
     * Determines whether the current round is eligible for exploration: the
     * explore window must not be exhausted AND the round must carry tool calls
     * (final-answer rounds terminate the loop and would waste steering).
     *
     * @param msg assistant message from the current round
     * @param s   per-invocation state (carries the explore-round counter)
     * @return true if this round should run the Explorer
     */
    private boolean isExplorableRound(AssistantMessage msg, InvocationState s) {
        // [1] Explore window exhausted → stop injecting findings (let BUILD phase proceed)
        if (s.exploreRound >= budget.maxRounds()) {
            return false;
        }
        // [2] Final-answer round → loop is terminating, steering would be wasted
        return msg.getToolCalls() != null && !msg.getToolCalls().isEmpty();
    }

    /**
     * Runs the Explorer within a defensive boundary. Explorer failure must NOT
     * crash the agent loop — degrade silently (no fake findings pushed).
     *
     * <p>Catches the concrete runtime failures a faulty Explorer implementation
     * may raise ({@link IllegalStateException} / {@link NullPointerException});
     * other unexpected errors are allowed to propagate.
     *
     * @param userInput the resolved initial user query
     * @return the exploration result, or {@link ExplorationResult#empty()} on
     *         failure (never null)
     */
    private ExplorationResult exploreSafely(String userInput) {
        try {
            ExplorationResult result = explorer.explore(userInput, budget);
            // Explorer SPI may legally return null (no findings); normalize so
            // callers never null-check (G.MET.06 compliance).
            return result == null ? ExplorationResult.empty() : result;
        } catch (IllegalStateException | NullPointerException ex) {
            // Honest: no findings this round (no fake steering pushed).
            return ExplorationResult.empty();
        }
    }

    /**
     * Checks whether an exploration result carries actionable findings.
     *
     * @param result structured exploration result (may be {@link ExplorationResult#empty()})
     * @return true when findings text is present and non-empty
     */
    private static boolean hasFindings(ExplorationResult result) {
        return result.findings() != null && !result.findings().isEmpty();
    }

    // ============================================================
    // Helpers
    // ============================================================

    /**
     * Extracts the initial user query from the message list.
     *
     * <p>Searches for the first {@link UserMessage} in the context's message
     * list, rather than assuming a fixed position. The SDK may or may not
     * prepend a SystemMessage — when absent, messages[0] is the UserMessage.
     *
     * @param ctx callback context carrying the ModelContext
     * @param s   per-invocation state (carries the user-input cache)
     * @return the initial user query text, or an empty string when not
     *         resolvable (never null)
     */
    private String resolveUserInput(AgentCallbackContext ctx, InvocationState s) {
        if (s.userInputCache != null) {
            return s.userInputCache;
        }
        List<BaseMessage> messages = extractMessages(ctx);
        if (messages.isEmpty()) {
            return "";
        }
        cacheFirstUserMessage(messages, s);
        return s.userInputCache == null ? "" : s.userInputCache;
    }

    /**
     * Extracts the message list from the callback context's model context.
     *
     * @param ctx callback context carrying the ModelContext
     * @return the message list, or an empty list when unavailable (never null)
     */
    private List<BaseMessage> extractMessages(AgentCallbackContext ctx) {
        if (ctx.getContext() == null) {
            return List.of();
        }
        List<BaseMessage> messages = ctx.getContext().getMessages();
        return messages == null ? List.of() : messages;
    }

    /**
     * Caches the content of the first non-empty {@link UserMessage} found.
     *
     * @param messages the message list to scan (non-null, non-empty)
     * @param s       per-invocation state (stores the cached user input)
     */
    private void cacheFirstUserMessage(List<BaseMessage> messages, InvocationState s) {
        for (BaseMessage msg : messages) {
            if (msg instanceof UserMessage um) {
                String content = um.getContentAsString();
                if (content != null && !content.isEmpty()) {
                    s.userInputCache = content;
                    return;
                }
            }
        }
    }

    /**
     * Formats ExplorationResult into a steering string. Findings + candidate
     * approaches are surfaced so the next LLM iteration can leverage them
     * without re-exploring.
     *
     * @param result structured exploration result
     * @return formatted steering text for the next iteration
     */
    private static String formatFindings(ExplorationResult result) {
        StringBuilder sb = new StringBuilder("【探索发现 (Explore phase)】").append(LINE_SEPARATOR);
        sb.append(result.findings());
        if (!result.candidateApproaches().isEmpty()) {
            sb.append(LINE_SEPARATOR).append("候选方向：").append(result.candidateApproaches().stream().map(a -> "- " + a)
                    .collect(Collectors.joining(LINE_SEPARATOR)));
        }
        return sb.toString();
    }

    // ---- Per-invocation state (M6: migrated from bare instance fields) ----

    private InvocationState state(AgentCallbackContext ctx) {
        return RailInvocationState.get(ctx, stateKey, InvocationState.class, InvocationState::new);
    }

    private static final class InvocationState {
        private int exploreRound = 0;
        private String userInputCache;
    }

    // ---- Test observation points ----

    /**
     * Current explore round count for the given invocation context.
     *
     * @param ctx callback context for the invocation
     * @return number of explore rounds executed so far in this invocation
     */
    public int getExploreRound(AgentCallbackContext ctx) {
        return state(ctx).exploreRound;
    }
}
