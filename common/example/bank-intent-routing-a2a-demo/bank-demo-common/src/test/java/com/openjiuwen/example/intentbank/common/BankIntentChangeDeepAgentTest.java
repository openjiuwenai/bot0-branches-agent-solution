/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.intentbank.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.core.foundation.llm.Model;
import com.openjiuwen.core.foundation.llm.model_clients.BaseModelClient;
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
import com.openjiuwen.core.controller.schema.ControllerOutputChunk;
import com.openjiuwen.core.controller.schema.EventType;
import com.openjiuwen.core.session.checkpointer.CheckpointerFactory;
import com.openjiuwen.core.session.checkpointer.InMemoryCheckpointer;
import com.openjiuwen.core.session.AgentSessionApi;
import com.openjiuwen.core.session.stream.OutputSchema;
import com.openjiuwen.core.session.stream.StreamMode;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.example.intentbank.common.BankInterruptRails.ConfirmationRail;
import com.openjiuwen.example.intentbank.common.BankInterruptRails.IntentChangeTerminationRail;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.factory.HarnessFactory;
import com.openjiuwen.harness.schema.config.DeepAgentConfig;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Exercises intent change through the actual DeepAgent interrupt and resume path. */
class BankIntentChangeDeepAgentTest {
    private static final String PROVIDER = "BankIntentChangeTest";

    @TempDir
    Path workspace;

    @BeforeAll
    static void registerModelClient() {
        Model.registerFactory(new IntentChangeModelFactory());
    }

    @Test
    void interruptedTransferReturnsIntentChangeResult() {
        CheckpointerFactory.setDefaultCheckpointer(new InMemoryCheckpointer());
        String sessionId = "bank-intent-change-test";
        DeepAgent agent = HarnessFactory.createDeepAgent(
                AgentCard.builder().id("bank-intent-change-agent").name("BankIntentChangeAgent")
                        .description("intent change regression agent").build(),
                DeepAgentConfig.builder().systemPrompt("Always execute one transfer.")
                        .workspacePath(workspace.toString())
                        .enableTaskLoop(true).maxIterations(4).completionTimeout(10D)
                        .tools(List.of(BankTools.transfer()))
                        .rails(List.of(new ConfirmationRail(BankTools.TRANSFER,
                                "请确认是否向{recipient}转账{amount}元。"), new IntentChangeTerminationRail()))
                        .build(),
                null);
        agent.ensureInitialized();
        agent.getAgent().setLlm(new Model(
                ModelClientConfig.builder().clientId("intent-change-test").clientProvider(PROVIDER)
                        .apiKey("test-key").apiBase("test://intent-change").verifySsl(false).build(),
                ModelRequestConfig.builder().modelName("intent-change-test-model").build()));
        AgentSessionApi session = AgentSessionApi.create(sessionId, null, agent.getCard());

        List<Object> interrupted = collect(agent.stream(Map.of("query", "给王五转账50元", "conversation_id", sessionId),
                session, List.of(StreamMode.OUTPUT)));
        assertThat(hasInteraction(interrupted)).isTrue();

        List<Object> resumed = collect(agent.stream(
                Map.of("query", "改为购买1000元稳盈90天理财", "conversation_id", sessionId), session,
                List.of(StreamMode.OUTPUT)));
        String rendered = renderPayloads(resumed);
        assertThat(rendered).contains("INTENT_CHANGED", "改为购买1000元稳盈90天理财")
                .doesNotContain("task_failed");
    }

    private static List<Object> collect(Iterator<Object> stream) {
        List<Object> chunks = new ArrayList<>();
        stream.forEachRemaining(chunks::add);
        return chunks;
    }

    private static boolean hasInteraction(List<Object> chunks) {
        for (Object chunk : chunks) {
            if (chunk instanceof OutputSchema output && "__interaction__".equals(output.getType())) {
                return true;
            }
            if (chunk instanceof ControllerOutputChunk output && output.getControllerPayload() != null
                    && EventType.TASK_INTERACTION.getValue().equals(output.getControllerPayload().getType())) {
                return true;
            }
        }
        return false;
    }

    private static String renderPayloads(List<Object> chunks) {
        List<Object> payloads = new ArrayList<>();
        for (Object chunk : chunks) {
            if (chunk instanceof OutputSchema output) {
                payloads.add(output.getPayload());
                continue;
            }
            if (chunk instanceof ControllerOutputChunk output && output.getControllerPayload() != null) {
                payloads.add(output.getControllerPayload().getData());
            }
        }
        return String.valueOf(payloads);
    }

    private static final class IntentChangeModelFactory implements Model.ModelClientFactory {
        @Override
        public String providerName() {
            return PROVIDER;
        }

        @Override
        public BaseModelClient create(ModelRequestConfig modelConfig, ModelClientConfig clientConfig) {
            return new IntentChangeModelClient(modelConfig, clientConfig);
        }
    }

    private static final class IntentChangeModelClient extends BaseModelClient {
        private IntentChangeModelClient(ModelRequestConfig modelConfig, ModelClientConfig clientConfig) {
            super(modelConfig, clientConfig);
        }

        @Override
        public AssistantMessage invoke(Object messages, Object tools, Float temperature, Float topP, String model,
                Integer maxTokens, String stop, BaseOutputParser outputParser, Float timeout,
                Map<String, Object> kwargs) {
            return transferCall();
        }

        @Override
        public Iterator<AssistantMessageChunk> stream(Object messages, Object tools, Float temperature, Float topP,
                String model, Integer maxTokens, String stop, BaseOutputParser outputParser, Float timeout,
                Map<String, Object> kwargs) {
            return List.of(AssistantMessageChunk.builder().content("")
                    .toolCalls(transferCall().getToolCalls()).build()).iterator();
        }

        private static AssistantMessage transferCall() {
            return AssistantMessage.builder().content("")
                    .toolCalls(List.of(ToolCall.builder().id("execute-transfer-call").name(BankTools.TRANSFER)
                            .arguments("{\"recipient\":\"王五\",\"amount\":50}").build()))
                    .build();
        }

        @Override
        public ImageGenerationResponse generateImage(List<UserMessage> messages, String model, String size,
                String negativePrompt, int n, boolean promptExtend, boolean watermark, int seed,
                Map<String, Object> kwargs) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AudioGenerationResponse generateSpeech(List<UserMessage> messages, String model, String voice,
                String languageType, Map<String, Object> kwargs) {
            throw new UnsupportedOperationException();
        }

        @Override
        public VideoGenerationResponse generateVideo(List<UserMessage> messages, String imgUrl, String audioUrl,
                String model, String size, String resolution, int duration, boolean promptExtend, boolean watermark,
                String negativePrompt, Integer seed, Map<String, Object> kwargs) {
            throw new UnsupportedOperationException();
        }
    }
}
