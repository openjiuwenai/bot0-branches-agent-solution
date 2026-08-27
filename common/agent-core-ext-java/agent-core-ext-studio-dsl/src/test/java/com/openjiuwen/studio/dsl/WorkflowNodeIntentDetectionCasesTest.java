/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.intentdetection.IntentDetectionEngine;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.registry.NodeTypeRegistry;
import com.openjiuwen.studio.dsl.testsupport.StudioEngineTestSupport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Java port of {@code test_intent_detection.py} (mock LLM via stub invoker).
 *
 * @since 2026-08-25
 */

class WorkflowNodeIntentDetectionCasesTest {
    private static final String USER_INPUT = "我想订一张去北京的机票";

    private NodeTypeRegistry registry;

    @BeforeEach
    void setUp() {
        registry = NodeTypeRegistry.createWithBuiltins();
        StudioEngineTestSupport.installIntent(
                messages -> "{\"class\": \"分类1\", \"reason\": \"用户想要预订机票\"}");
    }

    @AfterEach
    void tearDown() {
        StudioEngineTestSupport.clear();
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> uf(Object invokeOut) {
        Map<String, Object> out = (Map<String, Object>) invokeOut;
        return (Map<String, Object>) out.get("userFields");
    }

    private static Map<String, Object> intentConfig() {
        return Map.of(
                "llm",
                Map.of(
                        "model",
                        Map.of(
                                "modelName",
                                "gpt-test",
                                "modelType",
                                "OpenAI",
                                "extension",
                                Map.of("api_key", "k", "api_base", "http://localhost"))),
                "branches",
                List.of(
                        Map.of("id", "branch_0", "catalog", "其他意图"),
                        Map.of("id", "branch_1", "catalog", "机票预订"),
                        Map.of("id", "branch_2", "catalog", "酒店预订")),
                "prompt",
                "请根据用户输入识别意图，从以下分类中选择最匹配的一个",
                "enableHistory",
                true,
                "enableInput",
                true);
    }

    @Test
    void workflowIntentDetectionWithStubLlm() {
        ComponentExecutable exec = registry.create(
                AssembledNode.of("intent_detection", "jiuwen.intentDetection", intentConfig()),
                StudioEngineTestSupport.context("wf"));

        Map<String, Object> fields = uf(exec.invoke(Map.of("input", USER_INPUT), null, null));

        assertThat(String.valueOf(fields.get("result"))).contains("分类1");
        assertThat(String.valueOf(fields.get("name"))).contains("机票");
        assertThat(fields.get("classificationId")).isEqualTo(1);
        assertThat(fields.get("reason")).isEqualTo("用户想要预订机票");
    }

    @Test
    void bareDigitClassNormalizedByStubLlm() {
        StudioEngineTestSupport.installIntent(messages -> "{\"class\": \"2\", \"reason\": \"酒店\"}");
        ComponentExecutable exec = registry.create(
                AssembledNode.of("i1", "jiuwen.intentDetection", intentConfig()),
                StudioEngineTestSupport.context("wf"));
        Map<String, Object> fields = uf(exec.invoke(Map.of("input", "订酒店"), null, null));
        assertThat(fields.get("result")).isEqualTo("分类2");
        assertThat(String.valueOf(fields.get("name"))).contains("酒店");
        assertThat(fields.get("classificationId")).isEqualTo(2);
    }
}
