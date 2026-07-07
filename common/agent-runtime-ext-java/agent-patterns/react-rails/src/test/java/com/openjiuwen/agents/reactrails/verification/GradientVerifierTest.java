/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.verification;

import com.openjiuwen.agents.reactrails.types.Violation;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GradientVerifier 承重测试 — 维度级梯度验证。
 *
 * <p>测试覆盖：
 * <ul>
 *   <li>全维度覆盖 → PASS（无 violations）</li>
 *   <li>部分覆盖 → isPartial=true + 精确定位已覆盖/缺失维度</li>
 *   <li>覆盖低于 floor → isPartial=false + 列出所有必需维度</li>
 *   <li>维度内多关键词 → dimensionThreshold 生效</li>
 *   <li>决策历史覆盖 → history 中匹配的关键词计入覆盖</li>
 *   <li>空/null 输入 → 优雅处理</li>
 *   <li>无标点 fallback → 单维度</li>
 * </ul>
 *
 * <p>mutation-RED:
 * <ul>
 *   <li>剥维度覆盖检查（永远 PASS）→ testAllDimensionsCovered 不失败 → RED</li>
 *   <li>剥 isPartial 分支（永远 true/false）→ 对应测试断言不成立 → RED</li>
 *   <li>剥 metadata 构建（mutation: 不 put isPartial）→ hint 降级 → RED</li>
 * </ul>
 */
class GradientVerifierTest {

    private final GradientVerifier verifier = new GradientVerifier();

    // ==================== Full coverage → PASS ====================

    @Test
    void allDimensionsCovered_returnsNoViolations() {
        List<Violation> result = verifier.verify(
                List.of("需要包含GDP、CPI和通胀率分析"),
                "GDP增长5%，CPI温和上涨，通胀率2%",
                null);

        assertThat(result)
                .as("all dimensions covered must return empty violations (PASS)")
                .isEmpty();
        // mutation-RED: strip dimension coverage check (always PASS) → violations
        // not empty → assertion fails → RED
    }

    // ==================== Partial coverage → isPartial=true ====================

    @Test
    void partialCoverage_returnsViolationWithIsPartialTrue() {
        List<Violation> result = verifier.verify(
                List.of("需要包含GDP、CPI和通胀率分析"),
                "GDP增长5%",  // only GDP dimension covered
                null);

        assertThat(result)
                .as("partial coverage must produce violations")
                .isNotEmpty();

        Violation v = result.get(0);
        assertThat(v.metadata())
                .as("partial coverage metadata must contain isPartial=true")
                .containsEntry("isPartial", true);

        @SuppressWarnings("unchecked")
        List<String> covered = (List<String>) v.metadata().get("covered");
        assertThat(covered)
                .as("covered dimensions must include GDP")
                .contains("需要包含gdp");

        @SuppressWarnings("unchecked")
        List<String> missing = (List<String>) v.metadata().get("missing");
        assertThat(missing)
                .as("missing dimensions must include CPI/通胀率 dimension")
                .contains("cpi和通胀率分析");

        assertThat((Double) v.metadata().get("coverage"))
                .as("partial coverage must have coverage >= floor (0.25)")
                .isGreaterThanOrEqualTo(GradientVerifier.DEFAULT_COVERAGE_FLOOR);

        assertThat(v.reason())
                .as("partial coverage reason must mention covered and missing")
                .contains("部分覆盖")
                .contains("需要包含gdp")
                .contains("cpi和通胀率分析");
        // mutation-RED: strip isPartial=true branch → metadata.isPartial=false
        // → assertion fails → RED
    }

    // ==================== Coverage below floor → isPartial=false ====================

    @Test
    void coverageBelowFloor_returnsViolationWithIsPartialFalse() {
        // 5 dimensions, only 1 covered → coverage = 0.2 < 0.25 floor
        List<Violation> result = verifier.verify(
                List.of("GDP、CPI、通胀率、货币政策、财政政策"),
                "gdp增长5%",  // only GDP covered (keyword "gdp")
                null);

        assertThat(result)
                .as("below-floor coverage must produce violations")
                .isNotEmpty();

        Violation v = result.get(0);
        assertThat(v.metadata())
                .as("below-floor coverage metadata must contain isPartial=false")
                .containsEntry("isPartial", false)
                .containsKey("allDimensions");

        assertThat((double) v.metadata().get("coverage"))
                .as("coverage 1/5=0.2 must be below floor 0.25")
                .isLessThan(GradientVerifier.DEFAULT_COVERAGE_FLOOR);

        assertThat(v.reason())
                .as("below-floor reason must mention total coverage need")
                .contains("覆盖不足");
        // mutation-RED: raise floor to 1.0 → becomes partial (isPartial=true)
        // → assertion on isPartial=false fails → RED
    }

    // ==================== Dimension threshold ====================

    @Test
    void multiKeywordDimension_notAllKeywordsMatched_dimensionNotCovered() {
        // "CPI和通胀率分析" dimension has keywords ["cpi", "通胀率"]
        // With dimensionThreshold=1.0, BOTH must appear for dimension to be covered
        List<Violation> result = verifier.verify(
                List.of("需要包含GDP、CPI和通胀率分析"),
                "gdp增长5%，cpi温和",  // "通胀率" missing → CPI dimension NOT covered
                null);

        assertThat(result)
                .as("dimension with partial keyword match must produce violation")
                .isNotEmpty();

        Violation v = result.get(0);
        @SuppressWarnings("unchecked")
        List<String> missing = (List<String>) v.metadata().get("missing");
        assertThat(missing)
                .as("CPI/通胀率 dimension must be missing when inflation keyword absent")
                .contains("cpi和通胀率分析");
        // mutation-RED: set dimensionThreshold to 0.5 → dimension covered despite
        // missing keyword → no violation or wrong coverage → RED
    }

    // ==================== Decision history coverage ====================

    @Test
    void keywordInDecisionHistory_countsAsCovered() {
        List<Violation> result = verifier.verify(
                List.of("需要包含GDP"),
                "summary of analysis",  // GDP not in output
                "called search('GDP forecast')");  // GDP in history

        assertThat(result)
                .as("keyword matched in decision history must satisfy coverage")
                .isEmpty();
        // mutation-RED: strip history from combined string → "gdp" not found
        // → violation produced → assertion fails → RED
    }

    // ==================== Null/empty inputs ====================

    @Test
    void nullOutput_handlesGracefully() {
        List<Violation> result = verifier.verify(
                List.of("包含GDP"),
                null,
                null);

        assertThat(result)
                .as("null output must produce violation (keyword not found in empty string)")
                .isNotEmpty();
    }

    @Test
    void nullDecisionHistory_handlesGracefully() {
        List<Violation> result = verifier.verify(
                List.of("GDP"),
                "something",
                null);

        // "gdp" not in "something" → violation expected
        assertThat(result)
                .as("null decision history must not cause NPE")
                .isNotEmpty();
    }

    @Test
    void emptyCriterion_skipped() {
        List<Violation> result = verifier.verify(
                List.of(""),
                "anything",
                null);

        assertThat(result)
                .as("empty criterion must be silently skipped")
                .isEmpty();
    }

    @Test
    void nullCriterion_skipped() {
        List<Violation> result = verifier.verify(
                Collections.singletonList(null),
                "anything",
                null);

        assertThat(result)
                .as("null criterion must be silently skipped")
                .isEmpty();
    }

    // ==================== Single dimension (no punctuation fallback) ====================

    @Test
    void singleDimension_noPunctuation_fallback() {
        List<Violation> result = verifier.verify(
                List.of("GDP分析"),
                "gdp增长5%",  // "gdp" is keyword, "分析" is stop word
                null);

        assertThat(result)
                .as("single dimension with keyword matched must pass")
                .isEmpty();
    }

    @Test
    void singleDimension_noPunctuation_failsWhenMissing() {
        List<Violation> result = verifier.verify(
                List.of("GDP分析"),
                "something else",
                null);

        assertThat(result)
                .as("single dimension with keyword missing must produce violation")
                .isNotEmpty();
    }

    // ==================== Multiple criteria ====================

    @Test
    void multipleCriteria_mixedResult() {
        List<Violation> result = verifier.verify(
                List.of("包含GDP", "包含CPI"),
                "GDP增长5%",  // only GDP covered
                null);

        assertThat(result)
                .as("mixed result must only report violations for unmet criteria")
                .hasSize(1);

        assertThat(result.get(0).criterion())
                .as("violation must be for unmet criterion")
                .isEqualTo("包含CPI");
    }

    // ==================== Metadata structure ====================

    @Test
    void violationMetadata_containsAllExpectedKeys() {
        List<Violation> result = verifier.verify(
                List.of("包含GDP、CPI"),
                "gdp",  // only GDP
                null);

        assertThat(result)
                .as("must produce violation for partial coverage")
                .isNotEmpty();

        Map<String, Object> meta = result.get(0).metadata();
        assertThat(meta)
                .as("violation metadata must contain all gradient fields")
                .containsOnlyKeys("coveredCount", "totalCount", "coverage",
                        "isPartial", "covered", "missing");
        // mutation-RED: strip metadata.put calls → metadata empty → assertion
        // on containsOnlyKeys fails → RED
    }

    @Test
    void belowFloorMetadata_containsAllDimensions() {
        List<Violation> result = verifier.verify(
                List.of("GDP、CPI、通胀率、货币政策、财政政策"),
                "gdp",  // 1/5 covered
                null);

        Map<String, Object> meta = result.get(0).metadata();
        assertThat(meta)
                .as("below-floor metadata must contain allDimensions")
                .containsKey("allDimensions");

        @SuppressWarnings("unchecked")
        List<String> allDims = (List<String>) meta.get("allDimensions");
        assertThat(allDims)
                .as("allDimensions must list all 5 dimensions")
                .hasSize(5);
        // mutation-RED: remove allDimensions from metadata → assertion fails → RED
    }

    // ==================== Custom thresholds ====================

    @Test
    void customDimensionThreshold_lowerThreshold_allowsPartialDimensionMatch() {
        // dimensionThreshold=0.5 → at least 1 of 2 keywords in "cpi和通胀率分析" needed
        GradientVerifier lenient = new GradientVerifier(0.5, 1.0, 0.25);

        List<Violation> result = lenient.verify(
                List.of("需要包含GDP、CPI和通胀率分析"),
                "gdp增长5%，cpi温和",  // "通胀率" missing, but "cpi" present
                null);

        // With threshold=0.5: "cpi和通胀率分析" has keywords ["cpi", "通胀率"]
        // matchCount=1/2=0.5 >= 0.5 → dimension covered
        // Both dimensions covered → PASS
        assertThat(result)
                .as("lower dimensionThreshold (0.5) accepts partial keyword match as covered")
                .isEmpty();
    }

    @Test
    void customCriterionPassThreshold_partialCoveragePasses() {
        // criterionPassThreshold=0.5 → at least 1 of 2 dimensions needed
        GradientVerifier lenient = new GradientVerifier(1.0, 0.5, 0.25);

        List<Violation> result = lenient.verify(
                List.of("包含GDP、CPI"),
                "gdp增长5%",  // only GDP (1/2=0.5 >= 0.5) → PASS
                null);

        assertThat(result)
                .as("lower criterionPassThreshold (0.5) accepts 1/2 dimensions as pass")
                .isEmpty();
    }
}
