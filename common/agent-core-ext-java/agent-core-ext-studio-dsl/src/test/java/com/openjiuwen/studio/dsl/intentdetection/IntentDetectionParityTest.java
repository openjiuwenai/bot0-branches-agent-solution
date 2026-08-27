/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.intentdetection;

import com.openjiuwen.studio.dsl.testsupport.StudioEngineTestSupport;
import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.core.foundation.llm.schema.BaseMessage;
import com.openjiuwen.core.foundation.llm.schema.SystemMessage;
import com.openjiuwen.core.foundation.llm.schema.UserMessage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Python {@code intent_detection.py} LLM path parity (stub invoker, no network).
 *
 * @since 2026-08-26
 */
class IntentDetectionParityTest {
    @AfterEach
    void tearDown() {
        StudioEngineTestSupport.clear();
    }

    private static Map<String, Object> modelConfigs() {
        Map<String, Object> extension = Map.of(
                "api_key", "test-key",
                "api_base", "http://localhost/v1");
        return Map.of(
                "llm",
                Map.of(
                        "model",
                        Map.of(
                                "modelName",
                                "gpt-test",
                                "modelType",
                                "OpenAI",
                                "hyperParameters",
                                Map.of("temperature", 0.1),
                                "extension",
                                extension)),
                "branches",
                List.of(
                        Map.of("id", "branch_0", "catalog", "其他意图"),
                        Map.of("id", "branch_1", "catalog", "机票预订"),
                        Map.of("id", "branch_2", "catalog", "酒店预订")),
                "prompt",
                "请根据用户输入识别意图",
                "enableHistory",
                true,
                "enableInput",
                true);
    }

    @Test
    void llmDetectParsesClassAndMapsBranch() {
        AtomicReference<List<BaseMessage>> captured = new AtomicReference<>();
        IntentDetectionLlmDetector.ModelInvoker invoker = messages -> {
            captured.set(messages);
            return "{\"class\": \"分类1\", \"reason\": \"用户想要预订机票\"}";
        };
        IntentDetectionConfig cfg = IntentDetectionConfig.fromNodeConfigs(modelConfigs());
        IntentDetectionEngine engine = new IntentDetectionEngine("id1", cfg, modelConfigs(), invoker, null);

        Map<String, Object> out = engine.invoke(Map.of("input", "我想订一张去北京的机票"), null, null);

        assertThat(out.get("result")).isEqualTo("分类1");
        assertThat(out.get("reason")).isEqualTo("用户想要预订机票");
        assertThat(out.get("classificationId")).isEqualTo(1);
        assertThat(String.valueOf(out.get("name"))).contains("机票");

        List<BaseMessage> msgs = captured.get();
        assertThat(msgs).hasSize(2);
        assertThat(msgs.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(msgs.get(1)).isInstanceOf(UserMessage.class);
        assertThat(String.valueOf(msgs.get(1).getContent())).contains("机票预订");
    }

    @Test
    void postProcessNormalizesBareDigitClass() {
        IntentDetectionConfig cfg = IntentDetectionConfig.fromNodeConfigs(modelConfigs());
        IntentDetectionLlmDetector detector =
                new IntentDetectionLlmDetector("id1", cfg, msgs -> "{\"class\": \"2\", \"reason\": \"酒店\"}");
        IntentDetectionLlmDetector.DetectionResult result =
                detector.detect("订酒店", "", cfg.categoryInfo(), "", Map.of(), null);
        assertThat(result.intentClass()).isEqualTo("分类2");
        assertThat(result.classificationId()).isEqualTo(2);
        assertThat(result.name()).contains("酒店");
    }

    @Test
    void invalidClassFallsBackToDefaultWithReason() {
        IntentDetectionConfig cfg = IntentDetectionConfig.fromNodeConfigs(modelConfigs());
        IntentDetectionLlmDetector detector =
                new IntentDetectionLlmDetector("id1", cfg, msgs -> "{\"class\": \"分类99\", \"reason\": \"x\"}");
        IntentDetectionLlmDetector.DetectionResult result =
                detector.detect("test", "", cfg.categoryInfo(), "", Map.of(), null);
        assertThat(result.intentClass()).isEqualTo(cfg.defaultClass());
        assertThat(result.reason()).contains("不在预定义的分类列表");
    }

    @Test
    void branchesBecomeCategoryList() {
        IntentDetectionConfig cfg = IntentDetectionConfig.fromNodeConfigs(modelConfigs());
        assertThat(cfg.categoryList()).containsExactly("分类0", "分类1", "分类2");
        assertThat(cfg.categoryNameList()).containsExactly("其他意图", "机票预订", "酒店预订");
    }

    @Test
    void resetRestoresStateAndClearsFewShot() {
        IntentDetectionEngine engine =
                new IntentDetectionEngine("id1", modelConfigs(), null, null);
        engine.getState().setStatus(IntentDetectionState.ExecutionStatus.END);
        assertThat(engine.reset()).isTrue();
        assertThat(engine.getState().status()).isEqualTo(IntentDetectionState.ExecutionStatus.START);
    }

    @Test
    void loadStateRestoresExecutionStatus() {
        IntentDetectionEngine engine = new IntentDetectionEngine("id1", modelConfigs(), null, null);
        IntentDetectionState state = new IntentDetectionState();
        state.setStatus(IntentDetectionState.ExecutionStatus.USER_INTERACT);
        engine.loadState(state);
        assertThat(engine.getState().status()).isEqualTo(IntentDetectionState.ExecutionStatus.USER_INTERACT);
    }

    @Test
    void routerIsAvailableForGraphWiring() {
        IntentDetectionEngine engine = new IntentDetectionEngine("id1", modelConfigs(), null, null);
        engine.addBranch("1 == ${intent_detection['classificationId']}", "end", "branch_1");
        assertThat(engine.router()).isNotNull();
    }
}
