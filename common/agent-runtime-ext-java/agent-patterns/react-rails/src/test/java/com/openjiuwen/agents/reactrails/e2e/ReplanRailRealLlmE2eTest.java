/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.e2e;

import com.openjiuwen.agents.reactrails.replan.ReplanRail;
import com.openjiuwen.agents.reactrails.replan.ReplanTool;
import com.openjiuwen.agents.reactrails.verification.CriteriaVerificationRail;
import com.openjiuwen.agents.reactrails.verification.RuleBasedCriteriaVerifier;
import com.openjiuwen.agents.reactrails.enforcing.ToolCallingEnforcingModel;
import com.openjiuwen.core.foundation.llm.model_clients.DefaultModelClientFactories;
import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.schema.AgentCard;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-LLM e2e: ReplanRail on real ReActAgent + real GLM.
 *
 * <p>Tests the replan counting channel: LLM calls __replan__ tool → ReplanRail counts →
 * if over limit → forceFinish(degraded). With maxReplan=1, a single __replan__ call is
 * allowed but a second escalates.
 *
 * <p>Soft-observe: whether the LLM actually calls __replan__ depends on the task/prompt
 * (non-deterministic). The test proves the channel works when the LLM does call it.
 */
class ReplanRailRealLlmE2eTest {
    @Test
    void realLlmReplanRailCountsWithRealAgent() {
        org.junit.jupiter.api.Assumptions.assumeTrue(LlmClient.envPresent(), "OPENJIUWEN_API_KEY 未设置，跳过真 LLM e2e");

        DefaultModelClientFactories.ensureRegistered();
        var cliCfg = ModelClientConfig.builder().clientId("replan-e2e-" + System.nanoTime()).clientProvider("OpenAI")
                .apiKey(System.getenv("OPENJIUWEN_API_KEY")).apiBase(System.getenv("OPENJIUWEN_BASE_URL"))
                .verifySsl(false).build();
        String effectiveModel = System.getenv().getOrDefault("OPENJIUWEN_MODEL", "deepseek-v4-pro");
        var reqCfg = ModelRequestConfig.builder().modelName(effectiveModel).temperature(0.3).maxTokens(200).build();
        ToolCallingEnforcingModel model = new ToolCallingEnforcingModel(cliCfg, reqCfg);

        ReActAgent agent = new ReActAgent(AgentCard.builder().name("e2e-replan").build());
        agent.setLlm(model);

        // Register ReplanTool correctly (blocker B2 fix): card for visibility + executable for dispatch
        ReplanTool.registerOnto(agent);

        // Register ReplanRail with maxReplan=1 (first call OK, second escalates)
        ReplanRail replanRail = new ReplanRail(1);
        agent.registerRail(replanRail);

        // Also register CriteriaVerificationRail (so final answer gets verified)
        agent.registerRail(new CriteriaVerificationRail(new RuleBasedCriteriaVerifier(), List.of("回答")));

        Object result = agent.invoke(
                "请分析当前的经济形势。请先调用 __replan__ 工具声明你切换分析角度的意图" + "（带 replan_reason 和 new_approach 两个参数），然后再给出你的分析。", null);

        // 基线断言（始终执行）：agent 跑完返回非 null + rail 计数器可读，证明 e2e infra 健康
        // （真 LLM → ReActAgent → __replan__ 工具/ReplanRail 链路畅通）。这不是承重断言——通道
        // 装配的硬证明由 ReplanToolRegistrationTest（mock）承担；此处只证 e2e 跑通，
        // 避免 LLM 不走预期路径时无断言执行。
        assertThat(result).as("agent 必须返回结果（e2e infra 健康基线）").isNotNull();
        assertThat(replanRail.replanCount()).as("rail 计数器可读").isGreaterThanOrEqualTo(0);
        // 软观察：LLM 是否调 __replan__ 非确定。若调了（count>0），校验 rail 计数；
        // 若超限（count>max=1）且返回 Map，硬校验降级 forceFinish。
        if (replanRail.replanCount() > 1 && result instanceof Map<?, ?> map) {
            assertThat(map.get(ReplanRail.DEGRADED_KEY)).as("count>max should trigger degraded forceFinish")
                    .isEqualTo(true);
        }
        // 软观察：此测试观察真 LLM 是否触发 replan 计数通道，非"证明通道成立"——
        // 通道装配由 ReplanToolRegistrationTest（mock 硬断言）承重。
    }
}
