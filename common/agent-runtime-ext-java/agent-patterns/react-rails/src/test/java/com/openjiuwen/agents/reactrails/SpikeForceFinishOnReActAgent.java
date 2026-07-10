/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails;

import com.openjiuwen.core.foundation.llm.Model;
import com.openjiuwen.core.foundation.llm.model_clients.BaseModelClient;
import com.openjiuwen.core.foundation.llm.model_clients.DefaultModelClientFactories;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessageChunk;
import com.openjiuwen.core.foundation.llm.schema.AudioGenerationResponse;
import com.openjiuwen.core.foundation.llm.schema.ImageGenerationResponse;
import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.core.foundation.llm.schema.UserMessage;
import com.openjiuwen.core.foundation.llm.schema.VideoGenerationResponse;
import com.openjiuwen.core.foundation.llm.output_parsers.BaseOutputParser;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.AgentRail;
import com.openjiuwen.core.singleagent.schema.AgentCard;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPIKE GATE (承重铁律①): prove ReActAgent.invoke on gitcode's public agent-core-java
 * 0.1.12 jar TRULY consumes requestForceFinish fired in afterModelCall.
 *
 * javap -c proved bytecode offset 225/700 consumeForceFinish — this is the RUNTIME proof.
 * Pass = react-rails unblocked. Fail = structurally infeasible on this jar version.
 */
class SpikeForceFinishOnReActAgent {
    static {
        DefaultModelClientFactories.ensureRegistered();
        Model.registerFactory(new SpikeModelClientFactory());
    }

    @Test
    void afterModelCallForceFinishShortCircuitsInvoke() {
        var cliCfg = ModelClientConfig.builder().clientId("spike-" + System.nanoTime()).clientProvider("spike-stub")
                .apiKey("dummy").apiBase("http://localhost:0").verifySsl(false).build();
        var reqCfg = ModelRequestConfig.builder().modelName("spike-model").temperature(0.1).maxTokens(100).build();
        Model model = new Model(cliCfg, reqCfg);

        ReActAgent agent = new ReActAgent(AgentCard.builder().name("spike-agent").build());
        agent.setLlm(model);

        Map<String, Object> forcedMap = Map.of("spike", "FORCED", "output", "force-finish-consumed");
        agent.registerRail(new ForceFinishRail(forcedMap));

        Object result = agent.invoke("test input", null);
        assertThat(result).as("invoke must return the forcedMap from afterModelCall requestForceFinish").isNotNull();
        assertThat(String.valueOf(result))
                .as("result must contain 'FORCED' — proves consumeForceFinish short-circuited invoke")
                .contains("FORCED");
    }
    static class SpikeModelClientFactory implements Model.ModelClientFactory {
        @Override
        public String providerName() {
            return "spike-stub";
        }

        @Override
        public BaseModelClient create(ModelRequestConfig reqCfg, ModelClientConfig cliCfg) {
            return new SpikeModelClient(reqCfg, cliCfg);
        }
    }

    static class SpikeModelClient extends BaseModelClient {
        SpikeModelClient(ModelRequestConfig reqCfg, ModelClientConfig cliCfg) {
            super(reqCfg, cliCfg);
        }

        @Override
        public AssistantMessage invoke(Object messages, Object tools, Float temperature, Float maxTokens, String model,
                Integer n, String stop, BaseOutputParser parser, Float topP, Map<String, Object> kwargs) {
            return new AssistantMessage("natural-final-answer");
        }

        @Override
        public Iterator<AssistantMessageChunk> stream(Object messages, Object tools, Float temperature, Float maxTokens,
                String model, Integer n, String stop, BaseOutputParser parser, Float topP, Map<String, Object> kwargs) {
            throw new UnsupportedOperationException("spike: stream not supported");
        }

        @Override
        public ImageGenerationResponse generateImage(List<UserMessage> msgs, String a, String b, String c, int d,
                boolean isE, boolean isF, int g, Map<String, Object> h) {
            throw new UnsupportedOperationException("spike");
        }

        @Override
        public AudioGenerationResponse generateSpeech(List<UserMessage> msgs, String a, String b, String c,
                Map<String, Object> d) {
            throw new UnsupportedOperationException("spike");
        }

        @Override
        public VideoGenerationResponse generateVideo(List<UserMessage> msgs, String a, String b, String c, String d,
                String e, int f, boolean isG, boolean isH, String i, Integer j, Map<String, Object> k) {
            throw new UnsupportedOperationException("spike");
        }
    }
    static class ForceFinishRail extends AgentRail {
        private final Map<String, Object> forcedMap;
        private boolean hasFired = false;

        ForceFinishRail(Map<String, Object> forcedMap) {
            this.forcedMap = forcedMap;
        }

        @Override
        public void afterModelCall(AgentCallbackContext ctx) {
            if (!hasFired) {
                hasFired = true;
                ctx.requestForceFinish(forcedMap);
            }
        }
    }
}
