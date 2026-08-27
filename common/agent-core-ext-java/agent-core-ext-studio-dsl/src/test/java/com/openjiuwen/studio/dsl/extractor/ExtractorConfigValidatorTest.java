/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.extractor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.model.NodeCauseCode;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Python {@code flow_extractor.Extractor.match_error_config} parity.
 *
 * @since 2026-08-26
 */

class ExtractorConfigValidatorTest {
    private static Map<String, Object> validBase() {
        return Map.of(
                "model",
                Map.of(
                        "modelName", "gpt-test",
                        "modelType", "OpenAI",
                        "hyperParameters", Map.of("temperature", 0.1)),
                "fieldNames",
                List.of(
                        Map.of(
                                "field_name", "location",
                                "cn_field_name", "目的地",
                                "description", "地点"),
                        Map.of(
                                "field_name", "traveltool",
                                "cn_field_name", "交通工具",
                                "description", "工具")),
                "inputComplement", true,
                "extractFieldsFromResponse", true);
    }

    @Test
    void validConfigPasses() {
        ExtractorConfigValidator.checkConfig("e1", validBase());
        ExtractorConfig cfg = ExtractorConfig.fromNodeConfigs(validBase());
        assertThat(cfg.inputComplement()).isTrue();
        assertThat(cfg.extractFieldsFromResponse()).isTrue();
    }

    @Test
    void missingModelNameFails() {
        Map<String, Object> bad = Map.of("model", Map.of("modelType", "OpenAI"));
        assertInvalid(bad, "Model name or model type");
    }

    @Test
    void illegalTemperatureFails() {
        Map<String, Object> bad = Map.of(
                "model",
                Map.of(
                        "modelName", "m",
                        "modelType", "t",
                        "hyperParameters", Map.of("temperature", "hot")));
        assertInvalid(bad, "Temperature is illegal");
    }

    @Test
    void illegalInputComplementFails() {
        Map<String, Object> base = new java.util.LinkedHashMap<>(validBase());
        base.put("inputComplement", "yes");
        assertInvalid(base, "input_complement is illegal");
    }

    @Test
    void illegalPromptTemplateFails() {
        Map<String, Object> base = new java.util.LinkedHashMap<>(validBase());
        base.put("promptTemplate", Map.of("role", "system"));
        assertInvalid(base, "Prompt Template should be a list");
    }

    @Test
    void fieldNamesMissingCnFieldNameFails() {
        Map<String, Object> bad = Map.of(
                "model",
                Map.of("modelName", "m", "modelType", "t", "hyperParameters", Map.of("temperature", 0.1)),
                "fieldNames", List.of(Map.of("field_name", "x", "description", "d")));
        assertInvalid(bad, "cn_field_name should be in field_names");
    }

    @Test
    void engineConstructorRunsCheckConfigOnInvoke() {
        assertThatThrownBy(() -> new ExtractorEngine("e1").invoke(Map.of(), null, null))
                .isInstanceOf(NodeExecutionException.class)
                .satisfies(ex -> assertThat(((NodeExecutionException) ex).causeCode())
                        .isEqualTo(NodeCauseCode.NODE_CONFIG_INVALID));
    }

    private static void assertInvalid(Map<String, Object> configs, String fragment) {
        assertThatThrownBy(() -> ExtractorConfigValidator.checkConfig("e1", configs))
                .isInstanceOf(NodeExecutionException.class)
                .hasMessageContaining(fragment)
                .satisfies(ex -> assertThat(((NodeExecutionException) ex).causeCode())
                        .isEqualTo(NodeCauseCode.NODE_CONFIG_INVALID));
    }
}
