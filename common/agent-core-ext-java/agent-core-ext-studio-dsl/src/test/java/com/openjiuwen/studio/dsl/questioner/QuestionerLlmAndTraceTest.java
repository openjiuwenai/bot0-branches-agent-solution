/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.questioner;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.session.internal.NodeSession;
import com.openjiuwen.core.session.internal.WorkflowSession;
import com.openjiuwen.core.session.state.InMemoryState;
import com.openjiuwen.studio.dsl.rails.formatters.DateUtilCompatibleParser;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LLM extract / reflection / Redis TraceStore parity for Questioner.
 *
 * @since 2026-08-26
 */
class QuestionerLlmAndTraceTest {

    @AfterEach
    void tearDown() {
        QuestionerTraceStore.clearMemory();
        QuestionerTraceStore.setJedis(null);
        DateUtilCompatibleParser.resetPythonAvailabilityCacheForTest();
    }

    @Test
    void llmExtractor_rejectsEmptyCollections() {
        QuestionerConfig cfg =
                QuestionerConfig.fromNodeConfigs(
                        Map.of(
                                "fieldNames",
                                List.of(Map.of("fieldName", "name", "type", "string", "required", true))));
        QuestionerLlmExtractor extractor =
                new QuestionerLlmExtractor("q1", cfg, messages -> "{\"name\": \"\", \"meta\": {}}");
        Map<String, Object> out = extractor.extract("test", List.of(), new QuestionerState(), null);
        assertThat(out).doesNotContainKey("name");
    }

    @Test
    void llmExtractor_parsesJsonAndFiltersFields() {
        QuestionerConfig cfg =
                QuestionerConfig.fromNodeConfigs(
                        Map.of(
                                "extractFieldsFromResponse",
                                true,
                                "fieldNames",
                                List.of(
                                        Map.of("fieldName", "name", "type", "string", "required", true),
                                        Map.of("fieldName", "age", "type", "integer", "required", true))));
        QuestionerLlmExtractor.ModelInvoker invoker =
                messages -> "{\"name\": \"Alice\", \"age\": 30, \"noise\": \"x\"}";
        QuestionerLlmExtractor extractor = new QuestionerLlmExtractor("q1", cfg, invoker);
        Map<String, Object> out =
                extractor.extract("我叫Alice今年30", List.of(), new QuestionerState(), null);
        assertThat(out).containsEntry("name", "Alice").containsKey("age");
        assertThat(out).doesNotContainKey("noise");
    }

    @Test
    void llmExtractor_reflectionCorrectsValue() {
        QuestionerConfig cfg =
                QuestionerConfig.fromNodeConfigs(
                        Map.of(
                                "fieldNames",
                                List.of(
                                        Map.of(
                                                "fieldName",
                                                "phone",
                                                "type",
                                                "string",
                                                "required",
                                                true,
                                                "reflection",
                                                true))));
        AtomicInteger calls = new AtomicInteger();
        QuestionerLlmExtractor.ModelInvoker invoker =
                messages -> {
                    int n = calls.incrementAndGet();
                    if (n == 1) {
                        return "{\"phone\": \"138000\"}";
                    }
                    return "{\"phone\": \"13800000000\"}";
                };
        QuestionerState state = new QuestionerState();
        QuestionerLlmExtractor extractor = new QuestionerLlmExtractor("q1", cfg, invoker);
        Map<String, Object> out = extractor.extract("手机号", List.of(), state, null);
        assertThat(out.get("phone")).isEqualTo("13800000000");
        assertThat(state.reflectionMap()).containsEntry("phone", "13800000000");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void traceStore_memoryAppendGetRecoverDelete() {
        String sid = "sess-q";
        String cid = "node-q";
        QuestionerTraceStore.append(sid, cid, Map.of("user", "hi"));
        QuestionerTraceStore.append(sid, cid, Map.of("assistant", "ask"));
        assertThat(QuestionerTraceStore.getAll(sid, cid)).hasSize(2);
        assertThat(QuestionerTraceStore.buildKey(sid, cid))
                .isEqualTo("agentBuilder:questioner:trace:sess-q:node-q");

        WorkflowSession wf = new WorkflowSession(sid, null, null, InMemoryState.create(), null);
        NodeSessionApi session = new NodeSessionApi(new NodeSession(wf, cid));
        QuestionerTraceStore.recoverToSession(sid, cid, session);

        QuestionerTraceStore.delete(sid, cid);
        assertThat(QuestionerTraceStore.getAll(sid, cid)).isEmpty();
    }

    @Test
    void engine_withStubLlm_extractsAndEnds() {
        QuestionerConfig cfg =
                QuestionerConfig.fromNodeConfigs(
                        Map.of(
                                "fieldNames",
                                List.of(Map.of("fieldName", "city", "type", "string", "required", true)),
                                "maxResponse",
                                3));
        QuestionerLlmExtractor.ModelInvoker invoker = messages -> "{\"city\": \"上海\"}";
        QuestionerEngine engine = new QuestionerEngine("q1", cfg, invoker);
        Map<String, Object> out = engine.invoke(Map.of("query", "我在上海"), null);
        assertThat(out.get("city")).isEqualTo("上海");
        assertThat(out.get("questionerState")).isEqualTo("answered");
        assertThat(out.get("STATUS")).isEqualTo("end");
    }

    @Test
    void engine_writesTraceToMemoryStore() {
        QuestionerConfig cfg =
                QuestionerConfig.fromNodeConfigs(Map.of("questionContent", "请问姓名？"));
        WorkflowSession wf = new WorkflowSession("wf-s1", null, "s1", InMemoryState.create(), null);
        NodeSessionApi session = new NodeSessionApi(new NodeSession(wf, "q1"));
        QuestionerEngine engine = new QuestionerEngine("q1", cfg);
        Map<String, Object> hang = engine.invoke(Map.of("query", "hello"), session);
        assertThat(hang.get("STATUS")).isEqualTo("INPUT_REQUIRED");
        assertThat(QuestionerTraceStore.getAll("s1", "q1")).hasSizeGreaterThanOrEqualTo(2);
        assertThat(QuestionerTraceStore.getAll("s1", "q1"))
                .anyMatch(m -> "hello".equals(m.get("user")))
                .anyMatch(m -> "请问姓名？".equals(m.get("assistant")));
    }

    @Test
    void state_usesPythonFieldNames() {
        QuestionerState state = new QuestionerState();
        state.extractedFields().put("city", "上海");
        state.setUserResponse("我在上海");
        Map<String, Object> serialized = state.toMap();
        assertThat(serialized).containsKey("extracted_key_fields");
        assertThat(serialized).doesNotContainKey("extracted_fields");
        assertThat(serialized.get("user_response")).isEqualTo("我在上海");

        QuestionerState roundTrip = QuestionerState.fromMap(serialized);
        assertThat(roundTrip.extractedFields()).containsEntry("city", "上海");
        assertThat(roundTrip.userResponse()).isEqualTo("我在上海");

        Map<String, Object> legacy = new LinkedHashMap<>(serialized);
        legacy.remove("extracted_key_fields");
        legacy.put("extracted_fields", Map.of("city", "北京"));
        QuestionerState fromLegacy = QuestionerState.fromMap(legacy);
        assertThat(fromLegacy.extractedFields()).containsEntry("city", "北京");
    }

    @Test
    void llmExtractor_writesLlmInfoTraceToStore() {
        QuestionerConfig cfg =
                QuestionerConfig.fromNodeConfigs(
                        Map.of(
                                "fieldNames",
                                List.of(Map.of("fieldName", "city", "type", "string", "required", true))));
        WorkflowSession wf = new WorkflowSession("wf-s1", null, "s-llm", InMemoryState.create(), null);
        NodeSessionApi session = new NodeSessionApi(new NodeSession(wf, "q-llm"));
        QuestionerLlmExtractor extractor =
                new QuestionerLlmExtractor(
                        "q-llm",
                        cfg,
                        messages -> "{\"city\": \"上海\"}");
        extractor.extract("我在上海", List.of(), new QuestionerState(), session);
        assertThat(QuestionerTraceStore.getAll("s-llm", "q-llm"))
                .anySatisfy(
                        m -> {
                            assertThat(m).containsKey("llm_info");
                            @SuppressWarnings("unchecked")
                            Map<String, Object> info = (Map<String, Object>) m.get("llm_info");
                            assertThat(info).containsKeys("llm_inputs", "llm_outputs");
                            assertThat(info.get("llm_outputs")).isEqualTo("{\"city\": \"上海\"}");
                            assertThat(info.get("llm_inputs")).isInstanceOf(List.class);
                        });
    }

    @Test
    void interruptStream_includesNodeName() {
        QuestionerConfig cfg =
                QuestionerConfig.fromNodeConfigs(
                        Map.of("name", "提问节点", "questionContent", "请问？"));
        NodeSessionApi session = org.mockito.Mockito.mock(NodeSessionApi.class);
        org.mockito.ArgumentCaptor<Map<String, Object>> captor =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        org.mockito.Mockito.when(session.getSessionId()).thenReturn("s1");
        QuestionerEngine engine = new QuestionerEngine("q-name", cfg);
        engine.invoke(Map.of("query", "hi"), session);
        org.mockito.Mockito.verify(session, org.mockito.Mockito.atLeastOnce())
                .writeCustomStream(captor.capture());
        Map<String, Object> payload = captor.getValue();
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) payload.get("data");
        assertThat(data).containsEntry("node_name", "提问节点").containsEntry("node_id", "q-name");
    }

    @Test
    void parseJson_fromFence() {
        Map<String, Object> m =
                QuestionerLlmExtractor.parseJsonObject("here\n```json\n{\"a\":1}\n```\nok");
        assertThat(m).containsEntry("a", 1);
    }

    @Test
    void llmExtractor_fillsDateFromUserInputWhenLlmReturnsNull() {
        QuestionerConfig cfg =
                QuestionerConfig.fromNodeConfigs(
                        Map.of(
                                "fieldNames",
                                List.of(Map.of("fieldName", "travelDate", "type", "string", "required", true)),
                                "railsConfig",
                                Map.of(
                                        "actions_config",
                                        List.of(Map.of(
                                                "action",
                                                "date_time_format",
                                                "action_extra_args",
                                                Map.of("travelDate", "%Y-%m-%d"))))));
        QuestionerLlmExtractor.ModelInvoker invoker = messages -> "{\"travelDate\": null}";
        QuestionerLlmExtractor extractor = new QuestionerLlmExtractor("q1", cfg, invoker);
        Map<String, Object> out =
                extractor.extract("2024-08-15", List.of(), new QuestionerState(), null);
        assertThat(out.get("travelDate")).isEqualTo("2024-08-15");
    }

    @Test
    void llmExtractor_fillsChineseRelativeTomorrow() {
        QuestionerConfig cfg =
                QuestionerConfig.fromNodeConfigs(
                        Map.of(
                                "fieldNames",
                                List.of(Map.of("fieldName", "day", "type", "string", "required", true)),
                                "railsConfig",
                                Map.of(
                                        "actions_config",
                                        List.of(Map.of(
                                                "action",
                                                "date_time_format",
                                                "action_extra_args",
                                                Map.of("day", "%Y-%m-%d"))))));
        QuestionerLlmExtractor.ModelInvoker invoker = messages -> "{}";
        QuestionerLlmExtractor extractor = new QuestionerLlmExtractor("q1", cfg, invoker);
        Map<String, Object> out = extractor.extract("明天出发", List.of(), new QuestionerState(), null);
        assertThat(out.get("day"))
                .isEqualTo(java.time.LocalDate.now().plusDays(1).toString());
    }
    @Test
    void tryParseChineseRelative_keywords() {
        DateUtilCompatibleParser.disablePythonDateutilForTest();
        assertThat(DateUtilCompatibleParser.tryParse("今天开会").toLocalDate())
                .isEqualTo(java.time.LocalDate.now());
        assertThat(DateUtilCompatibleParser.tryParse("后天").toLocalDate())
                .isEqualTo(java.time.LocalDate.now().plusDays(2));
        assertThat(DateUtilCompatibleParser.tryParse("不是时间")).isNull();
    }

    @Test
    void dateUtilCompatible_chineseYmdAndEmbedded() {
        DateUtilCompatibleParser.disablePythonDateutilForTest();
        assertThat(DateUtilCompatibleParser.tryParse("2024年8月15日").toLocalDate().toString())
                .isEqualTo("2024-08-15");
        assertThat(DateUtilCompatibleParser.tryParse("我打算 2024-08-20 出发").toLocalDate().toString())
                .isEqualTo("2024-08-20");
        assertThat(DateUtilCompatibleParser.tryParse("Aug 15, 2024").toLocalDate().toString())
                .isEqualTo("2024-08-15");
    }

    @Test
    void parseJson_pythonLiteralFallback() {
        Map<String, Object> m = QuestionerLlmExtractor.parseJsonObject("{'city': '上海'}");
        assertThat(m).containsEntry("city", "上海");
    }

    @Test
    void confirmationQuestion_whenAllFieldsExtracted() {
        QuestionerConfig cfg =
                QuestionerConfig.fromNodeConfigs(
                        Map.of(
                                "allowNodeConfirm",
                                true,
                                "fieldNames",
                                List.of(
                                        Map.of(
                                                "fieldName",
                                                "city",
                                                "type",
                                                "string",
                                                "cnFieldName",
                                                "城市",
                                                "required",
                                                true)),
                                "mockExtractedFields",
                                Map.of("city", "上海")));
        QuestionerEngine engine = new QuestionerEngine("q1", cfg);
        Map<String, Object> hang = engine.invoke(Map.of("query", "上海"), null);
        assertThat(hang.get("STATUS")).isEqualTo("INPUT_REQUIRED");
        assertThat(String.valueOf(hang.get("question"))).contains("上海");
        assertThat(String.valueOf(hang.get("question"))).contains("确认");
    }

    @Test
    void confirmIntent_endsWithConfirmedMessage() {
        QuestionerConfig cfg =
                QuestionerConfig.fromNodeConfigs(
                        Map.of(
                                "allowNodeConfirm",
                                true,
                                "fieldNames",
                                List.of(
                                        Map.of(
                                                "fieldName",
                                                "city",
                                                "type",
                                                "string",
                                                "cnFieldName",
                                                "城市",
                                                "required",
                                                true)),
                                "mockExtractedFields",
                                Map.of("city", "上海")));
        QuestionerEngine engine = new QuestionerEngine("q1", cfg);
        QuestionerState saved = new QuestionerState();
        saved.setStatus(QuestionerState.USER_INTERACT);
        saved.setNeedUserConfirm(true);
        saved.setQuestion("请确认");
        saved.extractedFields().put("city", "上海");
        Map<String, Object> in = new java.util.HashMap<>();
        in.put("query", "确认");
        in.put("__single_debug_recovery__", true);
        in.put(QuestionerState.KEY, saved.toMap());
        Map<String, Object> out = engine.invoke(in, null);
        assertThat(out.get("QUESTION")).isEqualTo(QuestionerKeywords.MSG_CONFIRMED);
        assertThat(out.get("STATUS")).isEqualTo("confirmed");
    }

    @Test
    void breakIntent_usesFixedExitMessage() {
        QuestionerConfig cfg =
                QuestionerConfig.fromNodeConfigs(
                        Map.of(
                                "allowNodeBreak",
                                true,
                                "fieldNames",
                                List.of(
                                        Map.of(
                                                "fieldName",
                                                "city",
                                                "type",
                                                "string",
                                                "required",
                                                true))));
        QuestionerEngine engine = new QuestionerEngine("q1", cfg);
        QuestionerState saved = new QuestionerState();
        saved.setStatus(QuestionerState.USER_INTERACT);
        saved.setQuestion("城市？");
        Map<String, Object> in = new java.util.HashMap<>();
        in.put("query", "退出");
        in.put("__single_debug_recovery__", true);
        in.put(QuestionerState.KEY, saved.toMap());
        Map<String, Object> out = engine.invoke(in, null);
        assertThat(out.get("QUESTION")).isEqualTo(QuestionerKeywords.MSG_BREAK);
        assertThat(out.get("STATUS")).isEqualTo("break");
    }

    @Test
    void continueAsk_includesEnumHintWhenVisible() {
        QuestionerConfig cfg =
                QuestionerConfig.fromNodeConfigs(
                        Map.of(
                                "enumVisible",
                                true,
                                "fieldNames",
                                List.of(
                                        Map.of(
                                                "fieldName",
                                                "city",
                                                "type",
                                                "string",
                                                "cnFieldName",
                                                "城市",
                                                "required",
                                                true)),
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
        QuestionerLlmExtractor.ModelInvoker invoker = messages -> "{\"city\": \"\"}";
        QuestionerEngine engine = new QuestionerEngine("q1", cfg, invoker);
        Map<String, Object> hang = engine.invoke(Map.of("query", "hello"), null);
        assertThat(hang.get("STATUS")).isEqualTo("INPUT_REQUIRED");
        assertThat(String.valueOf(hang.get("question"))).contains("上海");
        assertThat(String.valueOf(hang.get("question"))).contains("北京");
    }
}
