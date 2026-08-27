/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.adapter.interact.FlowInputUtils;
import com.openjiuwen.studio.dsl.adapter.interact.InputNodeHandler;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.flowinput.FlowInputEngine;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.AssembledWorkflow;
import com.openjiuwen.studio.dsl.registry.NodeTypeRegistry;
import com.openjiuwen.studio.dsl.support.InMemoryToolRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Java port of node interrupt/resume paths from {@code test_flow_input_base_ir.py} and
 * {@code test_case_flow_input/*} ({@link InputNodeHandler}; mock session, no WorkflowWrapper).
 *
 * <p>Deferred: full astream WorkflowWrapper chunks / nested FlowInput inside SubWorkflow
 * InteractiveInput routing / real MCP map tool between input and questioner.
 *
 * @since 2026-08-26
 */

class WorkflowNodeInputWorkflowCasesTest {
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

    private static Map<String, Object> flowInputConfNameAgeEmail() {
        return Map.of(
                "name",
                "输入",
                "userFields",
                Map.of(
                        "inputs",
                        List.of(
                                Map.of(
                                        "id",
                                        "name",
                                        "type",
                                        "string",
                                        "required",
                                        true,
                                        "description",
                                        "用户姓名"),
                                Map.of(
                                        "id",
                                        "age",
                                        "type",
                                        "number",
                                        "required",
                                        false,
                                        "description",
                                        "用户年龄"),
                                Map.of(
                                        "id",
                                        "email",
                                        "type",
                                        "string",
                                        "required",
                                        true,
                                        "description",
                                        "用户邮箱"))));
    }

    @Nested
    class BaseIrInit {
        @Test
        void flowInputInitializationWiresAndStartsAtHang() {
            ComponentExecutable exec = registry.create(
                    AssembledNode.of(
                            "flow_input",
                            "jiuwen.input",
                            Map.of(
                                    "userFields",
                                    Map.of(
                                            "inputs",
                                            List.of(
                                                    Map.of("id", "city1", "type", "string", "required", true),
                                                    Map.of("id", "city2", "type", "string", "required", true))))),
                    NodeBuildContext.defaults("wf"));
            AtomicReference<Map<String, Object>> bucket = new AtomicReference<>(new HashMap<>());
            @SuppressWarnings("unchecked")
            Map<String, Object> out =
                    (Map<String, Object>) exec.invoke(Map.of("userFields", Map.of()), statefulSession(bucket), null);
            assertThat(out).isEmpty();
            @SuppressWarnings("unchecked")
            Map<String, Object> state = (Map<String, Object>) bucket.get().get(FlowInputEngine.STATE_KEY);
            assertThat(state).containsEntry("status", "user_interact");
            assertThat(String.valueOf(state.get("question"))).contains("city1").contains("city2");
        }

        @Test
        void flowInputAliasFlowInput() {
            ComponentExecutable exec = registry.create(
                    AssembledNode.of(
                            "i",
                            "jiuwen.flowInput",
                            Map.of(
                                    "userFields",
                                    Map.of(
                                            "inputs",
                                            List.of(Map.of("id", "name", "required", true, "type", "string"))))),
                    NodeBuildContext.defaults("wf"));
            AtomicReference<Map<String, Object>> bucket = new AtomicReference<>(new HashMap<>());
            @SuppressWarnings("unchecked")
            Map<String, Object> out =
                    (Map<String, Object>) exec.invoke(Map.of("userFields", Map.of()), statefulSession(bucket), null);
            assertThat(out).isEmpty();
            assertThat(bucket.get().get(FlowInputEngine.STATE_KEY)).isInstanceOf(Map.class);
        }

        @Test
        void needInputsMessageAndParseLikeBaseHelpers() {
            Map<String, Object> config =
                    Map.of(
                            "userFields",
                            Map.of(
                                    "inputs",
                                    List.of(Map.of(
                                            "id", "name", "type", "string", "required", true, "description", "用户姓名"))));
            assertThat(FlowInputUtils.buildInputsMessage(config)).contains("\"name\"");
            assertThat(FlowInputUtils.parseUserResponse("name:张三\nage:25"))
                    .containsEntry("name", "张三")
                    .containsEntry("age", "25");
        }
    }

    @Nested
    class InterruptResumeWorkflow {
        @Test
        void startToInputToEnd_interruptThenResume_likeFlowInputWorkflow() {
            AtomicReference<Map<String, Object>> bucket = new AtomicReference<>(new HashMap<>());
            NodeSessionApi session = statefulSession(bucket);

            ComponentExecutable input = registry.create(
                    AssembledNode.of("node_flow_input", "jiuwen.input", flowInputConfNameAgeEmail()),
                    NodeBuildContext.defaults("wf_flow_input"));
            ComponentExecutable end = registry.create(
                    AssembledNode.of(
                            "node_end",
                            "jiuwen.end",
                            Map.of(
                                    "responseTemplate",
                                    "用户信息收集完成：姓名={{name}}，年龄={{age}}，邮箱={{email}}")),
                    NodeBuildContext.defaults("wf_flow_input"));

            @SuppressWarnings("unchecked")
            Map<String, Object> hangOut =
                    (Map<String, Object>) input.invoke(Map.of("userFields", Map.of()), session, null);
            assertThat(hangOut).isEmpty();
            @SuppressWarnings("unchecked")
            Map<String, Object> state = (Map<String, Object>) bucket.get().get(FlowInputEngine.STATE_KEY);
            assertThat(state).containsEntry("status", "user_interact");

            Map<String, Object> filled = uf(input.invoke(
                    Map.of("userFields", Map.of("name", "张三", "age", "25", "email", "a@b.com")),
                    session,
                    null));
            assertThat(filled)
                    .containsEntry("name", "张三")
                    .containsEntry("email", "a@b.com")
                    .doesNotContainKeys("inputReceived", "flowInputState", "hangState");
            // age coerced to number
            assertThat(filled.get("age")).isInstanceOf(Number.class);

            Map<String, Object> endOut = uf(end.invoke(Map.of("userFields", filled), session, null));
            assertThat(String.valueOf(endOut.get("answer")))
                    .contains("张三")
                    .contains("a@b.com");
            assertThat(endOut.get("__terminal__")).isEqualTo(true);
        }

        @Test
        void requiredFieldMissingOnResumeThrows() {
            AtomicReference<Map<String, Object>> bucket = new AtomicReference<>(new HashMap<>());
            NodeSessionApi session = statefulSession(bucket);
            ComponentExecutable exec = registry.create(
                    AssembledNode.of("node_flow_input", "jiuwen.input", flowInputConfNameAgeEmail()),
                    NodeBuildContext.defaults("wf"));
            // first pass returns {} and parks state
            assertThat(exec.invoke(Map.of("userFields", Map.of()), session, null))
                    .isEqualTo(Map.of());
            assertThatThrownBy(() ->
                            exec.invoke(Map.of("userFields", Map.of("name", "only-name")), session, null))
                    .isInstanceOf(NodeExecutionException.class)
                    .hasMessageContaining("email");
        }

        @Test
        void fieldsAlreadyPresentStillHangsOnStart_likePython() {
            ComponentExecutable exec = registry.create(
                    AssembledNode.of("node_flow_input", "jiuwen.input", flowInputConfNameAgeEmail()),
                    NodeBuildContext.defaults("wf"));
            AtomicReference<Map<String, Object>> bucket = new AtomicReference<>(new HashMap<>());
            @SuppressWarnings("unchecked")
            Map<String, Object> out = (Map<String, Object>) exec.invoke(
                    Map.of(
                            "userFields",
                            Map.of("name", "Ada", "email", "ada@ex.com", "age", 30)),
                    statefulSession(bucket),
                    null);
            assertThat(out).isEmpty();
            @SuppressWarnings("unchecked")
            Map<String, Object> state = (Map<String, Object>) bucket.get().get(FlowInputEngine.STATE_KEY);
            assertThat(state).containsEntry("status", "user_interact");
        }

        @Test
        void startInputEnd_interruptThenResumeLinear_likeFlowInputBaseIr() {
            AtomicReference<Map<String, Object>> bucket = new AtomicReference<>(new HashMap<>());
            NodeSessionApi session = statefulSession(bucket);

            ComponentExecutable start = registry.create(
                    AssembledNode.of("start", "jiuwen.start", Map.of()),
                    NodeBuildContext.defaults("flow_input_linear"));
            ComponentExecutable input = registry.create(
                    AssembledNode.of("node_flow_input", "jiuwen.input", flowInputConfNameAgeEmail()),
                    NodeBuildContext.defaults("flow_input_linear"));
            ComponentExecutable end = registry.create(
                    AssembledNode.of(
                            "node_end",
                            "jiuwen.end",
                            Map.of(
                                    "responseTemplate",
                                    "用户信息收集完成：姓名={{name}}，年龄={{age}}，邮箱={{email}}")),
                    NodeBuildContext.defaults("flow_input_linear"));

            Map<String, Object> afterStart =
                    asMap(start.invoke(Map.of("userFields", Map.of("query", "init")), session, null));
            @SuppressWarnings("unchecked")
            Map<String, Object> hangOut = (Map<String, Object>) input.invoke(afterStart, session, null);
            assertThat(hangOut).isEmpty();

            Map<String, Object> filled = uf(input.invoke(
                    Map.of(
                            "userFields",
                            Map.of("name", "李四", "age", 28, "email", "li@ex.com")),
                    session,
                    null));
            assertThat(filled).containsEntry("name", "李四");
            Map<String, Object> endOut = uf(end.invoke(Map.of("userFields", filled), session, null));
            assertThat(String.valueOf(endOut.get("answer"))).contains("李四").contains("li@ex.com");
            assertThat(endOut.get("__terminal__")).isEqualTo(true);
        }

        @Test
        void nestedInputInterruptResumeLinearChain_startNestEnd() {
            AtomicReference<Map<String, Object>> bucket = new AtomicReference<>(new HashMap<>());
            NodeSessionApi session = statefulSession(bucket);

            // Child must start at Input (not Start): Nested checks session interrupt after every child
            // node; Start would short-circuit resume before Input can clear flow_input_state.
            AssembledWorkflow child = new AssembledWorkflow(
                    "sub_input_chain",
                    List.of(
                            AssembledNode.of("node_flow_input", "jiuwen.input", flowInputConfNameAgeEmail()),
                            AssembledNode.of(
                                    "node_end",
                                    "jiuwen.end",
                                    Map.of("responseTemplate", "nested {{name}} {{email}}"))));
            NodeBuildContext ctx = new NodeBuildContext(
                    "wf_parent_input",
                    0,
                    5,
                    null,
                    configs -> child,
                    new InMemoryToolRegistry(),
                    registry);

            ComponentExecutable pStart =
                    registry.create(AssembledNode.of("p_start", "jiuwen.start", Map.of()), ctx);
            ComponentExecutable nest = registry.create(
                    AssembledNode.of("sub", "jiuwen.subWorkflow", Map.of("workflowId", "sub_input_chain")), ctx);
            ComponentExecutable pEnd = registry.create(
                    AssembledNode.of(
                            "p_end",
                            "jiuwen.end",
                            Map.of("responseTemplate", "parent done {{name}}")),
                    ctx);

            Map<String, Object> current =
                    asMap(pStart.invoke(Map.of("userFields", Map.of("query", "x")), session, null));
            Map<String, Object> hang = uf(nest.invoke(current, session, null));
            assertThat(hang.get("hangState")).isEqualTo("INPUT_REQUIRED");
            assertThat(hang.get("nestedWorkflowState")).isEqualTo("user_interact");

            Map<String, Object> resumeIn = new LinkedHashMap<>(uf(current));
            resumeIn.put("name", "王五");
            resumeIn.put("age", 40);
            resumeIn.put("email", "w@ex.com");
            Map<String, Object> resumed = uf(nest.invoke(Map.of("userFields", resumeIn), session, null));
            assertThat(resumed)
                    .containsEntry("name", "王五")
                    .containsEntry("nestedWorkflowState", "end")
                    .doesNotContainKey("inputReceived");

            Map<String, Object> parentDone = uf(pEnd.invoke(Map.of("userFields", resumed), session, null));
            assertThat(String.valueOf(parentDone.get("answer"))).contains("王五");
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> asMap(Object o) {
            return (Map<String, Object>) o;
        }
    }

    @Nested
    class NestedSubWorkflowInput {
        @Test
        void inputInsideSubWorkflow_interruptAndResume() {
            AtomicReference<Map<String, Object>> bucket = new AtomicReference<>(new HashMap<>());
            NodeSessionApi session = statefulSession(bucket);

            AssembledWorkflow child = new AssembledWorkflow(
                    "sub_with_input",
                    List.of(
                            AssembledNode.of("node_flow_input", "jiuwen.input", flowInputConfNameAgeEmail()),
                            AssembledNode.of(
                                    "node_end",
                                    "jiuwen.end",
                                    Map.of("responseTemplate", "done {{name}}"))));
            NodeBuildContext ctx = new NodeBuildContext(
                    "wf_parent",
                    0,
                    5,
                    null,
                    configs -> child,
                    new InMemoryToolRegistry(),
                    registry);
            ComponentExecutable nest = registry.create(
                    AssembledNode.of("sub", "jiuwen.subWorkflow", Map.of("workflowId", "sub_with_input")), ctx);

            Map<String, Object> hang = uf(nest.invoke(Map.of("userFields", Map.of()), session, null));
            assertThat(hang.get("nestedWorkflowState")).isEqualTo("user_interact");
            assertThat(hang.get("hangState")).isEqualTo("INPUT_REQUIRED");

            Map<String, Object> done = uf(nest.invoke(
                    Map.of("userFields", Map.of("name", "Bob", "email", "b@c.com")), session, null));
            assertThat(done)
                    .containsEntry("name", "Bob")
                    .containsEntry("nestedWorkflowState", "end");
        }
    }
}
