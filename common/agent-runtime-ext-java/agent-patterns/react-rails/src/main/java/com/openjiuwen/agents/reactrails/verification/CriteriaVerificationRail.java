/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.verification;

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

/**
 * Beta cognitive rail for ReActAgent — external-judge criteria verification gate.
 * <p>Hooks {@code afterModelCall}: when the LLM produces a final answer (no tool calls),
 * verifies it against success criteria using an injected {@link CriteriaVerifier}.
 * Uses {@code requestForceFinish} as a double-direction gate:
 * <ul>
 *   <li><b>Pass</b> → {@code forceFinish(verified=true)}: locks the correct terminal state.</li>
 *   <li><b>Fail</b> → {@code forceFinish(degraded=true, unmet=[...])}: honestly marks unmet criteria.</li>
 * </ul>
 * <p><b>Runtime-verified gate</b>: ReActAgent.invoke on agent-core-java 0.1.12 jar
 * consumes requestForceFinish at bytecode offset 225/700. Confirmed at runtime by
 * {@code SpikeForceFinishOnReActAgent}. This rail's forceFinish IS consumed → the gate
 * truly short-circuits the ReAct loop.
 * <p>Honest boundary: afterModelCall gate cannot <b>force correction</b> (pushSteering
 * can't redirect a final-answer terminal). It can only lock or degrade. Forced correction
eeds an outer shell (deferred).
 * <p><b>Porting simplification</b> (vs the spring-ai-ascend upstream variant):
 * 消除了 GoalSpec（→ List&lt;String&gt; successCriteria）、LLMDecision（→ 累积
 * tool-call 为 String decisionHistory）、Violation（→ 极简 record）。verify 签名从
 * verify(GoalSpec, List&lt;LLMDecision&gt;, LLMDecision.Complete) 改为
 * verify(List&lt;String&gt;, String, String)。语义等价但类型简化（jar 不含 beta.model 类型）。
 * @since 2026-07
 */
public class CriteriaVerificationRail extends AgentRail {

    public static final String OUTPUT_KEY = "output";
    public static final String VERIFIED_KEY = "criteria_verified";
    public static final String RESULT_KEY = "criteria_result";
    public static final String DEGRADED_KEY = "degraded";
    public static final String UNMET_KEY = "unmet_criteria";

    private final CriteriaVerifier verifier;
    private final List<String> successCriteria;
    private final List<String> decisionHistory = new ArrayList<>();

    /**
     * @param verifier         the external-judge verifier (use {@link RuleBasedCriteriaVerifier} for default)
     * @param successCriteria  the criteria to check against the final output
     */
    public CriteriaVerificationRail(CriteriaVerifier verifier, List<String> successCriteria) {
        this.verifier = verifier;
        this.successCriteria = List.copyOf(successCriteria);
    }

    /**
     * 模型回调钩子：终态答案走校验→forceFinish 双向门，工具轮则累积决策历史。
     */
    @Override
    public synchronized void afterModelCall(AgentCallbackContext ctx) {
        if (!(ctx.getInputs() instanceof ModelCallInputs inputs)) {
            return;
        }
        if (!(inputs.getResponse() instanceof AssistantMessage msg)) {
            return;
        }

        if (isFinalAnswer(msg)) {
            // Final answer → verify → forceFinish double-direction gate
            String output = contentOf(msg);
            String historyStr = String.join(" | ", decisionHistory);
            List<Violation> violations = verifier.verify(successCriteria, output, historyStr);

            if (violations.isEmpty()) {
                ctx.requestForceFinish(verifiedResult(output));
            } else {
                ctx.requestForceFinish(degradedResult(output, violations));
            }
        } else {
            // Tool-call round → accumulate history
            accumulateToolCalls(msg);
        }
    }

    private static boolean isFinalAnswer(AssistantMessage msg) {
        return msg.getToolCalls() == null || msg.getToolCalls().isEmpty();
    }

    private void accumulateToolCalls(AssistantMessage msg) {
        if (msg.getToolCalls() == null)
            return;
        for (ToolCall tc : msg.getToolCalls()) {
            decisionHistory.add(tc.getName() + "(" + tc.getArguments() + ")");
        }
    }

    private static String contentOf(AssistantMessage msg) {
        String content = msg.getContentAsString();
        return content != null ? content : "";
    }

    private static Map<String, Object> verifiedResult(String output) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(OUTPUT_KEY, output);
        result.put(VERIFIED_KEY, true);
        result.put(RESULT_KEY, "PASS");
        return result;
    }

    private static Map<String, Object> degradedResult(String output, List<Violation> violations) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(OUTPUT_KEY, output);
        result.put(VERIFIED_KEY, false);
        result.put(RESULT_KEY, "FAIL");
        result.put(DEGRADED_KEY, true);
        result.put(UNMET_KEY, violations.stream().map(v -> v.criterion() + ": " + v.reason()).toList());
        return result;
    }
}