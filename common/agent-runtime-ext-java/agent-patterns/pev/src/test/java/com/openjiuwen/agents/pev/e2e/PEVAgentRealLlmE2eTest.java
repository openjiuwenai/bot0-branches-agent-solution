/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.pev.e2e;

import java.util.Locale;
import com.openjiuwen.agents.pev.agent.PEVAgent;
import com.openjiuwen.agents.pev.agent.PevComponents;
import com.openjiuwen.agents.pev.kernel.NodeResult;
import com.openjiuwen.agents.pev.kernel.PevKernel;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PEVAgent real-LLM e2e — proves the data channel (real LLM in the loop), not just the
 * mock control flow. Mirrors the honesty split from the spring-ai-ascend reference:
 * mock tests carry the hard control-flow断言; this e2e is soft-observe (real LLM, no
 * brittle content断言), gated by {@code requireEnv}.
 * <p>Env required: {@code OPENJIUWEN_API_KEY} / {@code OPENJIUWEN_BASE_URL} /
 * {@code OPENJIUWEN_MODEL} (BigModel GLM, OpenAI-compatible). Skipped via
 * {@link org.junit.jupiter.api.Assumptions#assumeTrue} when absent.
 */
class PEVAgentRealLlmE2eTest {

    private static final String LINE_SEPARATOR = System.lineSeparator();

    private static final LlmClient LLM = new LlmClient();

    private static boolean envPresent() {
        return LlmClient.envPresent();
    }

    @Test
    void pevAgentRunsRealLlmTaskEndToEndDataChannelProven() {
        org.junit.jupiter.api.Assumptions.assumeTrue(envPresent(), "OPENJIUWEN_API_KEY 未设置，跳过真 LLM e2e");

        String task = "用一句话（不超过30字）解释什么是 Plan-Execute-Verify 模式。";

        // Planner: 固定单节点 plan（节点描述 = 任务本身；单步任务 plan 结构平凡）
        PevComponents.Planner planner = in -> new PevComponents.Plan(task,
                List.of(new PevComponents.PlanNode("answer", in)));

        // Executor: 真调 LLM 执行节点
        PevComponents.Executor executor = nodes -> {
            String ans = LLM.chat(nodes.get(0).description());
            return Map.of("answer", new NodeResult.Success(ans));
        };

        // Verifier: 真调 LLM 判定 PASS/FAIL（数据通道二次证明）
        PevComponents.Verifier verifier = (in, r) -> {
            Object v = r.get("answer");
            String output = (v instanceof NodeResult.Success s) ? String.valueOf(s.value()) : "";
            String verdict = LLM.chat("判断以下回答是否满足要求。只回复 PASS 或 FAIL，不要其他内容。" + LINE_SEPARATOR + "要求：" + in
                    + LINE_SEPARATOR + "回答：" + output);
            boolean pass = verdict.toUpperCase(Locale.ROOT).contains("PASS")
                    && !verdict.toUpperCase(Locale.ROOT).contains("FAIL");
            return new PevKernel.VerifyResult(pass, pass ? Set.of() : Set.of("answer"), verdict);
        };

        PEVAgent agent = new PEVAgent(AgentCard.builder().build(), planner, executor, verifier);
        Object out = agent.invoke(task, null);

        // 软观察（真 LLM e2e 不硬断言具体内容，只证数据通道 + 控制流端到端跑通）
        assertThat(out).asString().isNotEmpty();
        assertThat(out.toString()).contains("answer:");
    }
}
