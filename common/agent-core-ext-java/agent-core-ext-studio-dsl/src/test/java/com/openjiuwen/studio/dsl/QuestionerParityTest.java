/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.questioner.QuestionerConfig;
import com.openjiuwen.studio.dsl.questioner.QuestionerEngine;
import com.openjiuwen.studio.dsl.questioner.QuestionerField;
import com.openjiuwen.studio.dsl.questioner.QuestionerState;
import com.openjiuwen.studio.dsl.rails.RailsRegistry;
import com.openjiuwen.studio.dsl.registry.NodeTypeRegistry;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * P3 Questioner + rails parity tests.
 *
 * @since 2026-08-25
 */
class QuestionerParityTest {

    @Test
    void rails_length_and_range() {
        Map<String, Object> railsConfig = Map.of(
                "rails",
                Map.of(
                        "execution",
                        List.of(
                                Map.of("action", "length_limit_validate"),
                                Map.of("action", "number_range_validate"))),
                "actions_config",
                List.of(
                        Map.of("action", "length_limit_validate", "action_extra_args", Map.of("name", 5)),
                        Map.of(
                                "action",
                                "number_range_validate",
                                "action_extra_args",
                                Map.of("age", List.of(0, 120)))));
        Map<String, Object> args = new HashMap<>();
        args.put("name", "abcdef");
        args.put("age", 30);
        Map<String, Object> out = RailsRegistry.executeRails(railsConfig, Map.of("arguments", args, "user_input", ""));
        assertThat(out.get("name")).isNull();
        assertThat(out.get("age")).isEqualTo(30L);
    }

    @Test
    void rails_enum_requires_substring_in_user_input() {
        Map<String, Object> railsConfig = Map.of(
                "rails",
                Map.of("execution", List.of(Map.of("action", "enum_legality_validate"))),
                "actions_config",
                List.of(Map.of(
                        "action",
                        "enum_legality_validate",
                        "action_extra_args",
                        Map.of("city", List.of("上海", "北京")))));
        Map<String, Object> ok = RailsRegistry.executeRails(
                railsConfig, Map.of("arguments", Map.of("city", "上海"), "user_input", "我在上海"));
        assertThat(ok.get("city")).isEqualTo("上海");
        Map<String, Object> bad = RailsRegistry.executeRails(
                railsConfig, Map.of("arguments", Map.of("city", "上海"), "user_input", "我在南京"));
        assertThat(bad.get("city")).isNull();
    }

    @Test
    void rails_phone_format() {
        Map<String, Object> railsConfig = Map.of(
                "rails",
                Map.of("execution", List.of(Map.of("action", "common_data_format_check"))),
                "actions_config",
                List.of(Map.of(
                        "action",
                        "common_data_format_check",
                        "action_extra_args",
                        Map.of("mobile", "phone"))));
        Map<String, Object> ok = RailsRegistry.executeRails(
                railsConfig, Map.of("arguments", Map.of("mobile", "13812345678"), "user_input", ""));
        assertThat(ok.get("mobile")).isEqualTo("13812345678");
        Map<String, Object> bad = RailsRegistry.executeRails(
                railsConfig, Map.of("arguments", Map.of("mobile", "123"), "user_input", ""));
        assertThat(bad.get("mobile")).isNull();
    }

    @Test
    void questionContent_first_invoke_requires_input() {
        QuestionerConfig cfg = QuestionerConfig.fromNodeConfigs(Map.of(
                "questionContent", "你好{{name}}", "extractFieldsFromResponse", false));
        QuestionerEngine engine = new QuestionerEngine("q1", cfg);
        Map<String, Object> out = engine.invoke(Map.of("userFields", Map.of("name", "Kayla")), null);
        assertThat(out.get("question")).isEqualTo("你好Kayla");
        assertThat(out.get("questionerState")).isEqualTo("INPUT_REQUIRED");
        assertThat(out.get("hangState")).isEqualTo("INPUT_REQUIRED");
    }

    @Test
    void questionContent_resume_with_debug_flag() {
        QuestionerConfig cfg = QuestionerConfig.fromNodeConfigs(Map.of(
                "questionContent", "你叫什么名字？", "extractFieldsFromResponse", false));
        QuestionerEngine engine = new QuestionerEngine("q1", cfg);
        QuestionerState saved = new QuestionerState();
        saved.setStatus(QuestionerState.USER_INTERACT);
        saved.setQuestion("你叫什么名字？");
        Map<String, Object> in = new HashMap<>();
        in.put("query", "张三");
        in.put("__single_debug_recovery__", true);
        in.put(QuestionerState.KEY, saved.toMap());
        Map<String, Object> out = engine.invoke(in, null);
        assertThat(out.get("USER_RESPONSE")).isEqualTo("张三");
        assertThat(out.get("QUESTION")).isEqualTo("你叫什么名字？");
        assertThat(out.get("questionerState")).isEqualTo("answered");
    }

    @Test
    void extract_single_field_with_mock_and_rails() {
        QuestionerConfig cfg = QuestionerConfig.fromNodeConfigs(Map.of(
                "extractFieldsFromResponse",
                true,
                "fieldNames",
                List.of(Map.of("fieldName", "city", "type", "string", "cnFieldName", "城市", "required", true)),
                "mockExtractedFields",
                Map.of("city", "上海"),
                "railsConfig",
                Map.of(
                        "rails",
                        Map.of("execution", List.of(Map.of("action", "enum_legality_validate"))),
                        "actions_config",
                        List.of(Map.of(
                                "action",
                                "enum_legality_validate",
                                "action_extra_args",
                                Map.of("city", List.of("上海", "北京")))))));
        QuestionerEngine engine = new QuestionerEngine("q1", cfg);
        // user_input must contain 上海 for enum rail
        Map<String, Object> out = engine.invoke(Map.of("query", "我在上海出差"), null);
        assertThat(out.get("city")).isEqualTo("上海");
        assertThat(out.get("questionerState")).isEqualTo("answered");
    }

    @Test
    void questionerField_requiredDefaultsFalseWhenOmitted() {
        QuestionerField field = QuestionerField.fromMap(Map.of("fieldName", "city", "type", "string"));
        assertThat(field.required()).isFalse();
    }

    @Test
    void extractWithoutModel_throws() {
        QuestionerConfig cfg = QuestionerConfig.fromNodeConfigs(Map.of(
                "extractFieldsFromResponse",
                true,
                "fieldNames",
                List.of(Map.of("fieldName", "city", "type", "string", "required", true))));
        QuestionerEngine engine = new QuestionerEngine("q1", cfg);
        assertThatThrownBy(() -> engine.invoke(Map.of("query", "上海"), null))
                .isInstanceOf(NodeExecutionException.class)
                .hasMessageContaining("failed to invoke llm for extraction");
    }

    @Test
    void missing_question_and_fields_fails() {
        QuestionerConfig cfg = QuestionerConfig.fromNodeConfigs(Map.of("extractFieldsFromResponse", false));
        QuestionerEngine engine = new QuestionerEngine("q1", cfg);
        assertThatThrownBy(() -> engine.invoke(Map.of(), null))
                .isInstanceOf(NodeExecutionException.class)
                .hasMessageContaining("question_content");
    }

    @Test
    void handler_wired_through_registry() {
        NodeTypeRegistry registry = NodeTypeRegistry.createWithBuiltins();
        ComponentExecutable exec = registry.create(
                AssembledNode.of(
                        "q1",
                        "jiuwen.questioner",
                        Map.of("questionContent", "请输入", "extractFieldsFromResponse", false)),
                NodeBuildContext.defaults("wf"));
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) exec.invoke(Map.of("userFields", Map.of()), null, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> uf = (Map<String, Object>) out.get("userFields");
        assertThat(uf.get("questionerState")).isEqualTo("INPUT_REQUIRED");
        assertThat(uf.get("question")).isEqualTo("请输入");
    }
}
