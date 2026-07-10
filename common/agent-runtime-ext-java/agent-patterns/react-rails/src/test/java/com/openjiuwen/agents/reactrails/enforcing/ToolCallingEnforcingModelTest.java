/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.enforcing;

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
import com.openjiuwen.core.foundation.tool.schema.ToolInfo;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Mock tests for {@link ToolCallingEnforcingModel} (probe-based approach).
 *
 * <p>Verifies that the one-shot probe correctly:
 * <ul>
 *   <li>(a) Fail-fast on {@code LlmBackedModelClient}-style bypass (discards tools)</li>
 *   <li>(b) Passes through for legitimate clients that forward tools</li>
 * </ul>
 */
class ToolCallingEnforcingModelTest {
    // Test (a): bypass detection — fail-fast on LlmBackedModelClient
    /**
     * (a) Mock client discards tools entirely (like LlmBackedModelClient).
     * The one-shot probe on the first invoke sends a forced __probe_tool__,
     * gets back text-only response without tool_calls, and throws
     * ToolCallingBypassException — fail-fast before any real invoke.
     */
    @Test
    void invokeThrowsToolCallingBypassExceptionWhenClientDiscardsTools() throws Exception {
        String provider = "test-bypass-" + System.nanoTime();
        DefaultModelClientFactories.ensureRegistered();
        Model.registerFactory(new Model.ModelClientFactory() {
            @Override
            public String providerName() {
                return provider;
            }
            @Override
            public BaseModelClient create(ModelRequestConfig r, ModelClientConfig c) {
                return new BaseModelClient(r, c) {
                    @Override
                    public AssistantMessage invoke(Object messages, Object tools, Float temperature, Float maxTokens,
                            String model, Integer n, String stop, BaseOutputParser parser, Float topP,
                            Map<String, Object> kwargs) {
                        // LlmBackedModelClient-style: discard tools, return text-only
                        return new AssistantMessage("我先调用 __replan__ 工具来重新规划方案");
                    }

                    @Override
                    public Iterator<AssistantMessageChunk> stream(Object a, Object b, Float cc, Float d, String e,
                            Integer f, String g, BaseOutputParser h, Float i, Map<String, Object> j) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public ImageGenerationResponse generateImage(List<UserMessage> a, String b, String c, String d,
                            int e, boolean isF, boolean isG, int h, Map<String, Object> i) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public AudioGenerationResponse generateSpeech(List<UserMessage> a, String b, String c, String d,
                            Map<String, Object> e) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public VideoGenerationResponse generateVideo(List<UserMessage> a, String b, String c, String d,
                            String e, String f, int g, boolean isH, boolean isI, String j, Integer k,
                            Map<String, Object> l) {
                        throw new UnsupportedOperationException();
                    }
                };
            }
        });

        var cliCfg = ModelClientConfig.builder().clientId("test-enforcing-" + System.nanoTime())
                .clientProvider(provider).apiKey("dummy").apiBase("http://localhost:0").verifySsl(false).build();
        var reqCfg = ModelRequestConfig.builder().modelName("test-model").temperature(0.3).maxTokens(200).build();

        var model = new ToolCallingEnforcingModel(cliCfg, reqCfg);
        List<?> messages = List.of();
        List<ToolInfo> tools = List.of(new ToolInfo("function", "__replan__", null, null));

        // First invoke triggers the one-shot probe; the mock client discards tools
        // (returns text without tool_calls even for the probe's forced __probe_tool__).
        // The predicate detects bypass and throws before the real invoke executes.
        assertThatThrownBy(() -> model.invoke(messages, tools, 0.3f, null, "test-model", null, null, null, null, null))
                .isInstanceOf(ToolCallingBypassException.class).hasMessageContaining("bypass");
    }
    // Test (b): legitimate response — no false positive
    /**
     * (b) Mock client properly returns tool_calls for the probe, then returns
     * a legitimate final answer (content naturally mentions a tool name but
     * has no call intent, and toolCalls is empty) for the real invoke.
     *
     * The predicate passes the probe and returns the real response unchanged —
     * no false positive.
     */
    @Test
    void invokePassesThroughWhenClientHandlesTools() throws Exception {
        String provider = "test-legit-" + System.nanoTime();
        DefaultModelClientFactories.ensureRegistered();
        Model.registerFactory(new Model.ModelClientFactory() {
            @Override
            public String providerName() {
                return provider;
            }
            @Override
            public BaseModelClient create(ModelRequestConfig r, ModelClientConfig c) {
                return new BaseModelClient(r, c) {
                    // Counter: 1st call = probe, 2nd call = real invoke
                    private final AtomicInteger callCount = new AtomicInteger(0);

                    @Override
                    public AssistantMessage invoke(Object messages, Object tools, Float temperature, Float maxTokens,
                            String model, Integer n, String stop, BaseOutputParser parser, Float topP,
                            Map<String, Object> kwargs) {
                        int count = callCount.getAndIncrement();
                        if (count == 0) {
                            // Probe call: return tool_calls (client CAN do tool calling)
                            AssistantMessage msg = new AssistantMessage("Calling __probe_tool__");
                            msg.setToolCalls(List
                                    .of(new ToolCall("1", "function", "__probe_tool__", "{\"reason\":\"probe\"}", 0)));
                            return msg;
                        }
                        // Real invoke: legitimate final answer.
                        // Content naturally mentions a tool name ("__replan__") but
                        // there is no call intent — toolCalls is empty/null.
                        return new AssistantMessage("在之前的分析中，__replan__ " + "工具已用于数据获取。现在根据已有信息，答案是 42。");
                    }

                    @Override
                    public Iterator<AssistantMessageChunk> stream(Object a, Object b, Float cc, Float d, String e,
                            Integer f, String g, BaseOutputParser h, Float i, Map<String, Object> j) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public ImageGenerationResponse generateImage(List<UserMessage> a, String b, String c, String d,
                            int e, boolean isF, boolean isG, int h, Map<String, Object> i) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public AudioGenerationResponse generateSpeech(List<UserMessage> a, String b, String c, String d,
                            Map<String, Object> e) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public VideoGenerationResponse generateVideo(List<UserMessage> a, String b, String c, String d,
                            String e, String f, int g, boolean isH, boolean isI, String j, Integer k,
                            Map<String, Object> l) {
                        throw new UnsupportedOperationException();
                    }
                };
            }
        });

        var cliCfg = ModelClientConfig.builder().clientId("test-legit-" + System.nanoTime()).clientProvider(provider)
                .apiKey("dummy").apiBase("http://localhost:0").verifySsl(false).build();
        var reqCfg = ModelRequestConfig.builder().modelName("test-model").temperature(0.3).maxTokens(200).build();

        var model = new ToolCallingEnforcingModel(cliCfg, reqCfg);
        List<?> messages = List.of();
        List<ToolInfo> tools = List.of(new ToolInfo("function", "__replan__", null, null));

        // First invoke: probe passes (mock returns tool_calls for __probe_tool__),
        // then real invoke returns legitimate answer without tool_calls.
        // The probe-based predicate returns the response unchanged — no false positive.
        AssistantMessage response = model.invoke(messages, tools, 0.3f, null, "test-model", null, null, null, null,
                null);

        assertThat(response).isNotNull();
        assertThat(response.getContentAsString()).contains("__replan__").contains("答案是 42");
        // The response should have no tool_calls (LLM legitimately chose not to call tools)
        assertThat(response.getToolCalls()).isNullOrEmpty();
    }
}
