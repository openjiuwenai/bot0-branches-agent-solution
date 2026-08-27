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
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.registry.NodeTypeRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Java port of {@code test_flow_exception.py} core branch → exception vs end behavior
 * (node-level / linear chain; mock session; no WorkflowWrapper network).
 *
 * <p>Python: query contains "异常" → ExceptionInfo abort; otherwise → End.
 *
 * @since 2026-08-25
 */

class WorkflowNodeExceptionCasesTest {
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

    private ComponentExecutable branchNode() {
        // Equivalent to Python: '异常' not in query → end; else → exception (inverted as contains→exception)
        return registry.create(
                AssembledNode.of(
                        "branch",
                        "jiuwen.branch",
                        Map.of(
                                "branches",
                                List.of(
                                        Map.of(
                                                "branchId",
                                                "node_exception_termination",
                                                "condition",
                                                Map.of(
                                                        "operator",
                                                        "contains",
                                                        "left",
                                                        Map.of("value", "query"),
                                                        "right",
                                                        "异常")),
                                        Map.of("branchId", "end", "isDefault", true)))),
                NodeBuildContext.defaults("wf_exception"));
    }

    private ComponentExecutable exceptionNode() {
        return registry.create(
                AssembledNode.of("node_exception_termination", "jiuwen.exception", Map.of()),
                NodeBuildContext.defaults("wf_exception"));
    }

    private ComponentExecutable endNode() {
        return registry.create(
                AssembledNode.of(
                        "end",
                        "jiuwen.end",
                        Map.of("responseTemplate", "## 正常结束----")),
                NodeBuildContext.defaults("wf_exception"));
    }

    @Nested
    class BranchRouting {
        @Test
        void queryWithExceptionRoutesToExceptionBranch() {
            @SuppressWarnings("unchecked")
            Map<String, Object> out = (Map<String, Object>)
                    branchNode().invoke(Map.of("userFields", Map.of("query", "异常")), null, null);
            assertThat(out.get("branchId")).isEqualTo("node_exception_termination");
        }

        @Test
        void normalQueryRoutesToEndBranch() {
            @SuppressWarnings("unchecked")
            Map<String, Object> out = (Map<String, Object>)
                    branchNode().invoke(Map.of("userFields", Map.of("query", "正常办理")), null, null);
            assertThat(out.get("branchId")).isEqualTo("end");
        }
    }

    @Nested
    class LinearChain {
        @Test
        void exceptionPathAbortsWithPayload() {
            NodeSessionApi session = mock(NodeSessionApi.class);
            when(session.getGlobalState("__abort__")).thenReturn(null);
            List<Map<String, Object>> frames = new ArrayList<>();
            doAnswer(inv -> {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> f = inv.getArgument(0);
                        frames.add(f);
                        return null;
                    })
                    .when(session)
                    .writeCustomStream(any());
            doAnswer(inv -> null).when(session).updateGlobalState(any());

            @SuppressWarnings("unchecked")
            Map<String, Object> branchOut = (Map<String, Object>)
                    branchNode().invoke(Map.of("userFields", Map.of("query", "异常")), session, null);
            assertThat(branchOut.get("branchId")).isEqualTo("node_exception_termination");

            Map<String, Object> exInputs = new HashMap<>();
            exInputs.put(
                    "userFields",
                    Map.of(
                            "error_code",
                            500,
                            "error_message",
                            "用户自定义错误信息----",
                            "message",
                            "用户自定义错误信息----",
                            "query",
                            "异常"));

            assertThatThrownBy(() -> exceptionNode().invoke(exInputs, session, null))
                    .isInstanceOf(com.openjiuwen.studio.dsl.flowexception.WorkflowAbortException.class)
                    .satisfies(ex -> {
                        var abort = (com.openjiuwen.studio.dsl.flowexception.WorkflowAbortException) ex;
                        assertThat(abort.data()).containsEntry("message", "用户自定义错误信息----");
                    });
            assertThat(frames).isNotEmpty();
            assertThat(frames.get(0).get("type")).isEqualTo("workflow_exception");
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) frames.get(0).get("data");
            assertThat(data).containsEntry("jiuwen_exception_node_id", "node_exception_termination");
            assertThat(data).containsEntry("message", "用户自定义错误信息----");
        }

        @Test
        void endPathCompletesNormally() {
            NodeSessionApi session = mock(NodeSessionApi.class);
            AtomicReference<Object> done = new AtomicReference<>();
            when(session.getState("end_invoke_executed")).thenAnswer(inv -> done.get());
            doAnswer(inv -> {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> patch = inv.getArgument(0);
                        done.set(patch.get("end_invoke_executed"));
                        return null;
                    })
                    .when(session)
                    .updateState(any());

            @SuppressWarnings("unchecked")
            Map<String, Object> branchOut = (Map<String, Object>)
                    branchNode().invoke(Map.of("userFields", Map.of("query", "正常")), session, null);
            assertThat(branchOut.get("branchId")).isEqualTo("end");

            Map<String, Object> fields = uf(endNode()
                    .invoke(Map.of("userFields", Map.of("answer", "ok", "query", "正常")), session, null));
            assertThat(fields.get("response")).isEqualTo("## 正常结束----");
            assertThat(fields.get("__terminal__")).isEqualTo(true);
        }
    }

    @Nested
    class StartQuestionerBranchChain {
        @Test
        void startQuestionerMockBranchExceptionPath() {
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
            when(session.getGlobalState("__abort__")).thenReturn(null);
            doAnswer(inv -> null).when(session).updateGlobalState(any());
            doAnswer(inv -> null).when(session).writeCustomStream(any());

            ComponentExecutable start =
                    registry.create(AssembledNode.of("start", "jiuwen.start", Map.of()), NodeBuildContext.defaults("wf"));
            ComponentExecutable questioner = registry.create(
                    AssembledNode.of(
                            "q",
                            "jiuwen.questioner",
                            Map.of(
                                    "extractFieldsFromResponse",
                                    true,
                                    "fieldNames",
                                    List.of(Map.of(
                                            "fieldName",
                                            "user_input",
                                            "type",
                                            "string",
                                            "cnFieldName",
                                            "用户输入",
                                            "required",
                                            true)),
                                    "mockExtractedFields",
                                    Map.of("user_input", "异常"))),
                    NodeBuildContext.defaults("wf"));

            Map<String, Object> current = Map.of("userFields", Map.of("query", "异常办理"));
            current = asMap(start.invoke(current, session, null));
            current = asMap(questioner.invoke(current, session, null));
            Map<String, Object> qf = uf(current);
            // mock extract may answer in one shot
            if ("INPUT_REQUIRED".equals(String.valueOf(qf.get("hangState")))) {
                Map<String, Object> resume = new HashMap<>(qf);
                resume.put("query", "异常");
                resume.put("__single_debug_recovery__", true);
                current = asMap(questioner.invoke(Map.of("userFields", resume), session, null));
                qf = uf(current);
            }
            assertThat(qf.get("user_input")).isEqualTo("异常");

            @SuppressWarnings("unchecked")
            Map<String, Object> branchOut = (Map<String, Object>)
                    branchNode().invoke(Map.of("userFields", Map.of("query", qf.get("user_input"))), session, null);
            assertThat(branchOut.get("branchId")).isEqualTo("node_exception_termination");
            assertThatThrownBy(() -> exceptionNode()
                            .invoke(
                                    Map.of(
                                            "userFields",
                                            Map.of(
                                                    "message",
                                                    "用户自定义错误信息----",
                                                    "error_code",
                                                    500,
                                                    "query",
                                                    "异常")),
                                    session,
                                    null))
                    .isInstanceOf(com.openjiuwen.studio.dsl.flowexception.WorkflowAbortException.class)
                    .satisfies(ex -> assertThat(
                                    ((com.openjiuwen.studio.dsl.flowexception.WorkflowAbortException) ex)
                                            .data())
                            .containsEntry("message", "用户自定义错误信息----"));
        }

        @Test
        void startQuestionerMockBranchEndPath() {
            NodeSessionApi session = mock(NodeSessionApi.class);
            AtomicReference<Object> done = new AtomicReference<>();
            when(session.getState("end_invoke_executed")).thenAnswer(inv -> done.get());
            doAnswer(inv -> {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> patch = inv.getArgument(0);
                        done.set(patch.get("end_invoke_executed"));
                        return null;
                    })
                    .when(session)
                    .updateState(any());

            ComponentExecutable questioner = registry.create(
                    AssembledNode.of(
                            "q",
                            "jiuwen.questioner",
                            Map.of(
                                    "extractFieldsFromResponse",
                                    true,
                                    "fieldNames",
                                    List.of(Map.of(
                                            "fieldName",
                                            "user_input",
                                            "type",
                                            "string",
                                            "required",
                                            true)),
                                    "mockExtractedFields",
                                    Map.of("user_input", "正常办理"))),
                    NodeBuildContext.defaults("wf"));
            Map<String, Object> qf =
                    uf(questioner.invoke(Map.of("userFields", Map.of("query", "正常办理")), session, null));
            assertThat(qf.get("user_input")).isEqualTo("正常办理");

            @SuppressWarnings("unchecked")
            Map<String, Object> branchOut = (Map<String, Object>)
                    branchNode().invoke(Map.of("userFields", Map.of("query", "正常办理")), session, null);
            assertThat(branchOut.get("branchId")).isEqualTo("end");
            Map<String, Object> fields = uf(endNode()
                    .invoke(Map.of("userFields", Map.of("answer", "ok", "query", "正常办理")), session, null));
            assertThat(fields.get("response")).isEqualTo("## 正常结束----");
            assertThat(fields.get("__terminal__")).isEqualTo(true);
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> asMap(Object o) {
            return (Map<String, Object>) o;
        }
    }

    @Nested
    class ExceptionNodeVariants {
        @Test
        void softConfigIgnoredStillAborts() {
            // soft/defaultOutputs belong to exception_handler.py, not ExceptionInfo
            ComponentExecutable soft = registry.create(
                    AssembledNode.of(
                            "ex",
                            "jiuwen.exception",
                            Map.of(
                                    "handleType",
                                    "soft",
                                    "defaultOutputs",
                                    Map.of("fallback", true, "error_code", 500))),
                    NodeBuildContext.defaults("wf"));
            assertThatThrownBy(() -> soft.invoke(Map.of("userFields", Map.of("message", "x")), null, null))
                    .isInstanceOf(com.openjiuwen.studio.dsl.flowexception.WorkflowAbortException.class);
        }

        @Test
        void abortWithoutSessionStillThrows() {
            assertThatThrownBy(
                            () ->
                                    exceptionNode()
                                            .invoke(
                                                    Map.of(
                                                            "userFields",
                                                            Map.of("message", "boom", "error_code", 500)),
                                                    null,
                                                    null))
                    .isInstanceOf(com.openjiuwen.studio.dsl.flowexception.WorkflowAbortException.class)
                    .satisfies(ex -> assertThat(
                                    ((com.openjiuwen.studio.dsl.flowexception.WorkflowAbortException) ex)
                                            .data())
                            .containsEntry("message", "boom"));
        }

        @Test
        void concurrentAbortSkipsSecondFrame() {
            AtomicReference<Object> abortFlag = new AtomicReference<>();
            NodeSessionApi session = mock(NodeSessionApi.class);
            when(session.getGlobalState("__abort__")).thenAnswer(inv -> abortFlag.get());
            doAnswer(inv -> {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> patch = inv.getArgument(0);
                        abortFlag.set(patch.get("__abort__"));
                        return null;
                    })
                    .when(session)
                    .updateGlobalState(any());
            List<Map<String, Object>> frames = new ArrayList<>();
            doAnswer(inv -> {
                        frames.add(inv.getArgument(0));
                        return null;
                    })
                    .when(session)
                    .writeCustomStream(any());

            ComponentExecutable a = exceptionNode();
            ComponentExecutable b =
                    registry.create(
                            AssembledNode.of("ex2", "jiuwen.exception", Map.of()),
                            NodeBuildContext.defaults("wf_exception"));
            assertThatThrownBy(() -> a.invoke(Map.of("userFields", Map.of("n", 1)), session, null))
                    .isInstanceOf(com.openjiuwen.studio.dsl.flowexception.WorkflowAbortException.class);
            assertThat(frames).hasSize(1);
            assertThatThrownBy(() -> b.invoke(Map.of("userFields", Map.of("n", 2)), session, null))
                    .isInstanceOf(com.openjiuwen.studio.dsl.flowexception.WorkflowAbortException.class);
            assertThat(frames).hasSize(1);
        }
    }
}
