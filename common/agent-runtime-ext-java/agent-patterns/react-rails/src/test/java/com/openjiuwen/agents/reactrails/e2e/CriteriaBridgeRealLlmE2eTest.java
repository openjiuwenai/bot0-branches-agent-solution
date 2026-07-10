/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.agents.reactrails.enforcing.ToolCallingEnforcingModel;
import com.openjiuwen.agents.reactrails.replan.ReplanRail;
import com.openjiuwen.agents.reactrails.verification.CriteriaReplanBridgeRail;
import com.openjiuwen.agents.reactrails.verification.RuleBasedCriteriaVerifier;
import com.openjiuwen.core.foundation.llm.model_clients.DefaultModelClientFactories;
import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.schema.AgentCard;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Real-LLM smoke test for criteria bridge steering and retry.
 */
class CriteriaBridgeRealLlmE2eTest {
    @Test
    void realLlmVerifyFailSteeringRetry() {
        org.junit.jupiter.api.Assumptions.assumeTrue(LlmClient.envPresent(), "skip");
        DefaultModelClientFactories.ensureRegistered();
        var cliCfg = ModelClientConfig.builder().clientId("bridge-e2e-" + System.nanoTime()).clientProvider("OpenAI")
                .apiKey(System.getenv("OPENJIUWEN_API_KEY")).apiBase(System.getenv("OPENJIUWEN_BASE_URL"))
                .verifySsl(false).build();
        var reqCfg = ModelRequestConfig.builder()
                .modelName(System.getenv().getOrDefault("OPENJIUWEN_MODEL", "deepseek-v4-flash")).temperature(0.3)
                .maxTokens(200).build();
        ToolCallingEnforcingModel model = new ToolCallingEnforcingModel(cliCfg, reqCfg);
        ReActAgent agent = new ReActAgent(AgentCard.builder().name("bridge-e2e").build());
        agent.setLlm(model);

        // BridgeRail: verify fail → steering + retry (max 2 retries)
        ReplanRail counter = new ReplanRail(2);
        // Set hard criteria: require specific numeric values an LLM rarely puts in '分析形势'
        agent.registerRail(
                new CriteriaReplanBridgeRail(new RuleBasedCriteriaVerifier(), List.of("GDP", "CPI", "通胀率"), counter));

        Object result = agent.invoke("分析当前的经济形势。请简短回答。", null);
        assertThat(result).isNotNull();
        assertThat(counter.replanCount()).isGreaterThanOrEqualTo(0);
    }
}
