/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.extractor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.foundation.llm.schema.BaseMessage;
import com.openjiuwen.core.foundation.llm.schema.SystemMessage;
import com.openjiuwen.core.foundation.llm.schema.UserMessage;
import com.openjiuwen.studio.dsl.conversation.ConversationUserMessage;
import com.openjiuwen.studio.dsl.testsupport.StubModelContext;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Python {@code flow_extractor.py} LLM path parity (stub invoker, no network).
 *
 * @since 2026-08-26
 */
class ExtractorParityTest {
    private static Map<String, Object> modelConfigs() {
        Map<String, Object> extension = Map.of(
                "api_key", "test-key",
                "api_base", "http://localhost/v1");
        Map<String, Object> model = Map.of(
                "modelName", "gpt-test",
                "modelType", "OpenAI",
                "hyperParameters", Map.of("temperature", 0.1, "top_p", 0.15),
                "extension", extension);
        return Map.of(
                "model", model,
                "field_names",
                List.of(
                        Map.of(
                                "field_name", "location",
                                "cn_field_name", "目的地",
                                "description", "目的地或地点"),
                        Map.of(
                                "field_name", "traveltool",
                                "cn_field_name", "交通工具",
                                "description", "交通工具")),
                "inputComplement", true,
                "extractFieldsFromResponse", true);
    }

    @Test
    void llmExtractFiltersToCnFieldsAndTraces() {
        AtomicReference<List<BaseMessage>> captured = new AtomicReference<>();
        ExtractorLlmExtractor.ModelInvoker invoker = messages -> {
            captured.set(messages);
            return "{\"location\": \"上海\", \"traveltool\": \"飞机\", \"noise\": \"x\"}";
        };

        ExtractorConfig cfg = ExtractorConfig.fromNodeConfigs(modelConfigs());
        ExtractorEngine engine = new ExtractorEngine("ext1", cfg, invoker);

        ModelContext context = new StubModelContext(new UserMessage("我要坐飞机去上海参加会议"));

        Map<String, Object> out = engine.invoke(Map.of("userFields", Map.of()), null, context);

        assertThat(out.get("location")).isEqualTo("上海");
        assertThat(out.get("traveltool")).isEqualTo("飞机");
        assertThat(out).doesNotContainKey("noise");
        assertThat(out).doesNotContainKey("extracted");
        assertThat(out.get("USER_RESPONSE")).isEqualTo("我要坐飞机去上海参加会议");

        List<BaseMessage> msgs = captured.get();
        assertThat(msgs).hasSize(2);
        assertThat(msgs.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(msgs.get(1)).isInstanceOf(UserMessage.class);
        String system = String.valueOf(msgs.get(0).getContent());
        assertThat(system).contains("location").contains("traveltool");
    }

    @Test
    void formatExtractorConfigsNormalizesFieldNames() {
        ExtractorConfig cfg = ExtractorConfig.fromNodeConfigs(modelConfigs());
        assertThat(cfg.cnFieldsName()).containsEntry("location", "目的地").containsEntry("traveltool", "交通工具");
        assertThat(cfg.keyFields()).hasSize(2);
        assertThat(cfg.hyperParameters().get("temperature")).isEqualTo(0.1);
        assertThat(cfg.inputComplement()).isTrue();
        assertThat(cfg.extractFieldsFromResponse()).isTrue();
    }

    @Test
    void getLatestKRoundsChatMatchesPythonSlice() {
        List<Map<String, Object>> history = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            history.add(Map.of("role", "user", "content", "m" + i));
        }
        List<Map<String, Object>> sliced = ExtractorLlmExtractor.getLatestKRoundsChat(history, 5);
        assertThat(sliced).hasSize(11);
        assertThat(sliced.get(0).get("content")).isEqualTo("m1");
        assertThat(sliced.get(10).get("content")).isEqualTo("m11");
    }

    @Test
    void nullLlmValuesBecomeEmptyString() {
        ExtractorLlmExtractor extractor =
                new ExtractorLlmExtractor("e1", ExtractorConfig.fromNodeConfigs(modelConfigs()), msgs -> "{\"location\": null}");
        Map<String, Object> out = extractor.extract("test", List.of(), null);
        assertThat(out.get("location")).isEqualTo("");
    }

    @Test
    void emptyCnFieldsNameFiltersAllExtractedKeys() {
        Map<String, Object> configWithoutFields = Map.of(
                "model",
                Map.of(
                        "modelName", "gpt-test",
                        "modelType", "OpenAI",
                        "hyperParameters", Map.of("temperature", 0.1),
                        "extension", Map.of("api_key", "k", "api_base", "http://localhost/v1")));
        ExtractorEngine engine = new ExtractorEngine(
                "e1",
                ExtractorConfig.fromNodeConfigs(configWithoutFields),
                msgs -> "{\"location\": \"上海\"}");
        ModelContext context = new StubModelContext(new UserMessage("test"));
        Map<String, Object> out =
                engine.invoke(Map.of("config", configWithoutFields, "userFields", Map.of()), null, context);
        assertThat(out).doesNotContainKey("location");
        assertThat(out.get("USER_RESPONSE")).isEqualTo("test");
    }

    @Test
    void enableHistoryFalseExcludedFromDigHistory() {
        ExtractorConfig cfg = ExtractorConfig.fromNodeConfigs(modelConfigs());
        AtomicReference<List<BaseMessage>> captured = new AtomicReference<>();
        ExtractorEngine engine = new ExtractorEngine("ext1", cfg, messages -> {
            captured.set(messages);
            return "{\"location\": \"上海\", \"traveltool\": \"飞机\"}";
        });
        ModelContext context = new StubModelContext(
                new ConversationUserMessage("系统提示", false),
                new ConversationUserMessage("我要坐飞机去上海参加会议"));
        engine.invoke(Map.of("config", modelConfigs(), "userFields", Map.of()), null, context);
        String system = String.valueOf(captured.get().get(0).getContent());
        assertThat(system).doesNotContain("系统提示");
        assertThat(system).contains("traveltool");
    }
}
