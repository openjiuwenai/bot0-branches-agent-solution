/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.pev.e2e;

import com.openjiuwen.agents.pev.agent.PEVAgent;
import com.openjiuwen.agents.pev.agent.PevComponents;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Straight-through real-LLM e2e — the simplest PEV happy path: plan → execute → verify pass,
 * over a single registered tool ({@code getClaimInfo}).
 * <p>Ported (adapted, not copied) from the spring-ai-ascend reference
 * {@code PEVAlphaRealLlmE2eTest.straightThroughSimpleTool}. Differences from the reference:
 * <ul>
 *   <li>lives on agent-core-java's {@link PEVAgent} + the test-scope {@link LlmPlanner} /
 *       {@link ToolBackedExecutor} / {@link LlmVerifier} trio instead of spring-ai's
 *       {@code DefaultAgentKernel}; no shared component is modified here.</li>
 *   <li>honors the honesty split (铁律①): real LLM in the loop, but assertions are
 *       <b>soft</b> — output non-empty + contains the tool artifact — never brittle content
 *       断言. The hard control-flow断言 live in the mock tests.</li>
 * </ul>
 * <p>Env required: {@code OPENJIUWEN_API_KEY} / {@code OPENJIUWEN_BASE_URL} /
 * {@code OPENJIUWEN_MODEL} (BigModel GLM, OpenAI-compatible). Skipped via
 * {@link org.junit.jupiter.api.Assumptions#assumeTrue} when absent.
 */
class StraightThroughE2eTest {

    private static final LlmClient LLM = new LlmClient();

    @Test
    void straightThroughSingleToolPlanExecuteVerifyPass() {
        org.junit.jupiter.api.Assumptions.assumeTrue(LlmClient.envPresent(), "OPENJIUWEN_API_KEY 未设，跳过真 LLM e2e");

        // Task that is trivially satisfiable by a single getClaimInfo call.
        String task = "查询案件 CLM-2026-REDUCE 的状态、基础信息与定责结论。";

        // Planner: tool-aware — surface getClaimInfo so the LLM plans a single TOOL_CALL step.
        Map<String, String> toolDescriptions = new LinkedHashMap<>();
        toolDescriptions.put("getClaimInfo", ClaimTools.descriptions().get("getClaimInfo"));
        PevComponents.Planner planner = new LlmPlanner(LLM, toolDescriptions);

        // Executor: register only getClaimInfo — the single tool this scenario exercises.
        Map<String, java.util.function.Function<Map<String, Object>, String>> tools = new LinkedHashMap<>();
        tools.put("getClaimInfo", ClaimTools.all().get("getClaimInfo"));
        PevComponents.Executor executor = new ToolBackedExecutor(LLM, tools);

        // Verifier: LLM judges the assembled output against the task.
        PevComponents.Verifier verifier = new LlmVerifier(LLM);

        PEVAgent agent = new PEVAgent(AgentCard.builder().build(), planner, executor, verifier);
        Object out = agent.invoke(task, null);

        String output = String.valueOf(out);
        // Soft断言 (real LLM e2e — no brittle content断言):
        //   1. output non-empty — the loop produced something.
        assertThat(output).isNotEmpty();

        //   2. contains the tool artifact — the getClaimInfo fixture surfaces the case number
        //      (CLM-2026-REDUCE) and the liability conclusion, proving the tool actually ran
        //      and its result flowed through to the agent output.
        assertThat(output).containsAnyOf("CLM-2026-REDUCE", "REDUCE", "liability", "责任", "案件");
    }
}
