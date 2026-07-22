/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.deepresearch.verify;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.agents.reactrails.types.Violation;
import com.openjiuwen.agents.reactrails.verification.RuleBasedCriteriaVerifier;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Bug #5 evidence — {@link RuleBasedCriteriaVerifier} is a shallow keyword-substring
 * matcher and is blind to semantics in four production-relevant ways. Each test below
 * pins one blind spot with the exact criteria + output shape verify-agent might see.
 *
 * <p>Algorithm being pinned (see verifier lines 43-66):
 * <pre>
 *   for each criterion:
 *     tokens = criterion.toLowerCase().split("[\\s,，、。.]+")
 *              .filter(len&gt;=2 &amp;&amp; !stopword)
 *     matched = tokens.filter(t -&gt; output.toLowerCase().contains(t)
 *                                || history.toLowerCase().contains(t)).count()
 *     violation iff matched &lt; ceil(N/2)
 * </pre>
 *
 * <p>Four blind spots, all reproducible without an LLM, all against verify-agent's
 * actual criteria list {@code ["对比矩阵已覆盖", "引用来源已覆盖", "置信度已覆盖"]}
 * (each criterion has no whitespace/punct → single token → threshold 1 →
 * pure literal substring gate):
 * <ol>
 *   <li>{@link #synonymBlindness_semanticallyEquivalentReportFailsVerification()} —
 *       false negative. A semantically-perfect report using synonyms ("比较表格" /
 *       "参考文献" / "可信度") is rejected.</li>
 *   <li>{@link #negationBlindness_explicitDenialStillPasses()} — false positive.
 *       Output that literally denies coverage but echoes the anchor phrase is
 *       accepted.</li>
 *   <li>{@link #existenceOnlyBlindness_emptyReportPassesWhenAnchorsEcho()} — false
 *       positive. An empty report that just echoes the three anchor labels PASSES
 *       — no quality check whatsoever.</li>
 *   <li>{@link #echoAttackBlindness_naiveRepeatOfAnchorsPasses()} — false positive.
 *       Literally emitting the 3 criteria strings back to back — no report content
 *       at all — PASSES.</li>
 * </ol>
 *
 * <p><b>Combined impact</b>: {@code CriteriaReplanBridgeRail} defaults to this
 * verifier ({@code CriteriaReplanBridgeRailTest} does likewise). Both directions
 * of failure are damaging:
 * <ul>
 *   <li>False negatives (Test 1) → high-quality reports trigger useless steer / degrade
 *       loops → wasted LLM cost + wrong "unmet_criteria" signal.</li>
 *   <li>False positives (Tests 2/3/4) → hallucinated / empty / adversarial-echo
 *       reports pass verification silently → the rail's "quality gate" contract
 *       is a facade.</li>
 * </ul>
 *
 * <p>The verifier's Javadoc already admits this ("Falls back to ASSUME_FAIL when a
 * criterion can't be checked by keyword — honest") but only handles the empty-keywords
 * corner. It does NOT gate against negation, echo, or synonyms — those all silently
 * pass through the substring check.
 *
 * <p><b>Current status</b>: all four tests PASS today because the bug is present.
 * When Bug #5 is fixed (LLM-backed verifier, or negation/echo detection), the
 * assertions must flip. See MD for suggested fix directions.
 *
 * @see <a href="../../../../../../../../ISSUE_BUG5_rule_based_verifier_semantic_blindness.md">ISSUE_BUG5</a>
 */
class Bug5RuleBasedVerifierSemanticBlindnessEvidenceTest {
    /** verify-agent's actual criteria list (see agent-verify-runtime application.yml). */
    private static final List<String> VERIFY_AGENT_CRITERIA = List.of("对比矩阵已覆盖", "引用来源已覆盖", "置信度已覆盖");

    private final RuleBasedCriteriaVerifier verifier = new RuleBasedCriteriaVerifier();

    @Test
    void synonymBlindness_semanticallyEquivalentReportFailsVerification() {
        // Given: a high-quality report using natural synonyms — comparison matrix as
        // 比较表格, references as 参考文献, confidence as 可信度. Semantically covers all
        // three anchors; only the literal phrasing differs from the criteria strings.
        String semanticallyPerfectOutput = "## 大模型 API 对比\n"
                + "### 比较表格\n"
                + "| 厂商 | 输入价 | context |\n"
                + "|---|---|---|\n"
                + "| DeepSeek | ¥1/M | 128K |\n"
                + "| GLM-5.2 | ¥2/M | 200K |\n"
                + "### 参考文献\n"
                + "[1] https://api.deepseek.com/pricing\n"
                + "[2] https://open.bigmodel.cn/pricing\n"
                + "### 可信度评估\n"
                + "0.85 — 两项均来自官网。";

        // When
        List<Violation> violations = verifier.verify(VERIFY_AGENT_CRITERIA, semanticallyPerfectOutput, "");

        // Then: verifier flags ALL THREE criteria as unmet — false negatives
        assertThat(violations)
                .as("all 3 anchors are semantically covered via synonyms; verifier still rejects")
                .hasSize(3);
        assertThat(violations).extracting(Violation::criterion)
                .containsExactlyInAnyOrder("对比矩阵已覆盖", "引用来源已覆盖", "置信度已覆盖");
    }

    @Test
    void negationBlindness_explicitDenialStillPasses() {
        // Given: output that literally repeats the anchor phrases while denying them.
        // A human reviewer would immediately fail this; the substring verifier cannot.
        String denyingOutput = "判定结果:\n"
                + "- 对比矩阵已覆盖: 否, 报告未提供任何对比表。\n"
                + "- 引用来源已覆盖: 否, 报告无任何引用。\n"
                + "- 置信度已覆盖: 否, 报告未标注置信度。\n";

        // When
        List<Violation> violations = verifier.verify(VERIFY_AGENT_CRITERIA, denyingOutput, "");

        // Then: verifier passes all three despite explicit denial — false positives
        assertThat(violations)
                .as("output literally denies coverage but echoes anchor phrases; verifier PASSES")
                .isEmpty();
    }

    @Test
    void existenceOnlyBlindness_emptyReportPassesWhenAnchorsEcho() {
        // Given: a completely hollow report — anchor labels present, zero content.
        // Common LLM failure mode: template compliance without substance.
        String hollowOutput = "对比矩阵已覆盖:\n（无）\n\n引用来源已覆盖:\n（无）\n\n置信度已覆盖:\n（无）\n";

        // When
        List<Violation> violations = verifier.verify(VERIFY_AGENT_CRITERIA, hollowOutput, "");

        // Then: hollow report PASSES — verifier is existence-only, not quality-aware
        assertThat(violations).as("report has zero content but echoes all 3 anchor labels; verifier PASSES").isEmpty();
    }

    @Test
    void echoAttackBlindness_naiveRepeatOfAnchorsPasses() {
        // Given: adversarial minimum — output is nothing but the three criteria strings
        // pasted together. If a jailbreaker knew this rail is in front, this is the
        // trivial bypass.
        String echoOutput = "对比矩阵已覆盖 引用来源已覆盖 置信度已覆盖";

        // When
        List<Violation> violations = verifier.verify(VERIFY_AGENT_CRITERIA, echoOutput, "");

        // Then: pure echo of criteria strings PASSES — the verifier's "gate" is
        // trivially satisfied by parroting the criteria list back
        assertThat(violations).as("output is 3 criteria strings concatenated — no report content; verifier PASSES")
                .isEmpty();
    }
}
