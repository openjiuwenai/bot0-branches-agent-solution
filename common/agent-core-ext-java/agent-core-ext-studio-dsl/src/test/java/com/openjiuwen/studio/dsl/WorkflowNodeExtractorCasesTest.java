/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.foundation.llm.schema.UserMessage;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.extractor.ExtractorEngine;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.AssembledWorkflow;
import com.openjiuwen.studio.dsl.registry.NodeTypeRegistry;
import com.openjiuwen.studio.dsl.testsupport.LinearWorkflowTestSupport;
import com.openjiuwen.studio.dsl.testsupport.StubModelContext;
import com.openjiuwen.studio.dsl.testsupport.StudioEngineTestSupport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Java port of {@code test_flow_extractor.py} — stub LLM only (strict 1:1 with Python extension).
 *
 * @since 2026-08-25
 */

class WorkflowNodeExtractorCasesTest {
    private static final String USER_INPUT = "我要坐飞机去上海参加会议";
    private static final String MOCK_LLM_JSON = "{\"location\": \"上海\", \"traveltool\": \"飞机\"}";

    private NodeTypeRegistry registry;

    @BeforeEach
    void setUp() {
        registry = NodeTypeRegistry.createWithBuiltins();
        StudioEngineTestSupport.installExtractor(msgs -> MOCK_LLM_JSON);
    }

    @AfterEach
    void tearDown() {
        StudioEngineTestSupport.clear();
    }
    static Map<String, Object> standardExtractorConfig() {
        return Map.of(
                "model",
                Map.of(
                        "modelName", "gpt-4",
                        "modelType", "openai",
                        "hyperParameters", Map.of("temperature", 0.1, "top_p", 0.15),
                        "extension",
                        Map.of(
                                "api_key", "mock-api-key",
                                "api_base", "https://api.openai.com/v1",
                                "verify_ssl", true)),
                "fieldNames",
                List.of(
                        Map.of(
                                "field_name", "location",
                                "cn_field_name", "地点",
                                "description", "目的地或地点",
                                "default_value", ""),
                        Map.of(
                                "field_name", "traveltool",
                                "cn_field_name", "交通工具",
                                "description", "交通工具",
                                "default_value", "")),
                "withChatHistory", true,
                "chatHistoryMaxRounds", 5,
                "inputComplement", true,
                "extractFieldsFromResponse", true);
    }

    private static ModelContext contextWithUserInput(String text) {
        return new StubModelContext(new UserMessage(text));
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> uf(Object invokeOut) {
        Map<String, Object> out = (Map<String, Object>) invokeOut;
        return (Map<String, Object>) out.get("userFields");
    }

    private ComponentExecutable extractor(Map<String, Object> configs) {
        return registry.create(
                AssembledNode.of("extractor", "jiuwen.extractor", configs), StudioEngineTestSupport.context("wf"));
    }

    @Nested
    class MockLlmCases {
        @Test
        void extractLocationAndTraveltoolWithStubLlm() {
            ComponentExecutable exec = extractor(standardExtractorConfig());
            Map<String, Object> fields = uf(exec.invoke(
                    Map.of("userFields", Map.of("Input", USER_INPUT)),
                    null,
                    contextWithUserInput(USER_INPUT)));
            assertThat(String.valueOf(fields.get("location"))).contains("上海");
            assertThat(String.valueOf(fields.get("traveltool"))).contains("飞机");
            assertThat(fields.get("USER_RESPONSE")).isEqualTo(USER_INPUT);
            assertThat(fields).doesNotContainKey("extracted");
        }

        @Test
        void extractWithChatHistoryUsesContextMessages() {
            ComponentExecutable exec = extractor(standardExtractorConfig());
            ModelContext context =
                    new StubModelContext(new UserMessage("你好，我是小明"), new UserMessage(USER_INPUT));
            Map<String, Object> fields =
                    uf(exec.invoke(Map.of("userFields", Map.of()), null, context));
            assertThat(String.valueOf(fields.get("location"))).contains("上海");
            assertThat(fields.get("USER_RESPONSE")).isEqualTo(USER_INPUT);
        }

        @Test
        void extractWithEmptyLlmResponse() {
            StudioEngineTestSupport.installExtractor(msgs -> "{}");
            ComponentExecutable exec = extractor(standardExtractorConfig());
            Map<String, Object> fields = uf(exec.invoke(
                    Map.of("userFields", Map.of()), null, contextWithUserInput(USER_INPUT)));
            assertThat(fields.get("USER_RESPONSE")).isEqualTo(USER_INPUT);
            assertThat(fields).doesNotContainKey("location");
        }

        @Test
        void startExtractorEnd_linearStubChain() {
            AssembledWorkflow wf = new AssembledWorkflow(
                    "ext_linear",
                    List.of(
                            AssembledNode.of("start", "jiuwen.start", Map.of()),
                            AssembledNode.of("extractor", "jiuwen.extractor", standardExtractorConfig()),
                            AssembledNode.of(
                                    "end",
                                    "jiuwen.end",
                                    Map.of("responseTemplate", "{{location}}-{{traveltool}}"))));
            Map<String, Object> out = LinearWorkflowTestSupport.executeLinear(
                            registry,
                            wf,
                            StudioEngineTestSupport.context("ext_linear"),
                            Map.of("userFields", Map.of("query", USER_INPUT)),
                            null,
                            contextWithUserInput(USER_INPUT));
            assertThat(String.valueOf(uf(out).get("answer"))).contains("上海").contains("飞机");
        }
    }

    @Nested
    class TypeAliasCases {
        @Test
        void aliasInfoExtractionType() {
            ComponentExecutable exec = registry.create(
                    AssembledNode.of("e1", "jiuwen.infoExtraction", standardExtractorConfig()),
                    StudioEngineTestSupport.context("wf"));
            Map<String, Object> fields = uf(exec.invoke(
                    Map.of("userFields", Map.of()), null, contextWithUserInput("去北京")));
            assertThat(fields.get("location")).isEqualTo("上海");
        }
    }
}
