/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.pev.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.agents.pev.agent.PEVAgent;
import com.openjiuwen.core.singleagent.schema.AgentCard;

import org.junit.jupiter.api.Test;

/**
 * Claims-adjudication real-LLM e2e — ported from the spring-ai-ascend reference
 * {@code RealLlmFusionE2eTest.fusionClaimsAdjudicationCompletes} (adapted to the PEV module,
 * reference not changed). Exercises the full Plan-Execute-Verify loop on a claim-review
 * business scenario against BigModel GLM via the shared {@link LlmClient}.
 *
 * <p>Wiring (only this test file is new — every collaborator below is read-only shared infra):
 * <ul>
 *   <li>{@link LlmPlanner} — LLM produces a multi-step plan; tool names from
 *       {@link ClaimTools#descriptions()} surface in the planning prompt so the model can
 *       emit TOOL_CALL nodes.</li>
 *   <li>{@link ToolBackedExecutor} — registers all 5 {@link ClaimTools} executors; nodes whose
 *       description names a tool route to it, others fall back to an LLM reasoning step.</li>
 *   <li>{@link LlmVerifier} — LLM judges PASS/FAIL of the assembled output against the task.</li>
 * </ul>
 *
 * <p>Honesty split (mirrors {@link PEVAgentRealLlmE2eTest}): mock tests carry the hard
 * control-flow断言; this e2e is soft-observe — real LLM in the loop, no brittle content断言.
 * Gated by {@code requireEnv} so CI without {@code OPENJIUWEN_*} skips cleanly.
 *
 * <p>Env required: {@code OPENJIUWEN_API_KEY} / {@code OPENJIUWEN_BASE_URL} /
 * {@code OPENJIUWEN_MODEL} (BigModel GLM, OpenAI-compatible).
 */
class ClaimsAdjudicationE2eTest {
    @Test
    void claimsAdjudicationRunsRealLlmPlanExecuteVerifyEndToEnd() {
        org.junit.jupiter.api.Assumptions.assumeTrue(LlmClient.envPresent(), "OPENJIUWEN_API_KEY 未设置，跳过真 LLM e2e");

        // Standard reducible claim case from the ClaimTools fixture (5 tools over 2 cases).
        String caseNo = ClaimTools.CLM_REDUCE;
        String task = "审核 " + caseNo + " 标准理赔：调用理赔工具核实案件信息、责任理算、欺诈风险，" + "计算正确赔付额并判断是否需要大额复核，最终给出理赔结论。";

        LlmClient llm = new LlmClient();

        // Planner aware of the 5 claim tools so it can emit TOOL_CALL nodes by name.
        LlmPlanner planner = new LlmPlanner(llm, ClaimTools.descriptions());

        // Executor with all 5 deterministic claim tools registered.
        ToolBackedExecutor executor = new ToolBackedExecutor(llm, ClaimTools.all());

        // LLM verifier — same PASS/FAIL convention as the one-step e2e (perception signal
        // stays comparable across scenarios).
        LlmVerifier verifier = new LlmVerifier(llm);

        PEVAgent agent = new PEVAgent(AgentCard.builder().build(), planner, executor, verifier);
        // Soft-skip on LLM infra outage (GLM 5-tool planner prompts can 0-byte-timeout at the
        // gateway) — honesty split: an unavailable endpoint is env, not a logic defect.
        String output = SoftLlmE2e.runSoft("claims-adjudication", () -> String.valueOf(agent.invoke(task, null)));

        // 软观察（真 LLM e2e 不硬断言具体金额/措辞，只证 PEV 数据通道端到端跑通 + 工具被驱动）。
        assertThat(output).isNotEmpty();
        // assembleOutput 把每个节点结果形如 "node-1: <value>" 拼接；至少有一个节点产出了内容。
        assertThat(output).contains(":");
        // 案号应当在工具回填的结果中出现（getClaimInfo 等工具的 fixture 都带 caseNo 字段）。
        assertThat(output).containsIgnoringCase(caseNo);
    }
}