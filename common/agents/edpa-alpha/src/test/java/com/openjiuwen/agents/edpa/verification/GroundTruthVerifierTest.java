/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.verification;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.agents.reactrails.types.Violation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * GroundTruthVerifier bearing tests — deterministic-first layering (GEPA 确定性兜底).
 *
 * <p>IFF anchors: deterministic checker owns matched criteria (zero LLM, compute) →
 * keyword fallback only for unmatched. Mutation-RED: strip checker dispatch → matched
 * criterion falls through to keyword → keyword passes (false negative) → RED.
 *
 * @since 2026-07
 */
class GroundTruthVerifierTest {
    // 确定性 checker owns matched criterion → 算子校验（零 LLM），不走 keyword。
    @Test
    void deterministicCheckerOwnsMatchedCriterion() {
        DeterministicChecker amountChecker = new DeterministicChecker() {
            @Override
            public boolean matches(String criterion) {
                return criterion.contains("金额");
            }

            @Override
            public Violation check(String criterion, String output, String decisionHistory) {
                boolean valid = output != null && output.contains("100");
                return valid
                        ? null
                        : new Violation(criterion, "金额校验失败：output 无有效金额",
                                Map.of("covered", List.of(), "missing", List.of("金额")));
            }
        };
        GroundTruthVerifier v = new GroundTruthVerifier(List.of(amountChecker));

        assertThat(v.verify(List.of("金额计算"), "赔付 100 元", "")).as("确定性算子 pass（output 含 100）→ 无 violation").isEmpty();
        List<Violation> fail = v.verify(List.of("金额计算"), "无金额", "");
        assertThat(fail).as("确定性算子 fail → 1 violation").hasSize(1);
        assertThat(fail.get(0).reason()).contains("金额校验失败");
        // mutation-RED: strip checker dispatch → "金额计算" falls to keyword, "金额" in output
        // "金额计算" (criterion) keyword matches → false pass → RED
    }

    // 不 matches 的 criterion → keyword fallback。
    @Test
    void keywordFallbackForUnmatched() {
        DeterministicChecker amountOnly = new DeterministicChecker() {
            @Override
            public boolean matches(String criterion) {
                return criterion.contains("金额");
            }

            @Override
            public Violation check(String criterion, String output, String decisionHistory) {
                return null;
            }
        };
        GroundTruthVerifier v = new GroundTruthVerifier(List.of(amountOnly));

        List<Violation> violations = v.verify(List.of("营收 估值"), "无关内容", "");
        assertThat(violations).as("keyword fallback：关键词不足 → violation").hasSize(1);
    }

    // 无算子 → 纯 keyword（等价 RuleBasedCriteriaVerifier）。
    @Test
    void emptyCheckersPureKeyword() {
        GroundTruthVerifier v = new GroundTruthVerifier();
        assertThat(v.verify(List.of("营收 估值"), "营收 100 估值 合理", "")).as("纯 keyword：关键词覆盖 → pass").isEmpty();
    }

    // 确定性：同输入同输出（算子是 pure function，零随机）。
    @Test
    void checkerIsDeterministicSameInputSameVerdict() {
        DeterministicChecker c = new DeterministicChecker() {
            @Override
            public boolean matches(String criterion) {
                return true;
            }

            @Override
            public Violation check(String criterion, String output, String decisionHistory) {
                return output.contains("OK") ? null : new Violation(criterion, "fail");
            }
        };
        GroundTruthVerifier v = new GroundTruthVerifier(List.of(c));
        List<Violation> r1 = v.verify(List.of("c"), "OK", "");
        List<Violation> r2 = v.verify(List.of("c"), "OK", "");
        assertThat(r1).isEqualTo(r2).isEmpty();
    }

    // 多 criterion：部分算子 owned + 部分 keyword fallback，分层不串。
    @Test
    void mixedCriteriaLayeredCorrectly() {
        DeterministicChecker amountChecker = new DeterministicChecker() {
            @Override
            public boolean matches(String criterion) {
                return criterion.contains("金额");
            }

            @Override
            public Violation check(String criterion, String output, String decisionHistory) {
                return output.contains("100") ? null : new Violation(criterion, "金额不对");
            }
        };
        GroundTruthVerifier v = new GroundTruthVerifier(List.of(amountChecker));
        // "金额核对" → 算子（output 有 100 → pass）; "营收 估值" → keyword（output 无 → fail）
        List<Violation> violations = v.verify(List.of("金额核对", "营收 估值"), "赔付 100 元", "");
        assertThat(violations).as("金额算子 pass + 营收 keyword fail → 1 violation").hasSize(1);
        assertThat(violations.get(0).criterion()).contains("营收");
    }
}
