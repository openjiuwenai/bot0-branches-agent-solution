/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.questioner.QuestionerConfig;
import com.openjiuwen.studio.dsl.questioner.QuestionerEngine;
import com.openjiuwen.studio.dsl.questioner.QuestionerField;
import com.openjiuwen.studio.dsl.questioner.QuestionerState;
import com.openjiuwen.studio.dsl.registry.NodeTypeRegistry;
import com.openjiuwen.studio.dsl.util.TypeCoercer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Java port of {@code test_questioner_interrupt.py} (mock session / mock extract; no real LLM).
 *
 * <p>Deferred: full Workflow graph invoke with Model, QuestionerDirectReplyHandler,
 * QuestionerUtils.camel_to_snake, QuestionerOutput getattr.
 *
 * @since 2026-08-25
 */

class WorkflowNodeQuestionerInterruptCasesTest {
    private NodeTypeRegistry registry;

    @BeforeEach
    void setUp() {
        registry = NodeTypeRegistry.createWithBuiltins();
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> uf(Object invokeOut) {
        Map<String, Object> out = (Map<String, Object>) invokeOut;
        return (Map<String, Object>) out.get("userFields");
    }

    @Nested
    class StateCases {
        @Test
        void stateInitialization() {
            QuestionerState state = new QuestionerState();
            assertThat(state.responseNum()).isEqualTo(0);
            assertThat(state.status()).isEqualTo(QuestionerState.START);
            assertThat(state.extractedFields()).isEmpty();
            assertThat(state.question()).isEmpty();
        }

        @Test
        void stateSerializationRoundTrip() {
            QuestionerState state = new QuestionerState();
            state.setStatus(QuestionerState.USER_INTERACT);
            state.setQuestion("请提供您的姓名和年龄");
            state.incrementResponseNum();
            state.incrementResponseNum();
            state.extractedFields().put("name", "张三");
            state.extractedFields().put("age", 25);

            Map<String, Object> serialized = state.toMap();
            assertThat(serialized.get("response_num")).isEqualTo(2);
            assertThat(serialized.get("status")).isEqualTo(QuestionerState.USER_INTERACT);
            @SuppressWarnings("unchecked")
            Map<String, Object> fields = (Map<String, Object>) serialized.get("extracted_key_fields");
            assertThat(fields.get("name")).isEqualTo("张三");

            QuestionerState deserialized = QuestionerState.fromMap(serialized);
            assertThat(deserialized.responseNum()).isEqualTo(2);
            assertThat(deserialized.status()).isEqualTo(QuestionerState.USER_INTERACT);
            assertThat(deserialized.extractedFields().get("name")).isEqualTo("张三");
        }

        @Test
        void isUndergoingInteraction() {
            QuestionerState state = new QuestionerState();
            assertThat(state.isUndergoingInteraction()).isFalse();
            state.setStatus(QuestionerState.USER_INTERACT);
            assertThat(state.isUndergoingInteraction()).isTrue();
            state.setStatus(QuestionerState.END);
            assertThat(state.isUndergoingInteraction()).isFalse();
        }

        @Test
        void isFreshStateProxy() {
            QuestionerState state = new QuestionerState();
            assertThat(state.status()).isEqualTo(QuestionerState.START);
            assertThat(state.responseNum()).isEqualTo(0);
            state.incrementResponseNum();
            assertThat(state.responseNum()).isNotEqualTo(0);
        }
    }

    @Nested
    class InterruptAndPersistence {
        @Test
        void shouldInterruptViaHangState() {
            ComponentExecutable exec = registry.create(
                    AssembledNode.of(
                            "q",
                            "jiuwen.questioner",
                            Map.of("questionContent", "请输入姓名", "extractFieldsFromResponse", false)),
                    NodeBuildContext.defaults("wf"));
            Map<String, Object> fields = uf(exec.invoke(Map.of("userFields", Map.of()), null, null));
            assertThat(fields.get("hangState")).isEqualTo("INPUT_REQUIRED");
            assertThat(fields.get("questionerState")).isEqualTo("INPUT_REQUIRED");
            assertThat(fields.get("question")).isEqualTo("请输入姓名");
        }

        @Test
        void statePersistenceViaSession() {
            AtomicReference<Map<String, Object>> bucket = new AtomicReference<>(new HashMap<>());
            NodeSessionApi session = mock(NodeSessionApi.class);
            when(session.getState(any())).thenAnswer(inv -> {
                Object key = inv.getArgument(0);
                if (key == null) {
                    return bucket.get();
                }
                return bucket.get().get(String.valueOf(key));
            });
            doAnswer(inv -> {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> patch = inv.getArgument(0);
                        Map<String, Object> cur = new HashMap<>(bucket.get());
                        cur.putAll(patch);
                        bucket.set(cur);
                        return null;
                    })
                    .when(session)
                    .updateState(any());

            QuestionerConfig cfg = QuestionerConfig.fromNodeConfigs(Map.of(
                    "questionContent", "请提供您的年龄", "extractFieldsFromResponse", false));
            QuestionerEngine engine = new QuestionerEngine("q1", cfg);

            QuestionerState before = new QuestionerState();
            before.setStatus(QuestionerState.USER_INTERACT);
            before.setQuestion("请提供您的年龄");
            before.incrementResponseNum();
            before.extractedFields().put("name", "张三");
            session.updateState(Map.of(QuestionerState.KEY, before.toMap()));

            Map<String, Object> in = new HashMap<>();
            in.put("query", "25");
            in.put("__single_debug_recovery__", true);
            in.put(QuestionerState.KEY, before.toMap());
            Map<String, Object> out = engine.invoke(in, session);
            assertThat(out.get("USER_RESPONSE")).isEqualTo("25");
            assertThat(out.get("questionerState")).isEqualTo("answered");
        }

        @Test
        void errorPathEndsInteractionViaBreak() {
            QuestionerConfig cfg = QuestionerConfig.fromNodeConfigs(Map.of(
                    "questionContent",
                    "继续？",
                    "extractFieldsFromResponse",
                    false,
                    "allowNodeBreak",
                    true));
            QuestionerEngine engine = new QuestionerEngine("q1", cfg);
            QuestionerState saved = new QuestionerState();
            saved.setStatus(QuestionerState.USER_INTERACT);
            saved.setQuestion("继续？");
            Map<String, Object> in = new HashMap<>();
            in.put("query", "退出");
            in.put("__single_debug_recovery__", true);
            in.put(QuestionerState.KEY, saved.toMap());
            Map<String, Object> out = engine.invoke(in, null);
            assertThat(out.get("STATUS")).isEqualTo("break");
            assertThat(out.get("questionerState")).isEqualTo("answered");
        }
    }

    @Nested
    class FieldAndOutputCases {
        @Test
        void fieldInfoInitialization() {
            QuestionerField field = QuestionerField.fromMap(Map.of(
                    "fieldName", "name", "description", "用户姓名", "type", "string", "required", true));
            assertThat(field.fieldName()).isEqualTo("name");
            assertThat(field.description()).isEqualTo("用户姓名");
            assertThat(field.type()).isEqualTo("string");
            assertThat(field.required()).isTrue();
            assertThat(field.cnFieldName()).isEqualTo("name");
            assertThat(field.reflection()).isFalse();
        }

        @Test
        void fieldInfoWithAllFields() {
            QuestionerField field = QuestionerField.fromMap(Map.of(
                    "fieldName",
                    "age",
                    "description",
                    "用户年龄",
                    "cnFieldName",
                    "年龄",
                    "type",
                    "integer",
                    "required",
                    true,
                    "defaultValue",
                    0,
                    "reflection",
                    true));
            assertThat(field.cnFieldName()).isEqualTo("年龄");
            assertThat(field.defaultValue()).isEqualTo(0);
            assertThat(field.reflection()).isTrue();
        }

        @Test
        void typeCoerceLikeValidateAndConvert() {
            assertThat(TypeCoercer.coerce("123", "integer", null, false)).isEqualTo(123L);
            assertThat(TypeCoercer.coerce("123.45", "number", null, false)).isEqualTo(123.45d);
            assertThat(TypeCoercer.coerce("true", "boolean", null, false)).isEqualTo(true);
            assertThat(TypeCoercer.coerce("false", "boolean", null, false)).isEqualTo(false);
            assertThat(TypeCoercer.coerce(123, "string", null, false)).isEqualTo("123");
        }
    }

    @Nested
    class MultiRoundAndExtract {
        private Map<String, Object> extractConfig(Map<String, Object> mockFields) {
            Map<String, Object> cfg = new HashMap<>();
            cfg.put("extractFieldsFromResponse", true);
            cfg.put(
                    "fieldNames",
                    List.of(
                            Map.of("fieldName", "name", "cnFieldName", "姓名", "type", "string", "required", true),
                            Map.of("fieldName", "age", "cnFieldName", "年龄", "type", "integer", "required", true),
                            Map.of(
                                    "fieldName",
                                    "phone",
                                    "cnFieldName",
                                    "电话",
                                    "type",
                                    "string",
                                    "required",
                                    true)));
            cfg.put("maxResponse", 3);
            cfg.put("mockExtractedFields", mockFields);
            return cfg;
        }

        @Test
        void multiRoundStateProgressionWithMock() {
            Map<String, Object> partial = new HashMap<>();
            partial.put("name", "张三");
            // missing required age/phone → continue ask
            QuestionerEngine engine =
                    new QuestionerEngine("q1", QuestionerConfig.fromNodeConfigs(extractConfig(partial)));
            Map<String, Object> first = engine.invoke(Map.of("query", "我叫张三"), null);
            assertThat(first.get("hangState")).isEqualTo("INPUT_REQUIRED");
            // Python: output.key_fields.clear() while continue-ask
            assertThat(first).doesNotContainKey("name");
            assertThat(String.valueOf(first.get("question"))).contains("年龄");
        }

        @Test
        void maxResponseForcesEnd() {
            QuestionerConfig cfg = QuestionerConfig.fromNodeConfigs(extractConfig(Map.of("name", "张三")));
            assertThat(cfg.maxResponse()).isEqualTo(3);
            QuestionerEngine engine = new QuestionerEngine("q1", cfg);
            QuestionerState saved = new QuestionerState();
            saved.setStatus(QuestionerState.USER_INTERACT);
            saved.setQuestion("请补充");
            saved.extractedFields().put("name", "张三");
            // response_num already at max → force end even with missing required
            for (int i = 0; i < 3; i++) {
                saved.incrementResponseNum();
            }
            Map<String, Object> in = new HashMap<>();
            in.put("query", "还缺信息");
            in.put("__single_debug_recovery__", true);
            in.put(QuestionerState.KEY, saved.toMap());
            Map<String, Object> out = engine.invoke(in, null);
            assertThat(out.get("questionerState")).isEqualTo("answered");
            assertThat(out.get("hangState")).isEqualTo("Continue");
        }

        @Test
        void handlerWiredInterruptThenAnswerWithMock() {
            ComponentExecutable exec = registry.create(
                    AssembledNode.of(
                            "q1",
                            "jiuwen.questioner",
                            Map.of(
                                    "extractFieldsFromResponse",
                                    true,
                                    "fieldNames",
                                    List.of(Map.of(
                                            "fieldName", "city", "type", "string", "cnFieldName", "城市", "required", true)),
                                    "mockExtractedFields",
                                    Map.of("city", "上海"))),
                    NodeBuildContext.defaults("wf"));
            Map<String, Object> fields =
                    uf(exec.invoke(Map.of("query", "我在上海出差", "userFields", Map.of()), null, null));
            assertThat(fields.get("city")).isEqualTo("上海");
            assertThat(fields.get("questionerState")).isEqualTo("answered");
        }

        @Test
        void multiTurnTwoRoundsUntilAllRequiredFilled() {
            AtomicReference<Map<String, Object>> bucket = new AtomicReference<>(new HashMap<>());
            NodeSessionApi session = mock(NodeSessionApi.class);
            when(session.getState(any())).thenAnswer(inv -> {
                Object key = inv.getArgument(0);
                if (key == null) {
                    return bucket.get();
                }
                return bucket.get().get(String.valueOf(key));
            });
            doAnswer(inv -> {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> patch = inv.getArgument(0);
                        Map<String, Object> cur = new HashMap<>(bucket.get());
                        cur.putAll(patch);
                        bucket.set(cur);
                        return null;
                    })
                    .when(session)
                    .updateState(any());

            QuestionerEngine round1 =
                    new QuestionerEngine("q1", QuestionerConfig.fromNodeConfigs(extractConfig(Map.of("name", "张三"))));
            Map<String, Object> first = round1.invoke(Map.of("query", "我叫张三"), session);
            assertThat(first.get("hangState")).isEqualTo("INPUT_REQUIRED");
            // Python clears key_fields on continue-ask; partial extract lives in session state.
            @SuppressWarnings("unchecked")
            Map<String, Object> st1 = (Map<String, Object>) bucket.get().get(QuestionerState.KEY);
            @SuppressWarnings("unchecked")
            Map<String, Object> extracted1 = (Map<String, Object>) st1.get("extracted_key_fields");
            assertThat(extracted1).containsEntry("name", "张三");

            QuestionerEngine round2 = new QuestionerEngine(
                    "q1",
                    QuestionerConfig.fromNodeConfigs(
                            extractConfig(Map.of("name", "张三", "age", 25, "phone", "13800000000"))));
            Map<String, Object> in = new HashMap<>();
            in.put("query", "25岁 电话13800000000");
            in.put("__single_debug_recovery__", true);
            in.put(QuestionerState.KEY, bucket.get().get(QuestionerState.KEY));
            Map<String, Object> second = round2.invoke(in, session);
            assertThat(second.get("questionerState")).isEqualTo("answered");
            assertThat(second).containsEntry("name", "张三").containsEntry("phone", "13800000000");
            assertThat(second.get("age")).isIn(25, 25L);
        }

        @Test
        void fieldInfoListFromConfigPreservesOrderAndRequiredFlags() {
            QuestionerConfig cfg = QuestionerConfig.fromNodeConfigs(extractConfig(Map.of()));
            assertThat(cfg.keyFields()).hasSize(3);
            assertThat(cfg.keyFields().get(0).fieldName()).isEqualTo("name");
            assertThat(cfg.keyFields().get(1).cnFieldName()).isEqualTo("年龄");
            assertThat(cfg.keyFields().stream().filter(QuestionerField::required).count()).isEqualTo(3);
        }
    }
}
