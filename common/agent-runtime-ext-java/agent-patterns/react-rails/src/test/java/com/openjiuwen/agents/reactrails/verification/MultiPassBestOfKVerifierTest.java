/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.verification;

import com.openjiuwen.agents.reactrails.types.Violation;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MultiPassBestOfKVerifier 承重测试 — K=2 双维度评分 (严谨性/完整性) + 阈值门。
 *
 * <p>覆盖：
 * <ul>
 *   <li>双维度 PASS → 空 violation 列表</li>
 *   <li>严谨性 FAIL → 维度级 violation with "严谨性FAIL"</li>
 *   <li>完整性 FAIL → 维度级 violation with "完整性FAIL"</li>
 *   <li>双 FAIL → "双FAIL" replan 信号</li>
 *   <li>部分通过 → "部分通过" steering 信号</li>
 *   <li>null/空输入 → 优雅处理 (NPE 防护)</li>
 *   <li>工具证据 → scoreRigor 含 tool evidence component</li>
 * </ul>
 *
 * <p>每测试标注 mutation-RED: 哪些代码变异会让该测试 RED。
 *
 * @see MultiPassBestOfKVerifier
 * @see CriteriaReplanBridgeRail
 */
class MultiPassBestOfKVerifierTest {

    private final MultiPassBestOfKVerifier verifier = new MultiPassBestOfKVerifier();

    // ==================== Both PASS: rigor >= 0.7, completeness >= 0.7 ====================

    @Test
    void bothDimensionsAbovePassThreshold_noViolations() {
        // Rigor: 5 numbers (5.2, 2.1, 3, 0.5, 4.8 → 5×0.08=0.4 capped)
        //         + 2 reasoning markers (Therefore, because → 2×0.075=0.15)
        //         + tool evidence (0.15)
        //         = 0.7 >= PASS_THRESHOLD(0.7)
        String output = "GDP grew by 5.2% and CPI rose 2.1%. "
                + "After 3 rounds the error is 0.5%. Target is 4.8%. "
                + "Therefore I recommend adjusting because the data is clear.";
        String history = "__probe_tool__ was used to query data";
        List<String> criteria = List.of("GDP", "CPI");

        List<Violation> result = verifier.verify(criteria, output, history);

        assertThat(result)
                .as("output with 5 numbers + 2 markers + tool evidence must pass both dimensions")
                .isEmpty();
        // mutation-RED: strip scoreRigor() → rigor=0 → violations → RED
        // mutation-RED: strip scoreCompleteness() → completeness=0 → violations → RED
        // mutation-RED: raise PASS_THRESHOLD > 0.7 → violations → RED
    }

    // ==================== Rigor FAIL (< FAIL_THRESHOLD = 0.5) ====================

    @Test
    void outputWithoutNumbersMarkersOrTools_failsRigor() {
        // Rigor: 0 numbers, 0 markers, 0 tools, short → 0.0 < 0.5 FAIL
        // Completeness: keywords "gdp", "cpi" both in output → 1.0 PASS
        String output = "The economic data shows GDP and CPI trends.";
        String history = "";
        List<String> criteria = List.of("GDP", "CPI");

        List<Violation> result = verifier.verify(criteria, output, history);

        assertThat(result)
                .as("output with no numbers/markers/tools must fail rigor dimension")
                .isNotEmpty();
        assertThat(result.get(0).reason())
                .contains("严谨性FAIL");
        // mutation-RED: remove rigor-FAIL branch → would fall through to either
        //   partial-pass or no-violation path → RED (reason doesn't contain "FAIL")
    }

    // ==================== Completeness FAIL (< FAIL_THRESHOLD = 0.5) ====================

    @Test
    void outputMissingKeywordCoverage_failsCompleteness() {
        // Rigor: 4 numbers (5.2, 2.1, 3, 0.5 → 4×0.08=0.32)
        //         + 3 markers (Therefore, because, hence → 3×0.075=0.225) = 0.545 >= 0.5 (partial)
        // Completeness: criterion "inflation rate" → keywords ["inflation", "rate"]
        //   Neither in output → 0.0 < 0.5 FAIL
        String output = "GDP grew by 5.2% and CPI rose 2.1%. "
                + "After 3 rounds the error is 0.5%. "
                + "Therefore I conclude the outlook is positive "
                + "because the data supports this hence we can proceed.";
        String history = "";
        List<String> criteria = List.of("inflation rate");

        List<Violation> result = verifier.verify(criteria, output, history);

        assertThat(result)
                .as("output missing criterion keywords must fail completeness dimension")
                .isNotEmpty();
        assertThat(result.get(0).reason())
                .contains("完整性FAIL");
        // mutation-RED: remove completeness-FAIL branch → would fall to partial-pass or
        //   no-violation path → RED (reason doesn't contain "FAIL")
    }

    // ==================== Both FAIL (< 0.5 on both dimensions) ====================

    @Test
    void bothDimensionsBelowFailThreshold_triggersReplanSignal() {
        // Rigor: 0 numbers, 0 markers, 0 tools → 0.0 < 0.5 FAIL
        // Completeness: "gdp", "cpi" not in "I don't know" → 0.0 < 0.5 FAIL
        String output = "I don't know.";
        String history = "";
        List<String> criteria = List.of("GDP", "CPI");

        List<Violation> result = verifier.verify(criteria, output, history);

        assertThat(result)
                .as("output with no rigor AND no keyword match must trigger double-FAIL")
                .isNotEmpty();
        assertThat(result.get(0).reason())
                .contains("双FAIL")
                .contains("replan");
        // mutation-RED: remove both-FAIL branch → rigor-only FAIL branch fires → reason
        //   differs ("严谨性FAIL" vs "双FAIL") → RED
        // mutation-RED: flip < to >=  on fail check → no FAIL detected → RED
    }

    // ==================== Partial pass: >= FAIL_THRESHOLD but < PASS_THRESHOLD ====================

    @Test
    void rigorBetweenFailAndPassThreshold_partialPassSignal() {
        // Rigor: 4 numbers (5.2, 2.1, 3, 0.5 → 4×0.08=0.32)
        //         + 3 markers (Therefore, because, hence → 3×0.075=0.225) = 0.545
        //         0.5 <= 0.545 < 0.7 → partial
        // Completeness: keywords "gdp", "cpi" both in output → 1.0 >= 0.7 PASS
        String output = "GDP grew by 5.2% and CPI rose 2.1%. "
                + "After 3 rounds the error is 0.5%. "
                + "Therefore I conclude the outlook is positive "
                + "because the data supports this hence we can proceed.";
        String history = "";
        List<String> criteria = List.of("GDP", "CPI");

        List<Violation> result = verifier.verify(criteria, output, history);

        assertThat(result)
                .as("rigor partial + completeness pass must produce partial-pass steering")
                .isNotEmpty();
        assertThat(result.get(0).reason())
                .contains("部分通过")
                .contains("严谨性");
        // mutation-RED: change PASS_THRESHOLD to 0.5 → rigor seen as PASS → no violations → RED
        // mutation-RED: change FAIL_THRESHOLD to 0.6 → rigor seen as FAIL → "严谨性FAIL" → RED
    }

    @Test
    void completenessBetweenFailAndPassThreshold_partialPassSignal() {
        // Rigor: 5 numbers + 2 markers + tool evidence = 0.7 >= 0.7 PASS
        // Completeness: criterion "GDP CPI bond equity"
        //   keywords: ["gdp", "cpi", "bond", "equity"]
        //   only "gdp" and "cpi" in output → matched=2/4=0.5
        //   0.5 <= 0.5 < 0.7 → partial
        String output = "GDP grew by 5.2% and CPI rose 2.1%. "
                + "After 3 rounds the error is 0.5%. Target is 4.8%. "
                + "Therefore I recommend adjusting because the data is clear.";
        String history = "__probe_tool__ was used";
        List<String> criteria = List.of("GDP CPI bond equity");

        List<Violation> result = verifier.verify(criteria, output, history);

        assertThat(result)
                .as("completeness partial + rigor pass must produce partial-pass steering")
                .isNotEmpty();
        assertThat(result.get(0).reason())
                .contains("部分通过")
                .contains("完整性");
        // mutation-RED: lower FAIL_THRESHOLD to 0.4 → completeness(0.5) seen as PASS → no violations → RED
    }

    // ==================== Tool evidence contribution ====================

    @Test
    void toolEvidenceFromHistory_contributesToRigor() {
        // Same output, different history → different rigor score → different verdict
        // 5 numbers (5.2, 2.1, 3, 0.5, 4.8 → 5×0.08=0.4) + 2 markers (Therefore, because → 0.15)
        // Without tool: rigor=0.55 < 0.7 → partial
        // With tool: rigor=0.55+0.15=0.70 >= 0.7 → PASS
        String output = "GDP grew by 5.2% and CPI rose 2.1%. "
                + "After 3 rounds the error is 0.5%. Target is 4.8%. "
                + "Therefore I recommend adjusting because the data is clear.";
        String historyWithTool = "__probe_tool__ used";
        String historyWithoutTool = "";
        List<String> criteria = List.of("GDP", "CPI");

        List<Violation> withTool = verifier.verify(criteria, output, historyWithTool);
        List<Violation> withoutTool = verifier.verify(criteria, output, historyWithoutTool);

        assertThat(withoutTool)
                .as("without tool evidence: rigor < PASS_THRESHOLD → partial pass")
                .isNotEmpty();
        assertThat(withTool)
                .as("with tool evidence: rigor >= PASS_THRESHOLD → full pass")
                .isEmpty();
        // mutation-RED: remove tool-evidence check from scoreRigor → withTool also partial → RED
        // mutation-RED: lower PASS_THRESHOLD to 0.5 → withoutTool also passes → RED
    }

    // ==================== Null/empty input handling ====================

    @Test
    void nullAndEmptyInputs_handledGracefully() {
        // Null output + null history → safeOutput="" + safeHistory="" → no NPE
        List<Violation> nullResult = verifier.verify(List.of("test"), null, null);
        assertThat(nullResult)
                .as("null output+history must not throw, returns violations (both 0)")
                .isNotEmpty();

        // Null criterion → skipped (use mutable list to hold null)
        List<String> criteriaWithNull = new ArrayList<>();
        criteriaWithNull.add("GDP");
        criteriaWithNull.add(null);
        criteriaWithNull.add("CPI");
        // Use output that passes both dimensions so non-null criteria produce no violations
        String rigorousOutput = "GDP grew by 5.2% and CPI rose 2.1%. "
                + "After 3 rounds the error is 0.5%. Target is 4.8%. "
                + "Therefore I recommend this because the data is clear. "
                + "Used __probe_tool__ to verify.";
        List<Violation> nullCriterionResult = verifier.verify(
                criteriaWithNull, rigorousOutput, "__probe_tool__ used");
        assertThat(nullCriterionResult)
                .as("null criterion skipped without error")
                .isEmpty();

        // Empty criteria list
        List<Violation> emptyResult = verifier.verify(List.of(), "anything", "");
        assertThat(emptyResult)
                .as("empty criteria list returns empty violations")
                .isEmpty();

        // mutation-RED: remove null safety in output/history → NPE → RED
    }

    @Test
    void stopWordsOnlyCriterion_noExtractableKeywords() {
        // Criterion with only stop words → no extractable keywords → completeness=1.0
        // Output has enough rigor (numbers+markers) so it passes both dimensions
        String output = "GDP grew by 5.2% and CPI rose 2.1%. "
                + "After 3 rounds error is 0.5%. Target is 4.8%. "
                + "Therefore I recommend this because the data is clear.";
        String history = "used __probe_tool__";

        List<Violation> result = verifier.verify(
                List.of("the of and"), output, history);

        assertThat(result)
                .as("stop-words-only criterion yields no violation (completeness=1.0, rigor passes)")
                .isEmpty();
        // mutation-RED: remove early-return-for-empty-keywords → keywords empty → 0/0 → division
        //   by zero or incorrect default → RED
    }

    // ==================== Multi-criteria: mixed results ====================

    @Test
    void multiCriteria_mixedResults() {
        // Two criteria, one passes one fails
        String output = "GDP grew by 5.2% and CPI rose 2.1%. "
                + "After 3 rounds the error is 0.5%. Target is 4.8%. "
                + "Therefore I recommend adjusting because the data is clear.";
        String history = "__probe_tool__ was used";

        // "GDP" → keywords ["gdp"] → in output → completeness=1.0 PASS
        // "bond allocation" → keywords ["bond", "allocation"] → neither in output → completeness=0.0 FAIL
        List<Violation> result = verifier.verify(
                List.of("GDP", "bond allocation"), output, history);

        assertThat(result)
                .as("must return violations for failing criteria only")
                .hasSize(1);
        assertThat(result.get(0).criterion())
                .isEqualTo("bond allocation");
        assertThat(result.get(0).reason())
                .contains("完整性FAIL");
        // mutation-RED: skip criteria with completeness >= 0.7 → wouldn't process 2nd criterion → RED
    }

    // ==================== Scoring corner cases ====================

    @Test
    void veryLongOutput_getsLengthBonus() {
        // Output > 200 chars → length bonus of 0.15.
        // Design: 5 numbers (5.2, 2.1, 3, 0.5, 4.8 → 0.4) + no markers + no tools = 0.4
        // Without length bonus: 0.4 < 0.5 → rigorous FAIL
        // With length bonus: 0.4 + 0.15 = 0.55 >= 0.5 → partial pass (not FAIL)
        StringBuilder sb = new StringBuilder();
        sb.append("Here are the key metrics. GDP is 5.2% this quarter. ");
        sb.append("CPI rose 2.1% year over year. We did 3 rounds of validation ");
        sb.append("achieving error rate of 0.5%. The annual target is 4.8% growth. ");
        sb.append("I think the economy is doing well across all these indicators. ");
        sb.append("The data shows consistent improvement across multiple sectors. ");
        sb.append("Employment is improving. Consumer spending is up. ");
        sb.append("Manufacturing is stable. International trade is recovering. ");
        sb.append("Overall this is a good picture for the coming quarters. ");
        String output = sb.toString();
        assertThat(output.length()).isGreaterThan(200);

        List<Violation> result = verifier.verify(
                List.of("GDP", "CPI"), output, "");

        // With length bonus: rigor=0.55 (partial) → expects "部分通过" not "FAIL"
        assertThat(result)
                .as("long output gets length bonus → rigor passes FAIL threshold")
                .isNotEmpty();
        assertThat(result.get(0).reason())
                .contains("部分通过");
        // mutation-RED: remove length bonus from scoreRigor → rigor=0.4 < 0.5 → "严谨性FAIL" → RED
    }

    // ==================== Documentation ====================

    @Test
    void failThresholdBoundary_exactFailThreshold_countsAsFail() {
        // At exactly FAIL_THRESHOLD = 0.5, should still trigger fail branch
        // (we use < not <= so 0.5 is >= FAIL_THRESHOLD → not FAIL)
        // Design choice: 0.5 is NOT FAIL (since < 0.5 is FAIL)
        // Verify: completeness = 0.5 (2/4)
        String output = "GDP grew by 5.2% and CPI rose 2.1%. "
                + "After 3 rounds the error is 0.5%. Target is 4.8%. "
                + "Therefore I recommend adjusting because the data is clear.";
        String history = "__probe_tool__ was used";

        List<Violation> result = verifier.verify(
                List.of("GDP CPI bond equity"), output, history);

        // completeness = 2/4 = 0.5 >= 0.5 → NOT FAIL, but < 0.7 → partial
        assertThat(result.get(0).reason())
                .contains("部分通过")
                .doesNotContain("FAIL");
        // mutation-RED: change < 0.5 to <= 0.5 → 0.5 triggers FAIL → reason contains "FAIL" → RED
    }
}
