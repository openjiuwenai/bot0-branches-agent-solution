/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.verification;

import com.openjiuwen.agents.reactrails.enforcing.SystemPromptInjectingModel;
import com.openjiuwen.agents.reactrails.types.Violation;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.AgentRail;
import com.openjiuwen.core.singleagent.rail.ModelCallInputs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * LangChain-style Voting Critic Rail — runs multiple {@link CriteriaVerifier} instances
 * in parallel on every final answer, aggregates all violations, and pushes a consolidated
 * steering hint.
 *
 * <p><b>Hook</b>: {@link #afterModelCall} — fires for every model response.
 * <ul>
 *   <li>Tool-call rounds: accumulates decision history (same as bridge rail).</li>
 *   <li>Final-answer rounds: runs ALL injected verifiers in parallel (via
 *       {@code parallelStream()}), collects every violation, builds a severity-ranked
 *       steering hint, and calls {@code ctx.pushSteering()}.</li>
 * </ul>
 *
 * <p><b>Voting strategy</b>: each verifier votes independently. The aggregate hint
 * groups violations by {@code Violation.criterion()}, deduplicates, and signals
 * to the LLM how many judges concur on each failure. When ALL verifiers agree on
 * a COMPLETE_MISS severity (via {@link AdaptiveCriteriaVerifier#aggregate}),
 * the steering hint is escalated with a "failure confirmed by multiple judges" prefix.
 *
 * <p><b>Priority</b>: HIGH (100) — fires before {@link CriteriaReplanBridgeRail}
 * so the bridge rail's own verify is the authoritative decision maker, while the
 * voting critic provides supplementary multi-judge feedback.
 *
 * <p><b>No forceFinish</b>: this rail never calls {@code requestForceFinish}.
 * It only pushes steering. The bridge rail owns the forceFinish decision.
 * This avoids the "who wins" ambiguity when multiple rails set forceFinish.
 *
 * <p><b>Thread safety</b>: verifier list is {@link CopyOnWriteArrayList}.
 * The {@code decisionHistory} is {@code synchronized} on the rail instance.
 *
 * <p><b>IFF 契约</b>: strip {@code pushSteering} in
 * {@link #buildAggregateHint(List, List)} → steering queue empty → test RED.
 * Strip verifier parallel stream → zero violations → hint empty → test RED.
 */
public class VotingCriticVerifierRail extends AgentRail {

    /** Priority: 100 — high, fires before bridge rail (default=0). */
    private static final int PRIORITY = 100;

    /** Extra map key: voting aggregate verdict for inter-rail observation. */
    public static final String EXTRA_VOTING_KEY = "voting_critic_result";

    /** Extra map value: {@code "PASS"} when all verifiers pass. */
    public static final String VOTING_PASS = "PASS";
    /** Extra map value: {@code "MINOR"} when most verifiers pass with minor issues. */
    public static final String VOTING_MINOR = "MINOR";
    /** Extra map value: {@code "FAIL"} when majority verifiers report violations. */
    public static final String VOTING_FAIL = "FAIL";

    public static final String OUTPUT_KEY = "output";
    public static final String VOTING_KEY = "voting_verdict";

    private final List<CriteriaVerifier> verifiers;
    private final List<String> successCriteria;
    private final List<String> decisionHistory = new ArrayList<>();

    /**
     * @param verifiers       one or more CriteriaVerifier instances (all run in parallel)
     * @param successCriteria the criteria to check (from agent goal, immutable)
     */
    public VotingCriticVerifierRail(List<CriteriaVerifier> verifiers,
                                    List<String> successCriteria) {
        if (verifiers == null || verifiers.isEmpty()) {
            throw new IllegalArgumentException("VotingCriticVerifierRail needs at least one verifier");
        }
        this.verifiers = new CopyOnWriteArrayList<>(verifiers);
        this.successCriteria = List.copyOf(successCriteria);
        setPriority(PRIORITY);
    }

    /** Exposed for test observation — current decision history. */
    public synchronized List<String> getDecisionHistory() {
        return List.copyOf(decisionHistory);
    }

    /** Exposed for test observation — registered verifiers. */
    public List<CriteriaVerifier> getVerifiers() {
        return List.copyOf(verifiers);
    }

    // ============================================================
    // Hook: afterModelCall
    // ============================================================

    @Override
    public synchronized void afterModelCall(AgentCallbackContext ctx) {
        if (!(ctx.getInputs() instanceof ModelCallInputs inputs)) {
            return;
        }
        if (!(inputs.getResponse() instanceof AssistantMessage msg)) {
            return;
        }

        if (isFinalAnswer(msg)) {
            handleFinalAnswer(ctx, msg);
        } else {
            // Tool-call round: accumulate decision history
            accumulateToolCalls(msg);
        }
    }

    // ============================================================
    // Final-answer handling: parallel voting + aggregate steering
    // ============================================================

    /**
     * Run all verifiers in parallel, collect violations per verifier,
     * build an aggregate steering hint, and push it.
     */
    private void handleFinalAnswer(AgentCallbackContext ctx, AssistantMessage msg) {
        String output = contentOf(msg);
        String historyStr = String.join(" | ", decisionHistory);

        // Run ALL verifiers in parallel
        List<List<Violation>> perVerifierResults = verifiers.parallelStream()
                .map(v -> v.verify(successCriteria, output, historyStr))
                .toList();

        // Flatten all violations
        List<Violation> allViolations = perVerifierResults.stream()
                .flatMap(List::stream)
                .toList();

        // Determine aggregate verdict
        String verdict = aggregateVerdict(perVerifierResults);
        ctx.getExtra().put(EXTRA_VOTING_KEY, verdict);

        if (allViolations.isEmpty()) {
            // All verifiers pass → nothing to steer
            ctx.getExtra().put(VOTING_KEY, VOTING_PASS);
            return;
        }

        // Build severity-ranked steering hint
        String hint = buildAggregateHint(allViolations, perVerifierResults);
        ctx.getExtra().put(VOTING_KEY, verdict);
        ctx.pushSteering(hint);

        // If ALL verifiers agree on complete miss, signal a phase transition
        // to SystemPromptInjectingModel for stronger injection on next call.
        if (verdict.equals(VOTING_FAIL) && allVerifiersAgreeCompleteMiss(perVerifierResults)) {
            SystemPromptInjectingModel.setPhaseOverride(
                    "Multiple judges independently concluded that your response fails to meet"
                    + " ALL success criteria. This is a critical failure — do NOT repeat the"
                    + " same approach. Rethink your strategy from scratch.");
        }
    }

    // ============================================================
    // Verdict aggregation
    // ============================================================

    /**
     * Aggregate per-verifier results into a single verdict string.
     *
     * <p>Strategy:
     * <ul>
     *   <li>If ANY verifier reports violations → MINOR (some judges see issues)</li>
     *   <li>If ALL verifiers report violations → FAIL (consensus failure)</li>
     *   <li>If NO verifier reports violations → PASS (all judges agree OK)</li>
     * </ul>
     */
    static String aggregateVerdict(List<List<Violation>> perVerifierResults) {
        if (perVerifierResults == null || perVerifierResults.isEmpty()) {
            return VOTING_PASS;
        }
        long verifiersWithViolations = perVerifierResults.stream()
                .filter(vs -> vs != null && !vs.isEmpty())
                .count();
        if (verifiersWithViolations == perVerifierResults.size()) {
            return VOTING_FAIL;
        }
        if (verifiersWithViolations > 0) {
            return VOTING_MINOR;
        }
        return VOTING_PASS;
    }

    /**
     * Check whether ALL verifiers produced only COMPLETE_MISS violations
     * (zero keyword coverage per AdaptiveCriteriaVerifier severity).
     */
    private static boolean allVerifiersAgreeCompleteMiss(List<List<Violation>> perVerifierResults) {
        return perVerifierResults.stream()
                .allMatch(vs -> vs != null && !vs.isEmpty()
                        && AdaptiveCriteriaVerifier.aggregate(vs)
                                == AdaptiveCriteriaVerifier.Severity.COMPLETE_MISS);
    }

    // ============================================================
    // Aggregate steering hint
    // ============================================================

    /**
     * Build a severity-ranked steering hint from all violations.
     *
     * <p>Groups by criterion, deduplicates, ranks by adaptive severity,
     * and includes a "judge count" prefix showing how many verifiers flagged it.
     */
    static String buildAggregateHint(List<Violation> allViolations,
                                     List<List<Violation>> perVerifierResults) {
        if (allViolations == null || allViolations.isEmpty()) {
            return "";
        }

        int numVerifiers = perVerifierResults.size();

        // Group violations by criterion
        Map<String, List<Violation>> byCriterion = allViolations.stream()
                .collect(Collectors.groupingBy(
                        Violation::criterion,
                        LinkedHashMap::new,
                        Collectors.toList()));

        StringBuilder sb = new StringBuilder();
        sb.append("【多角度评审反馈】").append(numVerifiers).append("个评审维度反馈汇总:\n");

        for (var entry : byCriterion.entrySet()) {
            String criterion = entry.getKey();
            List<Violation> criterionViolations = entry.getValue();

            // How many verifiers flagged this criterion?
            long judgeCount = criterionViolations.size();
            String severity = severityLabel(criterionViolations);

            sb.append("- [").append(severity).append("] ").append(criterion);
            if (judgeCount > 1) {
                sb.append(" (").append(judgeCount).append("/").append(numVerifiers).append(" judges)");
            }
            sb.append("\n");

            // Include the most detailed reason (longest)
            String bestReason = criterionViolations.stream()
                    .map(Violation::reason)
                    .filter(r -> r != null && !r.isEmpty())
                    .max((a, b) -> Integer.compare(a.length(), b.length()))
                    .orElse("未满足标准");
            sb.append("  → ").append(bestReason).append("\n");
        }

        sb.append("请根据以上综合反馈修改回答。多个评审维度指向相同问题，建议优先处理。");
        return sb.toString();
    }

    /**
     * Determine worst severity across violations for one criterion.
     */
    private static String severityLabel(List<Violation> violations) {
        if (violations == null || violations.isEmpty()) return "PASS";
        AdaptiveCriteriaVerifier.Severity worst = AdaptiveCriteriaVerifier.Severity.PASS;
        for (Violation v : violations) {
            AdaptiveCriteriaVerifier.Severity sev = AdaptiveCriteriaVerifier.severityOf(v);
            if (sev.worseThan(worst)) {
                worst = sev;
            }
        }
        return switch (worst) {
            case PASS -> "PASS";
            case NEAR_PASS -> "NEAR_PASS";
            case WIDE_MISS -> "WIDE_MISS";
            case COMPLETE_MISS -> "COMPLETE_MISS";
        };
    }

    // ============================================================
    // Helpers
    // ============================================================

    private static boolean isFinalAnswer(AssistantMessage msg) {
        return msg.getToolCalls() == null || msg.getToolCalls().isEmpty();
    }

    private synchronized void accumulateToolCalls(AssistantMessage msg) {
        if (msg.getToolCalls() == null) return;
        for (ToolCall tc : msg.getToolCalls()) {
            decisionHistory.add(tc.getName() + "(" + tc.getArguments() + ")");
        }
    }

    private static String contentOf(AssistantMessage msg) {
        String content = msg.getContentAsString();
        return content != null ? content : "";
    }
}
