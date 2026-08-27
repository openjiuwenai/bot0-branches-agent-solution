/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.llmchain;

import com.openjiuwen.studio.dsl.testsupport.StudioEngineTestSupport;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessageChunk;
import com.openjiuwen.core.foundation.llm.schema.BaseMessage;
import com.openjiuwen.core.foundation.llm.schema.UsageMetadata;
import com.openjiuwen.core.foundation.llm.schema.UserMessage;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.testsupport.StubModelContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Python {@code llm_chain.py} parity (stub bridge, no network).
 *
 * @since 2026-08-26
 */
class LlmChainParityTest {

    @AfterEach
    void tearDown() {
        StudioEngineTestSupport.clear();
    }

    private static Map<String, Object> baseConf() {
        Map<String, Object> conf = new LinkedHashMap<>();
        conf.put(
                "model",
                Map.of(
                        "modelName",
                        "gpt-test",
                        "modelType",
                        "OpenAI",
                        "hyperParameters",
                        Map.of("temperature", 0.1),
                        "extension",
                        Map.of("api_key", "k", "api_base", "http://localhost")));
        conf.put("deployMode", "cloud");
        conf.put(
                "templateContent",
                List.of(Map.of("role", "user", "content", "请回答：{{query}}")));
        conf.put("responseFormat", Map.of("type", "text"));
        conf.put("enableHistory", false);
        conf.put(
                "userFields",
                Map.of("inputs", List.of(), "outputs", List.of(Map.of("id", "raw_output", "type", "string"))));
        return conf;
    }

    @Test
    void invokeFormatsTextOutputAndFlattensUsage() {
        StudioEngineTestSupport.installLlm(new LlmChainEngine.ModelBridge() {
            @Override
            public AssistantMessage invoke(List<BaseMessage> messages) {
                AssistantMessage msg = new AssistantMessage("北京是中国的首都");
                UsageMetadata usage = new UsageMetadata();
                usage.setInputTokens(10);
                usage.setOutputTokens(5);
                usage.setTotalTokens(15);
                msg.setUsageMetadata(usage);
                return msg;
            }

            @Override
            public Iterator<AssistantMessageChunk> stream(List<BaseMessage> messages) {
                return List.<AssistantMessageChunk>of().iterator();
            }
        });

        LlmChainEngine engine = StudioEngineTestSupport.createLlmChain("llm1", baseConf());
        Map<String, Object> out =
                engine.invoke(Map.of("userFields", Map.of("query", "中国首都")), null, null);

        @SuppressWarnings("unchecked")
        Map<String, Object> uf = (Map<String, Object>) out.get("userFields");
        assertThat(uf.get("raw_output")).isEqualTo("北京是中国的首都");
        assertThat(out.get("input_tokens")).isEqualTo(10);
        assertThat(out.get("total_tokens")).isEqualTo(15);
    }

    @Test
    void invokeJsonExtractsFields() {
        Map<String, Object> conf = baseConf();
        conf.put("responseFormat", Map.of("type", "json"));
        conf.put(
                "userFields",
                Map.of(
                        "outputs",
                        List.of(
                                Map.of("id", "capital", "type", "string"),
                                Map.of("id", "country", "type", "string"))));

        StudioEngineTestSupport.installLlm(new LlmChainEngine.ModelBridge() {
            @Override
            public AssistantMessage invoke(List<BaseMessage> messages) {
                return new AssistantMessage("{\"capital\": \"北京\", \"country\": \"中国\"}");
            }

            @Override
            public Iterator<AssistantMessageChunk> stream(List<BaseMessage> messages) {
                return List.<AssistantMessageChunk>of().iterator();
            }
        });

        LlmChainEngine engine = StudioEngineTestSupport.createLlmChain("llm1", conf);
        Map<String, Object> out =
                engine.invoke(Map.of("userFields", Map.of("query", "首都")), null, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> uf = (Map<String, Object>) out.get("userFields");
        assertThat(uf.get("capital")).isEqualTo("北京");
        assertThat(uf.get("country")).isEqualTo("中国");
    }

    @Test
    void undefinedPlaceholderRaises() {
        StudioEngineTestSupport.installLlm(new LlmChainEngine.ModelBridge() {
            @Override
            public AssistantMessage invoke(List<BaseMessage> messages) {
                return new AssistantMessage("x");
            }

            @Override
            public Iterator<AssistantMessageChunk> stream(List<BaseMessage> messages) {
                return List.<AssistantMessageChunk>of().iterator();
            }
        });
        LlmChainEngine engine = StudioEngineTestSupport.createLlmChain("llm1", baseConf());
        assertThatThrownBy(() -> engine.invoke(Map.of("userFields", Map.of()), null, null))
                .isInstanceOf(NodeExecutionException.class)
                .hasMessageContaining("query");
    }

    @Test
    void truncateHistoryByTurnStartsOnUser() {
        List<Map<String, Object>> history = List.of(
                Map.of("role", "user", "content", "u1"),
                Map.of("role", "assistant", "content", "a1"),
                Map.of("role", "user", "content", "u2"),
                Map.of("role", "assistant", "content", "a2"),
                Map.of("role", "user", "content", "u3"));
        List<Map<String, Object>> truncated = LlmChainPrompt.truncateHistoryByTurn(history, 2);
        assertThat(truncated.get(0).get("content")).isEqualTo("u2");
        assertThat(truncated).hasSize(3);
    }

    @Test
    void streamRealTimeYieldsChunksThenFinal() {
        StudioEngineTestSupport.installLlm(new LlmChainEngine.ModelBridge() {
            @Override
            public AssistantMessage invoke(List<BaseMessage> messages) {
                return new AssistantMessage("ab");
            }

            @Override
            public Iterator<AssistantMessageChunk> stream(List<BaseMessage> messages) {
                List<AssistantMessageChunk> chunks = new ArrayList<>();
                chunks.add(chunk("a", null));
                chunks.add(chunk("b", null));
                return chunks.iterator();
            }
        });

        LlmChainEngine engine = StudioEngineTestSupport.createLlmChain("llm1", baseConf());
        Iterator<Object> it =
                engine.stream(Map.of("userFields", Map.of("query", "hi")), null, null);
        List<Object> frames = new ArrayList<>();
        it.forEachRemaining(frames::add);
        assertThat(frames).hasSizeGreaterThanOrEqualTo(3);
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) frames.get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> uf = (Map<String, Object>) first.get("userFields");
        assertThat(uf.get("raw_output")).isEqualTo("a");
        assertThat(engine.getStreamOutput()).isNotNull();
        assertThat(engine.getStreamOutput().get("status")).isEqualTo("finish");
    }

    @Test
    void thinkingEnabledStreamsReasoningThenContent() {
        Map<String, Object> conf = baseConf();
        Map<String, Object> model = new LinkedHashMap<>(LlmChainConfig.mapOf(conf.get("model")));
        Map<String, Object> hyper = new LinkedHashMap<>(LlmChainConfig.mapOf(model.get("hyperParameters")));
        hyper.put("thinking", Map.of("type", "enabled"));
        model.put("hyperParameters", hyper);
        conf.put("model", model);
        conf.put(
                "userFields",
                Map.of(
                        "outputs",
                        List.of(
                                Map.of("id", "raw_output", "type", "string"),
                                Map.of("id", "reasoning_content", "type", "string"))));

        StudioEngineTestSupport.installLlm(new LlmChainEngine.ModelBridge() {
            @Override
            public AssistantMessage invoke(List<BaseMessage> messages) {
                return new AssistantMessage("答案");
            }

            @Override
            public Iterator<AssistantMessageChunk> stream(List<BaseMessage> messages) {
                List<AssistantMessageChunk> chunks = new ArrayList<>();
                AssistantMessageChunk think = new AssistantMessageChunk();
                think.setReasoningContent("思考中");
                think.setContent("");
                chunks.add(think);
                chunks.add(chunk("答", null));
                chunks.add(chunk("案", null));
                return chunks.iterator();
            }
        });

        LlmChainEngine engine = StudioEngineTestSupport.createLlmChain("llm1", conf);
        Iterator<Object> it =
                engine.stream(Map.of("userFields", Map.of("query", "q")), null, null);
        List<Object> frames = new ArrayList<>();
        it.forEachRemaining(frames::add);
        assertThat(frames).isNotEmpty();
        Object last = frames.get(frames.size() - 1);
        @SuppressWarnings("unchecked")
        Map<String, Object> lastMap = (Map<String, Object>) last;
        @SuppressWarnings("unchecked")
        Map<String, Object> uf = (Map<String, Object>) lastMap.get("userFields");
        assertThat(uf.get("reasoning_content")).asString().contains("思考");
    }

    @Test
    void enableHistoryInjectsChatHistoryVariable() {
        List<BaseMessage> captured = new ArrayList<>();
        StudioEngineTestSupport.installLlm(new LlmChainEngine.ModelBridge() {
            @Override
            public AssistantMessage invoke(List<BaseMessage> messages) {
                captured.addAll(messages);
                return new AssistantMessage("ok");
            }

            @Override
            public Iterator<AssistantMessageChunk> stream(List<BaseMessage> messages) {
                return List.<AssistantMessageChunk>of().iterator();
            }
        });

        Map<String, Object> conf = baseConf();
        conf.put("enableHistory", true);
        conf.put(
                "templateContent",
                List.of(Map.of("role", "user", "content", "历史：{{CHAT_HISTORY}}\n问：{{query}}")));

        StubModelContext ctx = new StubModelContext(new UserMessage("上一轮问题"));
        LlmChainEngine engine = StudioEngineTestSupport.createLlmChain("llm1", conf);
        engine.invoke(Map.of("userFields", Map.of("query", "下一轮")), null, ctx);

        assertThat(captured).isNotEmpty();
        String content = String.valueOf(captured.get(captured.size() - 1).getContent());
        assertThat(content).contains("上一轮问题");
        assertThat(content).contains("下一轮");
    }

    private static AssistantMessageChunk chunk(String content, String reasoning) {
        AssistantMessageChunk c = new AssistantMessageChunk();
        c.setContent(content);
        c.setFinishReason("null");
        if (reasoning != null) {
            c.setReasoningContent(reasoning);
        }
        return c;
    }
}
