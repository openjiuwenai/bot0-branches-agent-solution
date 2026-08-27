/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.intentdetection;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.core.foundation.llm.schema.BaseMessage;
import com.openjiuwen.core.foundation.llm.schema.UserMessage;
import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.session.internal.NodeSession;
import com.openjiuwen.core.session.internal.WorkflowSession;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.session.state.InMemoryState;
import com.openjiuwen.studio.dsl.support.InMemoryToolRegistry;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FAQ kg path for {@code jiuwen.intentDetection} (Python {@code get_faq_result}).
 *
 * @since 2026-08-26
 */

class IntentFaqParityTest {
    private static Map<String, Object> faqConfigs(String apiId, String scope) {
        Map<String, Object> configs = new LinkedHashMap<>();
        configs.put("enableKnowledge", true);
        configs.put("recallThreshold", 0.9);
        configs.put("kg", Map.of("id", apiId, "scope", scope));
        configs.put(
                "branches",
                List.of(
                        Map.of("id", "branch_0", "catalog", "其他意图"),
                        Map.of("id", "branch_1", "catalog", "机票预订"),
                        Map.of("id", "branch_2", "catalog", "酒店预订")));
        configs.put("enableHistory", false);
        configs.put("enableInput", true);
        return configs;
    }

    @Test
    void faqHighScoreShortCircuitsWithoutLlm() {
        InMemoryToolRegistry tools = new InMemoryToolRegistry();
        tools.register(
                "faq-kb-1",
                new Tool(ToolCard.builder().id("faq-kb-1").name("faq").description("faq").build()) {

                    /**
                     * invoke.
                     *
                     * @param inputs inputs
                     * @param kwargs kwargs
                     * @return result
                     * @since 0.1.0
                     */

                    @Override
                    public Object invoke(Map<String, Object> inputs, Map<String, Object> kwargs) {
                        return Map.of(
                                "errCode",
                                0,
                                "data",
                                Map.of(
                                        "output_list",
                                        List.of(Map.of("score", 0.95, "content", "机票预订", "title", "订机票"))));
                    }

                    /**
                     * stream.
                     *
                     * @param inputs inputs
                     * @param kwargs kwargs
                     * @return result
                     * @since 0.1.0
                     */

                    @Override
                    public Iterator<Object> stream(Map<String, Object> inputs, Map<String, Object> kwargs) {
                        return List.of().iterator();
                    }
                });

        IntentDetectionEngine engine =
                new IntentDetectionEngine("id-faq", faqConfigs("faq-kb-1", "faq"), tools);
        Map<String, Object> out = engine.invoke(Map.of("input", "我想订机票"), null, null);

        assertThat(out.get("result")).isEqualTo("分类1");
        assertThat(out.get("classificationId")).isEqualTo(1);
        assertThat(String.valueOf(out.get("name"))).contains("机票");
        assertThat(out.get("reason")).isEqualTo("");
    }

    @Test
    void faqMediumScoreAddsFewShotThenLlmRuns() {
        InMemoryToolRegistry tools = new InMemoryToolRegistry();
        tools.register(
                "faq-kb-2",
                new Tool(ToolCard.builder().id("faq-kb-2").name("faq").description("faq").build()) {

                    /**
                     * invoke.
                     *
                     * @param inputs inputs
                     * @param kwargs kwargs
                     * @return result
                     * @since 0.1.0
                     */

                    @Override
                    public Object invoke(Map<String, Object> inputs, Map<String, Object> kwargs) {
                        return Map.of(
                                "errCode",
                                0,
                                "data",
                                Map.of(
                                        "output_list",
                                        List.of(Map.of("score", 0.75, "content", "机票预订", "title", "订机票样例"))));
                    }

                    /**
                     * stream.
                     *
                     * @param inputs inputs
                     * @param kwargs kwargs
                     * @return result
                     * @since 0.1.0
                     */

                    @Override
                    public Iterator<Object> stream(Map<String, Object> inputs, Map<String, Object> kwargs) {
                        return List.of().iterator();
                    }
                });

        Map<String, Object> configs = new LinkedHashMap<>(faqConfigs("faq-kb-2", "faq"));
        configs.put(
                "llm",
                Map.of(
                        "model",
                        Map.of(
                                "modelName",
                                "gpt-test",
                                "modelType",
                                "OpenAI",
                                "extension",
                                Map.of("api_key", "k", "api_base", "http://localhost"))));

        AtomicReference<List<BaseMessage>> captured = new AtomicReference<>();
        IntentDetectionLlmDetector.ModelInvoker invoker = messages -> {
            captured.set(messages);
            return "{\"class\": \"分类1\", \"reason\": \"llm\"}";
        };

        IntentDetectionEngine engine =
                new IntentDetectionEngine("id-faq2", IntentDetectionConfig.fromNodeConfigs(configs), configs, invoker, tools);
        Map<String, Object> out = engine.invoke(Map.of("input", "订机票"), null, null);

        assertThat(out.get("result")).isEqualTo("分类1");
        assertThat(captured.get()).isNotNull();
        String userPrompt = String.valueOf(captured.get().get(1).getContent());
        assertThat(userPrompt).contains("样例");
        assertThat(userPrompt).contains("订机票样例");
    }

    @Test
    void docLineScopeUsesStrSearchDataLikePython() {
        InMemoryToolRegistry tools = new InMemoryToolRegistry();
        tools.register(
                "doc-kb-1",
                new Tool(ToolCard.builder().id("doc-kb-1").name("doc").description("doc").build()) {

                    /**
                     * invoke.
                     *
                     * @param inputs inputs
                     * @param kwargs kwargs
                     * @return result
                     * @since 0.1.0
                     */

                    @Override
                    public Object invoke(Map<String, Object> inputs, Map<String, Object> kwargs) {
                        return Map.of(
                                "errCode",
                                0,
                                "data",
                                Map.of(
                                        "output_list",
                                        List.of(Map.of("score", 0.95, "content", "文档片段A"))));
                    }

                    /**
                     * stream.
                     *
                     * @param inputs inputs
                     * @param kwargs kwargs
                     * @return result
                     * @since 0.1.0
                     */

                    @Override
                    public Iterator<Object> stream(Map<String, Object> inputs, Map<String, Object> kwargs) {
                        return List.of().iterator();
                    }
                });

        Map<String, Object> configs = new LinkedHashMap<>(faqConfigs("doc-kb-1", "doc_line"));
        configs.put(
                "llm",
                Map.of(
                        "model",
                        Map.of(
                                "modelName",
                                "gpt-test",
                                "modelType",
                                "OpenAI",
                                "extension",
                                Map.of("api_key", "k", "api_base", "http://localhost"))));

        AtomicReference<List<BaseMessage>> captured = new AtomicReference<>();
        IntentDetectionEngine engine =
                new IntentDetectionEngine(
                        "id-doc",
                        IntentDetectionConfig.fromNodeConfigs(configs),
                        configs,
                        messages -> {
                            captured.set(messages);
                            return "{\"class\": \"分类0\", \"reason\": \"fallback\"}";
                        },
                        tools);

        engine.invoke(Map.of("input", "查文档"), null, null);
        assertThat(String.valueOf(captured.get().get(1).getContent())).contains("文档片段A");
    }

    @Test
    void memoryMessageAppendedWhenUserProfileEnabled() {
        Map<String, Object> configs = new LinkedHashMap<>(faqConfigs("unused", "faq"));
        configs.put(
                "llm",
                Map.of(
                        "model",
                        Map.of(
                                "modelName",
                                "gpt-test",
                                "modelType",
                                "OpenAI",
                                "extension",
                                Map.of("api_key", "k", "api_base", "http://localhost"))));
        configs.put("memory", Map.of("userProfile", Map.of("enable", true)));

        Map<String, Object> global = new HashMap<>();
        global.put("memory_message", new UserMessage("用户画像：偏好机票预订"));
        WorkflowSession wf =
                new WorkflowSession("wf-mem", null, "sess-mem", InMemoryState.create(null, global, null, null, null), null);
        NodeSessionApi session = new NodeSessionApi(new NodeSession(wf, "id-mem"));

        AtomicReference<List<BaseMessage>> captured = new AtomicReference<>();
        IntentDetectionEngine engine =
                new IntentDetectionEngine(
                        "id-mem",
                        IntentDetectionConfig.fromNodeConfigs(configs),
                        configs,
                        messages -> {
                            captured.set(messages);
                            return "{\"class\": \"分类1\", \"reason\": \"ok\"}";
                        },
                        null);

        engine.invoke(Map.of("input", "订票"), session, null);
        assertThat(captured.get()).hasSize(3);
        assertThat(String.valueOf(captured.get().get(2).getContent())).contains("用户画像");
    }
}
