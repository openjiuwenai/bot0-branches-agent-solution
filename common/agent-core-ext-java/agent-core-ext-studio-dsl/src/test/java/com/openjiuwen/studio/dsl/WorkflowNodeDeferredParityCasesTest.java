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
import com.openjiuwen.studio.dsl.model.AssembledWorkflow;
import com.openjiuwen.studio.dsl.questioner.QuestionerConfig;
import com.openjiuwen.studio.dsl.questioner.QuestionerEngine;
import com.openjiuwen.studio.dsl.questioner.QuestionerState;
import com.openjiuwen.studio.dsl.registry.NodeTypeRegistry;
import com.openjiuwen.studio.dsl.testsupport.LinearWorkflowTestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Enabled mock ports for previously deferred Python cases (#29/#30/#32/#36).
 *
 * <p>LLM/card/plugin 真网 mock 见 {@link WorkflowNodeLlmMockCasesTest}.
 *
 * @since 2026-08-26
 */
class WorkflowNodeDeferredParityCasesTest {
    private NodeTypeRegistry registry;

    @BeforeEach
    void setUp() {
        registry = NodeTypeRegistry.createWithBuiltins();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> uf(Object invokeOut) {
        Map<String, Object> out = (Map<String, Object>) invokeOut;
        Object user = out.get("userFields");
        if (user instanceof Map<?, ?>) {
            return (Map<String, Object>) user;
        }
        return out;
    }

    /** Mirrors Python {@code commit_user_inputs} writing {@code query} into session global state. */
    private static NodeSessionApi sessionWithGlobalQuery(String query) {
        NodeSessionApi session = mock(NodeSessionApi.class);
        when(session.getGlobalState("query")).thenReturn(query);
        return session;
    }

    private static NodeSessionApi statefulSession(AtomicReference<Map<String, Object>> bucket) {
        NodeSessionApi session = mock(NodeSessionApi.class);
        when(session.dumpState()).thenAnswer(inv -> new LinkedHashMap<>(bucket.get()));
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
                    Map<String, Object> cur = new LinkedHashMap<>(bucket.get());
                    patch.forEach((k, v) -> {
                        if (v == null) {
                            cur.remove(k);
                        } else {
                            cur.put(k, v);
                        }
                    });
                    bucket.set(cur);
                    return null;
                })
                .when(session)
                .updateState(any());
        return session;
    }

    /** #29 test_case_llm_react_multy_tools_010 — simplified Start→Message echo→End. */
    @Nested
    class ReactToolsSimplified {
        @Test
        void test_case_llm_react_multy_tools_010_messageEchoEnd() {
            AssembledWorkflow wf = new AssembledWorkflow(
                    "react_tools",
                    List.of(
                            AssembledNode.of("start", "jiuwen.start", Map.of()),
                            AssembledNode.of("message", "jiuwen.message", Map.of("template", "{{query}}")),
                            AssembledNode.of(
                                    "end",
                                    "jiuwen.end",
                                    Map.of("responseTemplate", "气温20度"))));
            String userQuery = "今天天气怎么样";
            Map<String, Object> out = LinearWorkflowTestSupport.executeLinear(
                    registry,
                    wf,
                    NodeBuildContext.defaults("react_tools"),
                    Map.of("userFields", Map.of("query", userQuery)),
                    sessionWithGlobalQuery(userQuery),
                    null);
            Map<String, Object> fields = uf(out);
            // End.responseTemplate overwrites answer/result; message echo lives in query + prior result merge
            assertThat(fields).containsEntry("query", "今天天气怎么样");
            assertThat(String.valueOf(fields.get("answer"))).isEqualTo("气温20度");
            assertThat(fields.get("__terminal__")).isEqualTo(true);

            ComponentExecutable msg = registry.create(
                    AssembledNode.of("message", "jiuwen.message", Map.of("template", "{{query}}")),
                    NodeBuildContext.defaults("react_tools"));
            assertThat(String.valueOf(uf(msg.invoke(
                                    Map.of("userFields", Map.of("query", "今天天气怎么样")),
                                    mock(NodeSessionApi.class),
                                    null))
                            .get("result")))
                    .contains("今天天气怎么样");
        }
    }

    /**
     * #30 test_case_loop_multi_condition_logic_or — numLoop + Message {{index}} + OR breakCondition.
     */
    @Nested
    class LoopMultiConditionOr {
        @Test
        void test_case_loop_multi_condition_logic_or_numLoopMessage() {
            ComponentExecutable loop = registry.create(
                    AssembledNode.of(
                            "loop",
                            "jiuwen.loop",
                            Map.of(
                                    "loop_type",
                                    "number",
                                    "loop_number",
                                    5,
                                    "loopBody",
                                    List.of(Map.of(
                                            "id",
                                            "msg",
                                            "type",
                                            "jiuwen.message",
                                            "configs",
                                            Map.of("template", "循环第{{index}}次"))),
                                    "output_schema",
                                    Map.of("text", "result"))),
                    NodeBuildContext.defaults("wf_loop_or"));
            Map<String, Object> fields =
                    uf(loop.invoke(Map.of("userFields", Map.of("query", "你好")), mock(NodeSessionApi.class), null));
            assertThat(fields.get("loopCount")).isEqualTo(5);
            assertThat(fields.get("text"))
                    .isEqualTo(List.of("循环第0次", "循环第1次", "循环第2次", "循环第3次", "循环第4次"));
        }

        @Test
        void breakCondition_orExpressionStopsEarly() {
            // OR: break when total>=3 OR flag==1; seed flag never set → breaks on total>=3
            Map<String, Object> cfg = new LinkedHashMap<>();
            cfg.put("loop_number", 10);
            cfg.put("intermediate_var", Map.of("total", 0, "flag", 0));
            cfg.put("breakCondition", "(${total} >= 3) || (${flag} == 1)");
            cfg.put(
                    "loopBody",
                    List.of(
                            Map.of(
                                    "id",
                                    "msg",
                                    "type",
                                    "jiuwen.message",
                                    "configs",
                                    Map.of("template", "round_{{total}}")),
                            Map.of(
                                    "id",
                                    "upd",
                                    "type",
                                    "jiuwen.setVariable",
                                    "configs",
                                    Map.of(
                                            "variableMapping",
                                            Map.of("total", 0),
                                            "operatorMapping",
                                            Map.of("total", "increment")))));
            cfg.put("output_schema", Map.of("text", "result"));
            ComponentExecutable loop =
                    registry.create(AssembledNode.of("loop", "jiuwen.loop", cfg), NodeBuildContext.defaults("wf"));
            Map<String, Object> fields = uf(loop.invoke(Map.of("userFields", Map.of()), mock(NodeSessionApi.class), null));
            assertThat(fields.get("text")).isEqualTo(List.of("round_0", "round_1", "round_2"));
            assertThat(fields.get("loopCount")).isEqualTo(3);
        }
    }

    /** #32 test_questioner_ssq — multi-field hang / partial resume / complete → End. */
    @Nested
    class QuestionerSsq {
        private static List<Map<String, Object>> ssqFields() {
            return List.of(
                    Map.of("fieldName", "tel", "cnFieldName", "电话号码", "type", "string", "required", true),
                    Map.of("fieldName", "time", "cnFieldName", "故障发生时间", "type", "string", "required", true),
                    Map.of("fieldName", "switch", "cnFieldName", "移动开关", "type", "string", "required", true),
                    Map.of("fieldName", "signals", "cnFieldName", "车机端显示信号", "type", "string", "required", true),
                    Map.of("fieldName", "flow_rate", "cnFieldName", "车机端剩余流量", "type", "string", "required", true));
        }

        @Test
        void test_questioner_ssq_multiFieldHangPartialThenComplete() {
            AtomicReference<Map<String, Object>> bucket = new AtomicReference<>(new HashMap<>());
            NodeSessionApi session = statefulSession(bucket);

            // R1: start → hang asking for fields
            QuestionerEngine startEng = new QuestionerEngine(
                    "ssq",
                    QuestionerConfig.fromNodeConfigs(Map.of(
                            "extractFieldsFromResponse",
                            true,
                            "fieldNames",
                            ssqFields(),
                            "maxResponse",
                            5,
                            "mockExtractedFields",
                            Map.of())));
            Map<String, Object> hang = startEng.invoke(Map.of("query", "车机故障"), session);
            assertThat(hang.get("hangState")).isEqualTo("INPUT_REQUIRED");
            assertThat(String.valueOf(hang.get("question"))).contains("电话");

            // R2: partial extract → still hang
            QuestionerEngine partialEng = new QuestionerEngine(
                    "ssq",
                    QuestionerConfig.fromNodeConfigs(Map.of(
                            "extractFieldsFromResponse",
                            true,
                            "fieldNames",
                            ssqFields(),
                            "maxResponse",
                            5,
                            "mockExtractedFields",
                            Map.of("tel", "10000", "time", "十点"))));
            Map<String, Object> midIn = new LinkedHashMap<>();
            midIn.put("query", "电话号码是10000，故障发生在十点");
            midIn.put("__single_debug_recovery__", true);
            midIn.put(QuestionerState.KEY, bucket.get().get(QuestionerState.KEY));
            Map<String, Object> mid = partialEng.invoke(midIn, session);
            assertThat(mid.get("hangState")).isEqualTo("INPUT_REQUIRED");
            // Python clears key_fields on continue-ask; partial extract lives in session state.
            @SuppressWarnings("unchecked")
            Map<String, Object> st2 = (Map<String, Object>) bucket.get().get(QuestionerState.KEY);
            @SuppressWarnings("unchecked")
            Map<String, Object> extracted2 = (Map<String, Object>) st2.get("extracted_key_fields");
            assertThat(extracted2).containsEntry("tel", "10000").containsEntry("time", "十点");
            assertThat(String.valueOf(mid.get("question"))).containsAnyOf("开关", "信号", "流量");

            // R3: remaining fields → answered → End template
            QuestionerEngine fullEng = new QuestionerEngine(
                    "ssq",
                    QuestionerConfig.fromNodeConfigs(Map.of(
                            "extractFieldsFromResponse",
                            true,
                            "fieldNames",
                            ssqFields(),
                            "maxResponse",
                            5,
                            "mockExtractedFields",
                            Map.of(
                                    "tel",
                                    "10000",
                                    "time",
                                    "十点",
                                    "switch",
                                    "开启",
                                    "signals",
                                    "有",
                                    "flow_rate",
                                    "有"))));
            Map<String, Object> doneIn = new LinkedHashMap<>();
            doneIn.put("query", "移动开关开启，车机端有显示信号，车机端有剩余流量");
            doneIn.put("__single_debug_recovery__", true);
            doneIn.put(QuestionerState.KEY, bucket.get().get(QuestionerState.KEY));
            Map<String, Object> done = fullEng.invoke(doneIn, session);
            assertThat(done.get("questionerState")).isEqualTo("answered");
            assertThat(done)
                    .containsEntry("tel", "10000")
                    .containsEntry("switch", "开启")
                    .containsEntry("flow_rate", "有");

            ComponentExecutable end = registry.create(
                    AssembledNode.of(
                            "end",
                            "jiuwen.end",
                            Map.of(
                                    "responseTemplate",
                                    "tel={{tel}} time={{time}} switch={{switch}} signals={{signals}} flow={{flow_rate}}")),
                    NodeBuildContext.defaults("ssq"));
            Map<String, Object> endOut = uf(end.invoke(Map.of("userFields", done), session, null));
            assertThat(String.valueOf(endOut.get("answer")))
                    .contains("10000")
                    .contains("开启")
                    .contains("flow=有");
        }
    }

    /** #36 test_case_workflow_mult_loop_common_002 — Branch then Loop body (simplified mock). */
    @Nested
    class MultLoopBranch {
        @Test
        void test_case_workflow_mult_loop_common_002_branchThenLoop() {
            ComponentExecutable branch = registry.create(
                    AssembledNode.of(
                            "branch",
                            "jiuwen.branch",
                            Map.of(
                                    "branches",
                                    List.of(
                                            Map.of(
                                                    "branchId",
                                                    "loop_if",
                                                    "condition",
                                                    "('你好' in ${query})"),
                                            Map.of("branchId", "loop_default", "isDefault", true)))),
                    NodeBuildContext.defaults("mult_loop"));

            @SuppressWarnings("unchecked")
            Map<String, Object> ifOut = (Map<String, Object>)
                    branch.invoke(Map.of("userFields", Map.of("query", "你好")), null, null);
            assertThat(ifOut.get("branchId")).isEqualTo("loop_if");

            ComponentExecutable loopIf = registry.create(
                    AssembledNode.of(
                            "loop_if",
                            "jiuwen.loop",
                            Map.of(
                                    "loop_number",
                                    2,
                                    "intermediate_var",
                                    Map.of("age1", 0, "age2", 0),
                                    "loopBody",
                                    List.of(Map.of(
                                            "id",
                                            "sv",
                                            "type",
                                            "jiuwen.setVariable",
                                            "configs",
                                            Map.of(
                                                    "variableMapping",
                                                    Map.of("age1", 222, "age2", 111)))),
                                    "output_schema",
                                    Map.of("age1", "age1", "age2", "age2"))),
                    NodeBuildContext.defaults("mult_loop"));
            Map<String, Object> ifFields =
                    uf(loopIf.invoke(Map.of("userFields", Map.of("query", "你好")), mock(NodeSessionApi.class), null));
            assertThat(ifFields.get("age1")).isEqualTo(List.of(222, 222));
            assertThat(ifFields.get("loopCount")).isEqualTo(2);

            @SuppressWarnings("unchecked")
            Map<String, Object> defOut = (Map<String, Object>)
                    branch.invoke(Map.of("userFields", Map.of("query", "菠菜有什么营养成分")), null, null);
            assertThat(defOut.get("branchId")).isEqualTo("loop_default");

            ComponentExecutable loopDef = registry.create(
                    AssembledNode.of(
                            "loop_default",
                            "jiuwen.loop",
                            Map.of(
                                    "loop_number",
                                    1,
                                    "loopBody",
                                    List.of(Map.of(
                                            "id",
                                            "sv",
                                            "type",
                                            "jiuwen.setVariable",
                                            "configs",
                                            Map.of(
                                                    "variableMapping",
                                                    Map.of("age1", 222222, "age2", 111111)))),
                                    "output_schema",
                                    Map.of("age1", "age1"))),
                    NodeBuildContext.defaults("mult_loop"));
            Map<String, Object> defFields = uf(loopDef.invoke(
                    Map.of("userFields", Map.of("query", "菠菜有什么营养成分")), mock(NodeSessionApi.class), null));
            assertThat(defFields.get("age1")).isEqualTo(List.of(222222));
            assertThat(defFields.get("__terminal__")).isNull();

            ComponentExecutable end = registry.create(
                    AssembledNode.of("end", "jiuwen.end", Map.of("responseTemplate", "age1={{age1}}")),
                    NodeBuildContext.defaults("mult_loop"));
            // age1 is a list from loop collectors — template stringifies it
            Map<String, Object> endUf = new LinkedHashMap<>(defFields);
            endUf.put("age1", 222222);
            Map<String, Object> endOut = uf(end.invoke(Map.of("userFields", endUf), mock(NodeSessionApi.class), null));
            assertThat(String.valueOf(endOut.get("answer"))).contains("222222");
        }
    }
}
