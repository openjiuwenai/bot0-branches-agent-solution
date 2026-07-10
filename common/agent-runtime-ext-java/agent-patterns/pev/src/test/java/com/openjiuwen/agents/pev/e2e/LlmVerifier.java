/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.pev.e2e;

import com.openjiuwen.agents.pev.agent.PevComponents;
import com.openjiuwen.agents.pev.kernel.NodeResult;
import com.openjiuwen.agents.pev.kernel.PevKernel;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * LLM-backed Verifier — asks the LLM to judge PASS/FAIL for the whole task against the
 * combined goal + executed output, then folds the verdict into a structured
 * {@link PevKernel.VerifyResult}.
 *
 * <p><b>Verdict parsing (honesty edge):</b>
 * <ul>
 *   <li>{@code passed = contains "PASS" AND not contains "FAIL"} — same convention as the
 *       existing {@link PEVAgentRealLlmE2eTest} one-step e2e, kept consistent so the kernel's
 *       perception signal is comparable across scenarios.</li>
 *   <li>Empty/whitespace-only verdict ⇒ {@code parseFailure=true} (PerceptionUnreliable
 *       signal in {@link PevKernel}), so the kernel dispatches {@code AcceptPartial} rather
 *       than trusting a vacuous verdict.</li>
 *   <li>{@code failedNodes} on FAIL = all nodes whose {@link NodeResult} is not
 *       {@link NodeResult.Success}, plus (if all succeeded) every node — the verifier is
 *       judging the whole task, so a content failure with no structural failure implicates
 *       the produced content of every node.</li>
 * </ul>
 */
final class LlmVerifier implements PevComponents.Verifier {

    private static final String LINE_SEPARATOR = System.lineSeparator();

    private final LlmClient llm;

    LlmVerifier(LlmClient llm) {
        this.llm = llm;
    }

    @Override
    public PevKernel.VerifyResult verify(String userInput, Map<String, NodeResult> completed) {
        String output = assembleOutput(completed);
        String verdict = llm.chat("判断以下任务的执行结果是否满足要求。只回复 PASS 或 FAIL，不要其他内容。" + LINE_SEPARATOR + "任务要求：" + userInput
                + LINE_SEPARATOR + "执行结果：" + output);

        String up = verdict == null ? "" : verdict.toUpperCase(Locale.ROOT);
        boolean parseFailure = up.isBlank();
        boolean pass = !parseFailure && up.contains("PASS") && !up.contains("FAIL");

        if (pass) {
            return new PevKernel.VerifyResult(true, Set.of(), verdict);
        }
        if (parseFailure) {
            return new PevKernel.VerifyResult(false, Set.of(), verdict, true);
        }
        Set<String> failed = collectFailedNodes(completed);
        return new PevKernel.VerifyResult(false, failed, verdict);
    }

    /** Concatenate Success values; structural failures surfaced inline so the LLM can see them. */
    private static String assembleOutput(Map<String, NodeResult> completed) {
        if (completed == null || completed.isEmpty()) {
            return "(无执行结果)";
        }
        StringBuilder sb = new StringBuilder();
        completed.forEach((id, r) -> {
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append('[').append(id).append("] ");
            if (r instanceof NodeResult.Success s) {
                sb.append(s.value());
            } else if (r instanceof NodeResult.DeviceFailure d) {
                sb.append("设备故障: ").append(d.error());
            } else if (r instanceof NodeResult.VerifierFailure vf) {
                sb.append("验证失败: ").append(vf.reason());
            } else {
                sb.append(r);
            }
        });
        return sb.toString();
    }

    /**
     * On a FAIL verdict: prefer structurally-failed nodes; if none (pure content failure
     * against the goal), implicates all executed nodes.
     */
    private static Set<String> collectFailedNodes(Map<String, NodeResult> completed) {
        Set<String> failed = new LinkedHashSet<>();
        for (var e : completed.entrySet()) {
            if (!(e.getValue() instanceof NodeResult.Success)) {
                failed.add(e.getKey());
            }
        }
        if (failed.isEmpty()) {
            failed.addAll(completed.keySet());
        }
        return failed;
    }
}
