/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.verification;

import com.openjiuwen.agents.reactrails.types.Violation;
import com.openjiuwen.agents.reactrails.verification.VerifierScorer.ScoredVerdict;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * VotingCriteriaVerifier 承重测试 — K 个独立 scorer 的加权投票聚合。
 *
 * <p>覆盖：
 * <ul>
 *   <li>K=2 双 scorer，加权通过 → 无 violation</li>
 *   <li>K=2 单 scorer 低于阈值 → violation</li>
 *   <li>K=2 双 scorer 低于阈值 → violation</li>
 *   <li>自定义阈值</li>
 *   <li>空/空输入 → 优雅处理</li>
 *   <li>scorer 抛出异常 → 0.0 处理</li>
 *   <li>KeywordScorer 独立测试</li>
 *   <li>RigorScorer 独立测试</li>
 *   <li>多 criterion 混合结果</li>
 *   <li>权重不合法 → 构造异常</li>
 *   <li>空 scorer 列表 → 构造异常</li>
 * </ul>
 *
 * <p>每测试标注 mutation-RED: 哪些代码变异会让该测试 RED。
 *
 * @see VotingCriteriaVerifier
 * @see VerifierScorer
 */
class VotingCriteriaVerifierTest {

    // ==================== Factory helpers for test scorers ====================

    /** A test scorer that returns the same score for all criteria. */
    private static VerifierScorer constantScorer(double score, String label) {
        return new VerifierScorer() {
            @Override
            public ScoredVerdict score(List<String> successCriteria, String output, String decisionHistory) {
                Map<String, Double> map = new HashMap<>();
                for (String c : successCriteria) {
                    if (c != null && !c.isBlank()) map.put(c, score);
                }
                return new ScoredVerdict(map);
            }

            @Override
            public String label() {
                return label;
            }
        };
    }

    /** A test scorer that returns different scores per criterion from a map. */
    private static VerifierScorer perCriterionScorer(Map<String, Double> scores, String label) {
        return new VerifierScorer() {
            @Override
            public ScoredVerdict score(List<String> successCriteria, String output, String decisionHistory) {
                return new ScoredVerdict(new HashMap<>(scores));
            }

            @Override
            public String label() {
                return label;
            }
        };
    }

    /** A test scorer that always throws. */
    private static VerifierScorer throwingScorer(String label) {
        return new VerifierScorer() {
            @Override
            public ScoredVerdict score(List<String> successCriteria, String output, String decisionHistory) {
                throw new RuntimeException("simulated scorer failure");
            }

            @Override
            public String label() {
                return label;
            }
        };
    }

    // ==================== K=2: both above threshold ====================

    @Test
    void bothScorersAboveThreshold_noViolations() {
        // Scorer A: 0.8 for all criteria, weight 0.5
        // Scorer B: 0.9 for all criteria, weight 0.5
        // Weighted: 0.5*0.8 + 0.5*0.9 = 0.85 >= 0.5 PASS
        var verifier = new VotingCriteriaVerifier(List.of(
                new VotingCriteriaVerifier.WeightedScorer(0.5, constantScorer(0.8, "A")),
                new VotingCriteriaVerifier.WeightedScorer(0.5, constantScorer(0.9, "B"))));

        List<Violation> result = verifier.verify(List.of("GDP", "CPI"),
                "GDP grew 5.2%", "");

        assertThat(result)
                .as("both scorers above threshold -> no violations")
                .isEmpty();
        // mutation-RED: swap weightedScore from >= to > → 0.85 not > 0.5 → violations → RED
        // mutation-RED: change passThreshold to 0.9 → weighted 0.85 < 0.9 → violations → RED
    }

    // ==================== K=2: one below threshold ====================

    @Test
    void oneScorerBelowThreshold_weightedScoreStillBelowThreshold() {
        // Scorer A: 0.2, weight 0.5; Scorer B: 0.4, weight 0.5
        // Weighted: 0.5*0.2 + 0.5*0.4 = 0.30 < 0.5 → violation
        var verifier = new VotingCriteriaVerifier(List.of(
                new VotingCriteriaVerifier.WeightedScorer(0.5, constantScorer(0.2, "A")),
                new VotingCriteriaVerifier.WeightedScorer(0.5, constantScorer(0.4, "B"))),
                0.5);

        List<Violation> result = verifier.verify(List.of("test"), "output", "");

        assertThat(result)
                .as("weighted score 0.30 < 0.5 -> violation")
                .isNotEmpty();
        assertThat(result.get(0).reason())
                .contains("投票加权总分")
                .contains("0.30");
        // mutation-RED: change weightedScore < passThreshold to >= → no violation → RED
    }

    // ==================== K=2: both below threshold ====================

    @Test
    void bothScorersBelowThreshold_producesViolation() {
        var verifier = new VotingCriteriaVerifier(List.of(
                new VotingCriteriaVerifier.WeightedScorer(0.5, constantScorer(0.1, "A")),
                new VotingCriteriaVerifier.WeightedScorer(0.5, constantScorer(0.2, "B"))),
                0.5);

        List<Violation> result = verifier.verify(List.of("test"), "output", "");

        assertThat(result)
                .as("both scorers below threshold -> weighted score 0.15 < 0.5")
                .isNotEmpty();
        assertThat(result.get(0).reason())
                .contains("0.15");
        // mutation-RED: change weightedScore < passThreshold to <= → 0.15 < 0.5 still → still RED
        //   (test still passes, but the pass boundary is wrong)
        // mutation-RED: flip the threshold check > instead of < → no violation → RED
    }

    // ==================== Custom threshold ====================

    @Test
    void customPassThreshold_affectsVerdict() {
        // Both scorers return 0.4, weight 0.5 each → weighted 0.4
        // With threshold 0.3 → PASS (no violation)
        // With threshold 0.5 → FAIL (violation)
        var highThreshold = new VotingCriteriaVerifier(List.of(
                new VotingCriteriaVerifier.WeightedScorer(0.5, constantScorer(0.4, "A")),
                new VotingCriteriaVerifier.WeightedScorer(0.5, constantScorer(0.4, "B"))),
                0.5);
        var lowThreshold = new VotingCriteriaVerifier(List.of(
                new VotingCriteriaVerifier.WeightedScorer(0.5, constantScorer(0.4, "A")),
                new VotingCriteriaVerifier.WeightedScorer(0.5, constantScorer(0.4, "B"))),
                0.3);

        assertThat(highThreshold.verify(List.of("test"), "x", ""))
                .as("weighted 0.4 < 0.5 -> violation")
                .isNotEmpty();
        assertThat(lowThreshold.verify(List.of("test"), "x", ""))
                .as("weighted 0.4 >= 0.3 -> no violation")
                .isEmpty();
        // mutation-RED: hardcode passThreshold => both behave same -> one assertion fails -> RED
    }

    // ==================== Per-criterion scores ====================

    @Test
    void perCriterionScores_aggregatedIndependently() {
        // Criterion "A": scorer1=0.8, scorer2=0.2 → weighted 0.5*0.8+0.5*0.2=0.50 >= 0.5 → PASS
        // Criterion "B": scorer1=0.2, scorer2=0.2 → weighted 0.20 < 0.5 → FAIL
        var verifier = new VotingCriteriaVerifier(List.of(
                new VotingCriteriaVerifier.WeightedScorer(0.5,
                        perCriterionScorer(Map.of("A", 0.8, "B", 0.2), "S1")),
                new VotingCriteriaVerifier.WeightedScorer(0.5,
                        perCriterionScorer(Map.of("A", 0.2, "B", 0.2), "S2"))),
                0.5);

        List<Violation> result = verifier.verify(List.of("A", "B"), "output", "");

        assertThat(result)
                .as("criterion B should fail while A passes")
                .hasSize(1);
        assertThat(result.get(0).criterion())
                .isEqualTo("B");
        // mutation-RED: use flat aggregate across all criteria -> both fail or both pass -> RED
    }

    // ==================== Null/empty input ====================

    @Test
    void nullAndEmptyInputs_handledGracefully() {
        // Use a higher threshold so the scorer's 0.5 default produces a violation
        var verifier = new VotingCriteriaVerifier(List.of(
                new VotingCriteriaVerifier.WeightedScorer(1.0, constantScorer(0.5, "S"))),
                0.6);

        // Null criteria list
        assertThat(verifier.verify(null, "output", ""))
                .as("null criteria returns empty list")
                .isEmpty();

        // Empty criteria list
        assertThat(verifier.verify(List.of(), "output", ""))
                .as("empty criteria returns empty list")
                .isEmpty();

        // Null output + null history (scorer must handle without NPE)
        // Score=0.5, threshold=0.6 -> weighted 0.5 < 0.6 -> violation
        assertThat(verifier.verify(List.of("test"), null, null))
                .as("null output+history handled without NPE")
                .isNotEmpty();

        // Null criterion in list (skipped) — use low threshold to avoid false fail
        var lowThresholdVerifier = new VotingCriteriaVerifier(List.of(
                new VotingCriteriaVerifier.WeightedScorer(1.0, constantScorer(0.5, "S"))),
                0.4);
        List<String> criteriaWithNull = new ArrayList<>();
        criteriaWithNull.add("real");
        criteriaWithNull.add(null);
        criteriaWithNull.add("criteria");
        assertThat(lowThresholdVerifier.verify(criteriaWithNull, "real criteria output", ""))
                .as("null criterion skipped, valid criteria at 0.5 >= 0.4 -> no violations")
                .isEmpty();

        // mutation-RED: remove null safety in scoring → NPE → RED
    }

    // ==================== Scorer throws ====================

    @Test
    void scoringScorerThrows_treatedAsZero() {
        var verifier = new VotingCriteriaVerifier(List.of(
                new VotingCriteriaVerifier.WeightedScorer(1.0, throwingScorer("broken"))),
                0.5);

        List<Violation> result = verifier.verify(List.of("test"), "output", "");

        assertThat(result)
                .as("throwing scorer -> treated as 0.0 -> violation")
                .isNotEmpty();
        assertThat(result.get(0).reason())
                .contains("broken")
                .contains("异常");
        // mutation-RED: don't catch exception -> test never reaches assertions -> RED
        // mutation-RED: treat exception as 1.0 instead of 0.0 -> no violation -> RED
    }

    // ==================== Weight validation ====================

    @Test
    void invalidWeight_throwsOnConstruction() {
        assertThatThrownBy(() -> new VotingCriteriaVerifier.WeightedScorer(-0.1, constantScorer(0.5, "X")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VotingCriteriaVerifier.WeightedScorer(1.5, constantScorer(0.5, "X")))
                .isInstanceOf(IllegalArgumentException.class);
        // mutation-RED: remove weight validation -> no exception -> RED
    }

    @Test
    void emptyScorersList_throwsOnConstruction() {
        assertThatThrownBy(() -> new VotingCriteriaVerifier(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VotingCriteriaVerifier(null, 0.5))
                .isInstanceOf(IllegalArgumentException.class);
        // mutation-RED: remove empty check -> construction succeeds -> NPE on use -> RED
    }

    // ==================== KeywordScorer unit tests ====================

    @Test
    void keywordScorer_fullMatch_returnsHighScore() {
        var scorer = new VotingCriteriaVerifier.KeywordScorer();
        // "GDP growth" -> keywords ["gdp", "growth"]; output contains both
        var verdict = scorer.score(List.of("GDP growth"), "GDP growth is 5.2%", "");

        assertThat(verdict.criterionScores())
                .containsEntry("GDP growth", 1.0);
        // mutation-RED: change contains() to any match -> "GDP" in "GDP growth" = true, but "growth" not in output -> < 1.0 -> RED
    }

    @Test
    void keywordScorer_partialMatch_returnsFraction() {
        var scorer = new VotingCriteriaVerifier.KeywordScorer();
        // Criterion "GDP growth CPI" -> keywords ["gdp", "growth", "cpi"]
        // Output "GDP and CPI" -> "gdp" and "cpi" match, "growth" does not -> 2/3 = 0.667
        var verdict = scorer.score(List.of("GDP growth CPI"), "GDP and CPI", "");

        Double score = verdict.criterionScores().get("GDP growth CPI");
        assertThat(score)
                .isNotNull()
                .isLessThan(1.0)
                .isGreaterThan(0.5);
        // mutation-RED: count matches differently -> ratio changes -> RED
    }

    @Test
    void keywordScorer_noMatch_returnsZero() {
        var scorer = new VotingCriteriaVerifier.KeywordScorer();
        var verdict = scorer.score(List.of("inflation rate"), "GDP grew 5.2%", "");

        assertThat(verdict.criterionScores())
                .containsEntry("inflation rate", 0.0);
        // mutation-RED: return 1.0 on no match -> RED
    }

    @Test
    void keywordScorer_stopWordsOnly_returnsOne() {
        var scorer = new VotingCriteriaVerifier.KeywordScorer();
        var verdict = scorer.score(List.of("the of and"), "anything", "");

        assertThat(verdict.criterionScores())
                .containsEntry("the of and", 1.0);
        // mutation-RED: treat empty keywords as 0.0 -> RED
    }

    // ==================== RigorScorer unit tests ====================

    @Test
    void rigorScorer_highQualityOutput_returnsHighScore() {
        var scorer = new VotingCriteriaVerifier.RigorScorer();
        String output = "GDP grew by 5.2% and CPI rose 2.1%. "
                + "After 3 rounds the error is 0.5%. Target is 4.8%. "
                + "Therefore I recommend adjusting because the data supports this. "
                + "The trend suggests we should act quickly. "
                + "Overall this is a positive development across all sectors.";
        String history = "__probe_tool__ was used for verification";

        var verdict = scorer.score(List.of("test"), output, history);

        Double score = verdict.criterionScores().get("test");
        assertThat(score)
                .isNotNull()
                .isGreaterThan(0.5);
        // mutation-RED: remove tool evidence check -> lower score -> possible RED if below 0.5
        // mutation-RED: remove number counting -> lower score -> RED
    }

    @Test
    void rigorScorer_emptyOutput_returnsLowScore() {
        var scorer = new VotingCriteriaVerifier.RigorScorer();
        var verdict = scorer.score(List.of("test"), "", "");

        assertThat(verdict.criterionScores())
                .containsEntry("test", 0.0);
        // mutation-RED: return default positive -> RED
    }

    // ==================== Multi-criteria mixed ====================

    @Test
    void multiCriteria_onlyFailingCriteriaReturned() {
        // Criterion "A": weighted 0.85 >= 0.5 PASS
        // Criterion "B": weighted 0.20 < 0.5 FAIL
        var verifier = new VotingCriteriaVerifier(List.of(
                new VotingCriteriaVerifier.WeightedScorer(1.0,
                        perCriterionScorer(Map.of("A", 0.85, "B", 0.20), "S"))),
                0.5);

        List<Violation> result = verifier.verify(List.of("A", "B"), "output", "");

        assertThat(result)
                .hasSize(1);
        assertThat(result.get(0).criterion())
                .isEqualTo("B");
        // mutation-RED: return all criteria regardless of score -> two violations -> RED
    }
}
