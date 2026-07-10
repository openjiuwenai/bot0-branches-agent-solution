/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.e2e;

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
 * Real-LLM e2e: CriteriaVerificationRail on a real ReActAgent + real GLM-5.2.
 *
 * <p>Proves the full data channel: real LLM generates answer → CriteriaVerificationRail
 * verifies against criteria → requestForceFinish(verified/degraded) consumed by ReActAgent.
 *
 * <p>requireEnv gate: skips if OPENJIUWEN_* not set.
 */
class CriteriaVerificationRailRealLlmE2eTest {

    @Test
    void realLlmCriteriaRailVerifiesAnswer() {
        org.junit.jupiter.api.Assumptions.assumeTrue(LlmClient.envPresent(), "OPENJIUWEN_API_KEY 未设置，跳过真 LLM e2e");

        DefaultModelClientFactories.ensureRegistered();
        var cliCfg = ModelClientConfig.builder().clientId("react-rails-e2e-" + System.nanoTime())
                .clientProvider("OpenAI").apiKey(System.getenv("OPENJIUWEN_API_KEY"))
                .apiBase(System.getenv("OPENJIUWEN_BASE_URL")).verifySsl(false).build();
        String effectiveModel = System.getenv().getOrDefault("OPENJIUWEN_MODEL", "deepseek-v4-pro");
        var reqCfg = ModelRequestConfig.builder().modelName(effectiveModel).temperature(0.3).maxTokens(200).build();
        ToolCallingEnforcingModel model = new ToolCallingEnforcingModel(cliCfg, reqCfg);

        ReActAgent agent = new ReActAgent(AgentCard.builder().name("e2e-agent").build());
        agent.setLlm(model);

        // Register criteria rail — criteria that a good answer should meet
        agent.registerRail(
                new CriteriaVerificationRail(new RuleBasedCriteriaVerifier(), List.of("Plan", "Execute", "Verify")));

        // Task: ask the LLM to explain PEV (should produce keywords Plan/Execute/Verify)
        Object result = agent.invoke("用一句话解释 PEV（Plan-Execute-Verify）模式。回答中要包含 Plan、Execute、Verify 这三个词。", null);
        // Hard assertion: result MUST be a Map (proves forceFinish was consumed = channel live).
        // forceFinish on agent-core-java 0.1.12 swaps the return into a forcedMap; a String
        // result means the rail never fired / forceFinish was ignored — channel dead.
        assertThat(result).as("forceFinish must be consumed by ReActAgent → result is Map, not String")
                .isInstanceOf(Map.class);

        // Soft-observe (non-deterministic, no assert): which branch fired.
        Map<?, ?> map = (Map<?, ?>) result;
    }
}