/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.verification;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.core.foundation.llm.Model;
import com.openjiuwen.core.foundation.llm.model_clients.BaseModelClient;
import com.openjiuwen.core.foundation.llm.model_clients.DefaultModelClientFactories;
import com.openjiuwen.core.foundation.llm.output_parsers.BaseOutputParser;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessageChunk;
import com.openjiuwen.core.foundation.llm.schema.AudioGenerationResponse;
import com.openjiuwen.core.foundation.llm.schema.ImageGenerationResponse;
import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.foundation.llm.schema.UserMessage;
import com.openjiuwen.core.foundation.llm.schema.VideoGenerationResponse;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.ModelCallInputs;
import com.openjiuwen.core.singleagent.schema.AgentCard;

import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * CriteriaVerificationRail 承重测试 — on REAL ReActAgent (not mock).
 * Proves: (1) criteria pass → forceFinish(verified=true); (2) criteria fail → forceFinish(degraded).
 * mutation-RED: strip verify → rail doesn't forceFinish → invoke returns natural LLM response → RED.
 */
class CriteriaVerificationRailTest {
    static {
        DefaultModelClientFactories.ensureRegistered();
        Model.registerFactory(new TestModelClientFactory());
    }

    @Test
    void criteriaPassLocksVerifiedTerminal() {
        TestModelClient.stubResponse = "经过分析，给出配置建议：增配债券。引用风险评估：中等偏激进。";
        ReActAgent agent = newAgentWithStub();
        agent.registerRail(new CriteriaVerificationRail(new RuleBasedCriteriaVerifier(), List.of("给出配置建议", "引用风险评估")));

        Object result = agent.invoke("give advice", null);
        Map<?, ?> map = toMap(result);

        assertThat(map.get(CriteriaVerificationRail.VERIFIED_KEY)).isEqualTo(true);
        assertThat(map.get(CriteriaVerificationRail.RESULT_KEY)).isEqualTo("PASS");
        // mutation-RED: strip forceFinish(verifiedResult) → map null → RED
    }

    @Test
    void criteriaFailMarksDegradedTerminal() {
        TestModelClient.stubResponse = "I don't know";
        ReActAgent agent = newAgentWithStub();
        agent.registerRail(new CriteriaVerificationRail(new RuleBasedCriteriaVerifier(), List.of("给出配置建议", "引用风险评估")));

        Object result = agent.invoke("give advice", null);
        Map<?, ?> map = toMap(result);

        assertThat(map.get(CriteriaVerificationRail.VERIFIED_KEY)).isEqualTo(false);
        assertThat(map.get(CriteriaVerificationRail.DEGRADED_KEY)).isEqualTo(true);
        assertThat(map.get(CriteriaVerificationRail.RESULT_KEY)).isEqualTo("FAIL");
        assertThat((List<?>) map.get(CriteriaVerificationRail.UNMET_KEY)).isNotEmpty();
        // mutation-RED: strip forceFinish(degradedResult) → map null → RED
    }

    @Test
    void noRailInvokeReturnsNaturalAnswer() {
        TestModelClient.stubResponse = "natural answer";
        ReActAgent agent = newAgentWithStub();
        Object result = agent.invoke("test", null);
        assertThat(result).asString().contains("natural answer");
    }

    @Test
    void decisionHistoryDoesNotCrossInvocationContexts() {
        AtomicReference<String> observedHistory = new AtomicReference<>();
        CriteriaVerifier verifier = (criteria, output, history) -> {
            observedHistory.set(history);
            return List.of();
        };
        CriteriaVerificationRail rail = new CriteriaVerificationRail(verifier, List.of("criterion"));

        rail.afterModelCall(ctxWithToolCall("first_invocation_tool"));
        rail.afterModelCall(ctxWithFinalAnswer("second invocation answer"));

        assertThat(observedHistory.get()).as("a fresh invocation must not inherit prior tool history").isEmpty();
    }
    @SuppressWarnings("unchecked")
    private static Map<?, ?> toMap(Object result) {
        assertThat(result).isInstanceOf(Map.class);
        return (Map<?, ?>) result;
    }

    private static ReActAgent newAgentWithStub() {
        var cliCfg = ModelClientConfig.builder().clientId("test-" + System.nanoTime()).clientProvider("test-stub")
                .apiKey("dummy").apiBase("http://localhost:0").verifySsl(false).build();
        var reqCfg = ModelRequestConfig.builder().modelName("test").temperature(0.1).maxTokens(100).build();
        Model model = new Model(cliCfg, reqCfg);
        ReActAgent agent = new ReActAgent(AgentCard.builder().name("test").build());
        agent.setLlm(model);
        return agent;
    }

    private static AgentCallbackContext ctxWithToolCall(String toolName) {
        ToolCall toolCall = new ToolCall();
        toolCall.setName(toolName);
        toolCall.setArguments("{}");
        AssistantMessage message = new AssistantMessage();
        message.setToolCalls(List.of(toolCall));
        return ctxWithResponse(message);
    }

    private static AgentCallbackContext ctxWithFinalAnswer(String answer) {
        return ctxWithResponse(new AssistantMessage(answer));
    }

    private static AgentCallbackContext ctxWithResponse(AssistantMessage message) {
        ModelCallInputs inputs = new ModelCallInputs();
        inputs.setResponse(message);
        return AgentCallbackContext.builder().agent(new Object()).inputs(inputs).build();
    }
    static class TestModelClientFactory implements Model.ModelClientFactory {
        @Override
        public String providerName() {
            return "test-stub";
        }
        @Override
        public BaseModelClient create(ModelRequestConfig r, ModelClientConfig c) {
            return new TestModelClient(r, c);
        }
    }

    static class TestModelClient extends BaseModelClient {
        static volatile String stubResponse = "default";

        TestModelClient(ModelRequestConfig r, ModelClientConfig c) {
            super(r, c);
        }

        @Override
        public AssistantMessage invoke(Object msgs, Object tools, Float temp, Float maxTok, String model, Integer n,
                String stop, BaseOutputParser parser, Float topP, Map<String, Object> kwargs) {
            return new AssistantMessage(stubResponse);
        }
        @Override
        public Iterator<AssistantMessageChunk> stream(Object a, Object b, Float c, Float d, String e, Integer f,
                String g, BaseOutputParser h, Float i, Map<String, Object> j) {
            throw new UnsupportedOperationException();
        }
        @Override
        public ImageGenerationResponse generateImage(List<UserMessage> a, String b, String c, String d, int e,
                boolean isF, boolean isG, int h, Map<String, Object> i) {
            throw new UnsupportedOperationException();
        }
        @Override
        public AudioGenerationResponse generateSpeech(List<UserMessage> a, String b, String c, String d,
                Map<String, Object> e) {
            throw new UnsupportedOperationException();
        }
        @Override
        public VideoGenerationResponse generateVideo(List<UserMessage> a, String b, String c, String d, String e,
                String f, int g, boolean isH, boolean isI, String j, Integer k, Map<String, Object> l) {
            throw new UnsupportedOperationException();
        }
    }
}
