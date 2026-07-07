/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.verification;

import com.openjiuwen.agents.reactrails.types.Violation;
import com.openjiuwen.agents.reactrails.verification.VerifierScorer.ScoredVerdict;
import com.openjiuwen.core.foundation.llm.Model;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.SystemMessage;
import com.openjiuwen.core.foundation.llm.schema.UserMessage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * VotingCriteriaVerifier — K independent verifier scorers, weighted vote aggregation.
 *
 * <p>Ensemble pattern over the existing {@link CriteriaVerifier} SPI. For each success
 * criterion, K independent {@link VerifierScorer} implementations evaluate the output in
 * parallel (virtual threads, Java 21+). The weighted sum of per-criterion scores determines
 * pass/fail against a configurable threshold.
 *
 * <p><b>Benchmark: LangChain Voting Agentic Pattern</b>
 * <br>LangChain uses multiple independent "critics" that each evaluate the output separately,
 * then aggregate via majority vote or weighted consensus. This verifier is the same pattern
 * adapted to the {@link CriteriaVerifier} SPI — each scorer is a critic, the weighted sum
 * is the voting aggregation, and violations represent the ensemble's verdict.
 *
 * <p><b>Reference configuration (K=3, weights sum to 1.0)</b>:
 * <pre>{@code
 * List<WeightedScorer> ensemble = List.of(
 *     new WeightedScorer(0.25, new KeywordScorer()),
 *     new WeightedScorer(0.25, new RigorScorer()),
 *     new WeightedScorer(0.50, new LlmJudgeScorer(llmModel)));
 * CriteriaVerifier verifier = new VotingCriteriaVerifier(ensemble, 0.5);
 * }</pre>
 *
 * <p><b>Integration</b>: drop-in replacement for any existing {@link CriteriaVerifier}
 * usage. The autoconfig's {@code @ConditionalOnMissingBean(CriteriaVerifier.class)} means
 * defining a {@link VotingCriteriaVerifier} bean replaces the default
 * {@link RuleBasedCriteriaVerifier}.
 *
 * <p><b>Parallel execution</b>: each scorer runs in its own virtual thread
 * ({@link Executors#newVirtualThreadPerTaskExecutor()}). All scores are collected with a
 * 30-second timeout per scorer. A scorer that times out or throws is treated as score 0.0
 * (honest fail-degraded).
 *
 * <p><b>Thread safety</b>: scorers must be stateless or thread-safe. The internal
 * aggregation is single-threaded after all scorers complete.
 *
 * @see VerifierScorer
 * @see WeightedScorer
 */
public class VotingCriteriaVerifier implements CriteriaVerifier {

    /** Default pass threshold: weighted score >= this = criterion met. */
    public static final double DEFAULT_PASS_THRESHOLD = 0.5;

    /** Default timeout per scorer in seconds. */
    public static final long DEFAULT_TIMEOUT_SECONDS = 30;

    private final List<WeightedScorer> scorers;
    private final double passThreshold;
    private final long timeoutSeconds;

    /**
     * @param scorers        ordered list of weighted scorers (sum of weights should be 1.0)
     * @param passThreshold  minimum weighted score to consider a criterion met (default 0.5)
     * @param timeoutSeconds timeout per scorer in seconds (default 30)
     */
    public VotingCriteriaVerifier(List<WeightedScorer> scorers, double passThreshold, long timeoutSeconds) {
        if (scorers == null || scorers.isEmpty()) {
            throw new IllegalArgumentException("At least one WeightedScorer is required");
        }
        this.scorers = List.copyOf(scorers);
        this.passThreshold = passThreshold;
        this.timeoutSeconds = timeoutSeconds;
    }

    /** @param scorers weighted scorers, pass threshold 0.5, timeout 30s */
    public VotingCriteriaVerifier(List<WeightedScorer> scorers, double passThreshold) {
        this(scorers, passThreshold, DEFAULT_TIMEOUT_SECONDS);
    }

    /** @param scorers weighted scorers, pass threshold 0.5, timeout 30s */
    public VotingCriteriaVerifier(List<WeightedScorer> scorers) {
        this(scorers, DEFAULT_PASS_THRESHOLD, DEFAULT_TIMEOUT_SECONDS);
    }

    /**
     * Verify all criteria via weighted voting.
     *
     * <p>Each scorer evaluates all criteria in parallel (virtual thread per scorer).
     * Per-criterion scores are aggregated via weighted sum. Criteria whose weighted
     * score is below {@code passThreshold} produce a {@link Violation} with aggregated
     * feedback from all scorers.
     */
    @Override
    public List<Violation> verify(List<String> successCriteria, String output, String decisionHistory) {
        if (successCriteria == null || successCriteria.isEmpty()) {
            return List.of();
        }
        String safeOutput = output == null ? "" : output;
        String safeHistory = decisionHistory == null ? "" : decisionHistory;

        // Step 1: Filter null/blank criteria before parallel execution
        List<String> validCriteria = successCriteria.stream()
                .filter(c -> c != null && !c.isBlank())
                .toList();
        if (validCriteria.isEmpty()) {
            return List.of();
        }

        // Step 2: Run all scorers in parallel
        List<ScoredVerdict> verdicts = runAllScorers(validCriteria, safeOutput, safeHistory);

        // Step 3: Per-criterion weighted aggregation
        List<Violation> violations = new ArrayList<>();
        for (String criterion : validCriteria) {
            if (criterion == null || criterion.isBlank()) continue;

            double weightedScore = 0.0;
            List<String> feedbackParts = new ArrayList<>();

            for (int i = 0; i < scorers.size(); i++) {
                WeightedScorer ws = scorers.get(i);
                ScoredVerdict v = verdicts.get(i);
                double s = v.criterionScores().getOrDefault(criterion, 0.0);
                weightedScore += ws.weight() * s;
                feedbackParts.add(String.format(Locale.ROOT, "[%s] %.2f: %s",
                        scorers.get(i).scorer().label(), s, v.feedback()));
            }

            if (weightedScore < passThreshold) {
                violations.add(new Violation(criterion, String.format(Locale.ROOT,
                        "投票加权总分 %.2f (阈值 %s). 各评分维度: %s",
                        weightedScore, passThreshold, String.join("; ", feedbackParts))));
            }
        }
        return violations;
    }

    /**
     * Invoke all scorers in parallel and collect verdicts.
     *
     * <p>Each scorer runs in a virtual thread. Timeouts and exceptions are caught:
     * a failing scorer contributes 0.0 for all criteria (fail-degraded, honest).
     */
    private List<ScoredVerdict> runAllScorers(List<String> criteria, String output, String history) {
        List<ScoredVerdict> verdicts = new ArrayList<>();
        List<Callable<ScoredVerdict>> tasks = new ArrayList<>();

        // Capture criteria snapshot for thread safety
        List<String> criteriaCopy = List.copyOf(criteria);
        for (WeightedScorer ws : scorers) {
            VerifierScorer scorer = ws.scorer();
            tasks.add(() -> scorer.score(criteriaCopy, output, history));
        }

        // Run in parallel via virtual thread executor
        // executor.invokeAll and future.get both throw InterruptedException
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = executor.invokeAll(tasks, timeoutSeconds, TimeUnit.SECONDS);
            for (int i = 0; i < futures.size(); i++) {
                var future = futures.get(i);
                try {
                    verdicts.add(future.get(0, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    String label = scorers.get(i).scorer().label();
                    verdicts.add(ScoredVerdict.allZero(
                            criteria, label + " 异常: " + e.getCause().getMessage()));
                } catch (TimeoutException e) {
                    String label = scorers.get(i).scorer().label();
                    verdicts.add(ScoredVerdict.allZero(
                            criteria, label + " 超时 (> " + timeoutSeconds + "s)"));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    String label = scorers.get(i).scorer().label();
                    verdicts.add(ScoredVerdict.allZero(criteria, label + " 中断"));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            for (int i = verdicts.size(); i < scorers.size(); i++) {
                String label = scorers.get(i).scorer().label();
                verdicts.add(ScoredVerdict.allZero(criteria, label + " 中断"));
            }
        }
        return verdicts;
    }

    // ============================================================
    // Value type: WeightedScorer
    // ============================================================

    /**
     * A scorer paired with its voting weight. Weights should sum to 1.0.
     *
     * @param weight voting weight in [0.0, 1.0]
     * @param scorer the scoring function
     */
    public record WeightedScorer(double weight, VerifierScorer scorer) {
        public WeightedScorer {
            if (weight < 0.0 || weight > 1.0) {
                throw new IllegalArgumentException("Weight must be in [0.0, 1.0]: " + weight);
            }
        }
    }

    // ============================================================
    // Built-in scorer: KeywordScorer (deterministic, zero LLM cost)
    // ============================================================

    /**
     * Deterministic keyword-match scorer — fastest scorer in the ensemble.
     *
     * <p>Extracts meaningful keywords from each criterion (>=2 chars, non-stop-word),
     * then measures what fraction appear in the output or decision history.
     * Returns 1.0 when the criterion has no extractable keywords (common words only),
     * avoiding false fail on generic criteria.
     *
     * <p>Zero-coupling: no LLM calls, no external dependencies beyond the JDK.
     * Designed for the voting ensemble: returns a continuous score [0, 1] instead of
     * a binary pass/fail, enabling nuanced weighted aggregation.
     */
    public static class KeywordScorer implements VerifierScorer {

        private static final Set<String> STOP_WORDS = Set.of(
                "的", "了", "在", "是", "和", "就", "不", "都", "一", "一个", "上", "也",
                "the", "a", "an", "is", "are", "was", "to", "of", "in", "for", "on",
                "with", "at", "by", "from", "as", "into", "and", "or", "not", "be",
                "this", "that", "it", "its", "must", "should", "will");

        @Override
        public ScoredVerdict score(List<String> successCriteria, String output, String decisionHistory) {
            Map<String, Double> result = new HashMap<>();
            String outputLower = output == null ? "" : output.toLowerCase(Locale.ROOT);
            String historyLower = decisionHistory == null ? "" : decisionHistory.toLowerCase(Locale.ROOT);

            for (String criterion : successCriteria) {
                if (criterion == null || criterion.isBlank()) continue;
                result.put(criterion, computeScore(criterion, outputLower, historyLower));
            }
            return new ScoredVerdict(result);
        }

        private static double computeScore(String criterion, String outputLower, String historyLower) {
            String[] words = criterion.toLowerCase(Locale.ROOT).split("[\\s,，、。.]+");
            List<String> keywords = new ArrayList<>();
            for (String w : words) {
                if (w.length() >= 2 && !STOP_WORDS.contains(w)) {
                    keywords.add(w);
                }
            }
            if (keywords.isEmpty()) {
                return 1.0; // No extractable keywords -> cannot fail this criterion
            }
            long matched = keywords.stream()
                    .filter(kw -> outputLower.contains(kw) || historyLower.contains(kw))
                    .count();
            return (double) matched / keywords.size();
        }

        @Override
        public String label() {
            return "关键词匹配";
        }
    }

    // ============================================================
    // Built-in scorer: RigorScorer (output quality heuristics)
    // ============================================================

    /**
     * Output quality scorer based on structural heuristics.
     *
     * <p>Scores the output along three axes:
     * <ul>
     *   <li><b>Numeric density</b> (0.0-0.34) — each numeric token adds 0.06, capped</li>
     *   <li><b>Reasoning markers</b> (0.0-0.33) — each causal/inferential word adds 0.06, capped</li>
     *   <li><b>Substance</b> (0.0-0.33) — length bonus (0.15 for >200 chars), tool evidence (0.18)</li>
     * </ul>
     *
     * <p>The score is criterion-independent: the same rigor score applies to all criteria,
     * acting as a baseline quality gate in the ensemble.
     */
    public static class RigorScorer implements VerifierScorer {

        private static final Pattern NUMERIC_PATTERN = Pattern.compile("\\d+(?:\\.\\d+)?");
        private static final Pattern REASONING_MARKERS = Pattern.compile(
                "因此|因为|所以|由于|推导|计算|原因|影响|对比|趋势|conclude|therefore|because|hence",
                Pattern.CASE_INSENSITIVE);

        @Override
        public ScoredVerdict score(List<String> successCriteria, String output, String decisionHistory) {
            double score = computeRigor(output, decisionHistory);
            Map<String, Double> result = new HashMap<>();
            for (String criterion : successCriteria) {
                if (criterion != null && !criterion.isBlank()) {
                    result.put(criterion, score);
                }
            }
            return new ScoredVerdict(result, String.format(Locale.ROOT, "全局质量分 %.2f", score));
        }

        private static double computeRigor(String output, String history) {
            String safeOutput = output == null ? "" : output;
            String safeHistory = history == null ? "" : history;
            double s = 0.0;

            long numCount = NUMERIC_PATTERN.matcher(safeOutput).results().count();
            s += Math.min(numCount * 0.06, 0.34);

            long markerCount = REASONING_MARKERS.matcher(safeOutput).results().count();
            s += Math.min(markerCount * 0.06, 0.33);

            if (safeOutput.length() > 200) s += 0.15;
            if (safeHistory.contains("__probe_tool__") || safeHistory.contains("__replan__")) s += 0.18;

            return Math.min(s, 1.0);
        }

        @Override
        public String label() {
            return "输出质量";
        }
    }

    // ============================================================
    // Built-in scorer: LlmJudgeScorer (external LLM-as-judge)
    // ============================================================

    /**
     * LLM-as-judge scorer — delegates semantic evaluation to an external LLM.
     *
     * <p>Constructs a judge prompt that asks the LLM to rate the output against each
     * success criterion on a scale of 0-10, normalizes to [0, 1].
     * Uses the existing {@link Model} from agent-core-java (no jar modifications needed).
     *
     * <p><b>Caveats</b>:
     * <ul>
     *   <li>Requires a configured {@link Model} (or subclass like {@code ToolCallingEnforcingModel})</li>
     *   <li>One LLM call per {@code score(...)} invocation — this is the expensive scorer</li>
     *   <li>Parses structured JSON output from the LLM; graceful degradation on parse failure</li>
     * </ul>
     *
     * <p>Zero-coupling from the jar: uses only public {@link Model} and
     * {@link com.openjiuwen.core.foundation.llm.schema.BaseMessage} types from agent-core-java.
     */
    public static class LlmJudgeScorer implements VerifierScorer {

        private static final String JUDGE_SYSTEM_PROMPT =
                "You are an impartial judge evaluating an agent's output against success criteria. "
                + "Rate the output on a scale of 0-10 for each criterion, where:\n"
                + "- 10 = fully meets the criterion with thorough evidence\n"
                + "- 7-9 = meets the criterion well\n"
                + "- 4-6 = partially meets the criterion\n"
                + "- 1-3 = barely addresses the criterion\n"
                + "- 0 = does not address the criterion at all\n\n"
                + "Respond with a JSON object where keys are the criteria and values are the scores. "
                + "Example: {\"criterion1\": 8, \"criterion2\": 3}";

        private final Model llm;

        /** @param llm a configured LLM {@link Model} instance for judge calls */
        public LlmJudgeScorer(Model llm) {
            this.llm = llm;
        }

        @Override
        public ScoredVerdict score(List<String> successCriteria, String output, String decisionHistory) {
            if (successCriteria == null || successCriteria.isEmpty()) {
                return new ScoredVerdict(Map.of());
            }
            String safeOutput = output == null ? "" : output;
            String safeHistory = decisionHistory == null ? "" : decisionHistory;

            String criteriaText = successCriteria.stream()
                    .filter(c -> c != null && !c.isBlank())
                    .map(c -> "- " + c)
                    .collect(Collectors.joining("\n"));
            String userPrompt = "Success criteria:\n" + criteriaText
                    + "\n\nAgent output:\n" + safeOutput
                    + "\n\nTool history:\n" + safeHistory
                    + "\n\nRate each criterion 0-10 as JSON.";

            try {
                // Model.invoke(Object messages, Object tools, Float temperature,
                //     Float maxTokens, String model, Integer n, String stop,
                //     BaseOutputParser parser, Float topP, Map<String,Object> kwargs)
                AssistantMessage response = llm.invoke(
                        List.of(new SystemMessage(JUDGE_SYSTEM_PROMPT),
                                new UserMessage(userPrompt)),
                        null, Float.valueOf(0.3f), Float.valueOf(1024f),
                        null, null, null, null, null, null);
                String content = response != null ? response.getContentAsString() : "";
                Map<String, Double> scores = parseScores(content, successCriteria);
                return new ScoredVerdict(scores, scores.isEmpty() ? "LLM未返回有效评分" : "OK");
            } catch (Exception e) {
                return ScoredVerdict.allZero(successCriteria,
                        "LLM评判调用失败: " + e.getMessage());
            }
        }

        /**
         * Parse JSON scores from LLM response. Graceful fallback: returns empty map
         * on parse failure.
         */
        private static Map<String, Double> parseScores(String content, List<String> criteria) {
            Map<String, Double> result = new HashMap<>();
            int jsonStart = content.indexOf('{');
            int jsonEnd = content.lastIndexOf('}');
            if (jsonStart < 0 || jsonEnd <= jsonStart) {
                return result;
            }
            String json = content.substring(jsonStart, jsonEnd + 1);
            for (String criterion : criteria) {
                if (criterion == null || criterion.isBlank()) continue;
                double score = extractJsonScore(json, criterion);
                result.put(criterion, score);
            }
            return result;
        }

        /** Extract a numeric score (0-10) for a criterion from a JSON response. */
        private static double extractJsonScore(String json, String criterion) {
            String searchKey = "\"" + criterion.replace("\"", "\\\"") + "\"";
            int keyIdx = json.indexOf(searchKey);
            if (keyIdx < 0) {
                keyIdx = json.indexOf(searchKey.toLowerCase(Locale.ROOT));
            }
            if (keyIdx < 0) return 0.0;

            int colonIdx = json.indexOf(':', keyIdx + searchKey.length());
            if (colonIdx < 0) return 0.0;

            int numStart = colonIdx + 1;
            while (numStart < json.length() && json.charAt(numStart) == ' ') numStart++;
            int numEnd = numStart;
            while (numEnd < json.length()
                    && (Character.isDigit(json.charAt(numEnd)) || json.charAt(numEnd) == '.')) {
                numEnd++;
            }
            if (numStart >= numEnd) return 0.0;

            try {
                double raw = Double.parseDouble(json.substring(numStart, numEnd));
                return Math.min(Math.max(raw / 10.0, 0.0), 1.0);
            } catch (NumberFormatException e) {
                return 0.0;
            }
        }

        @Override
        public String label() {
            return "LLM评判";
        }
    }
}
