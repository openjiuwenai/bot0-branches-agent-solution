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

import com.openjiuwen.core.session.interaction.InteractiveInput;
import com.openjiuwen.core.session.internal.NodeSession;
import com.openjiuwen.core.session.internal.WorkflowSession;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.session.state.InMemoryState;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.adapter.control.NestedWorkflowNodeHandler;
import com.openjiuwen.studio.dsl.adapter.control.SubRequestScope;
import com.openjiuwen.studio.dsl.adapter.StudioStreamFrames;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.AssembledWorkflow;
import com.openjiuwen.studio.dsl.model.NodeCauseCode;
import com.openjiuwen.studio.dsl.registry.NodeTypeRegistry;
import com.openjiuwen.studio.dsl.support.InMemoryToolRegistry;
import com.openjiuwen.studio.dsl.util.SanitizeMessage;
import com.openjiuwen.studio.dsl.util.SessionStateIsolator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Java port of high-value node-level cases from {@code test_sub_workflow.py}
 * ({@link NestedWorkflowNodeHandler}; mock session, no network).
 *
 * <p>Covers REQUEST enter/exit sync + nested stream (Python SubWorkflow.stream /
 * {@code _enter_sub_request_scope} / {@code _exit_sub_request_scope}).
 *
 * @since 2026-08-26
 */

class WorkflowNodeSubWorkflowCasesTest {
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

    private NodeBuildContext ctxWithChild(AssembledWorkflow child) {
        return new NodeBuildContext(
                "wf_parent",
                0,
                5,
                null,
                configs -> child,
                new InMemoryToolRegistry(),
                registry);
    }

    private static NodeSessionApi statefulSession(AtomicReference<Map<String, Object>> bucket) {
        return statefulSession(bucket, new AtomicReference<>(new HashMap<>()));
    }
    private static NodeSessionApi statefulSession(
            AtomicReference<Map<String, Object>> bucket, AtomicReference<Map<String, Object>> global) {
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
        when(session.getGlobalState(any())).thenAnswer(inv -> {
            Object key = inv.getArgument(0);
            return global.get().get(String.valueOf(key));
        });
        doAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> patch = inv.getArgument(0);
                    Map<String, Object> cur = new LinkedHashMap<>(global.get());
                    patch.forEach((k, v) -> {
                        if (v == null) {
                            cur.remove(k);
                        } else {
                            cur.put(k, v);
                        }
                    });
                    global.set(cur);
                    return null;
                })
                .when(session)
                .updateGlobalState(any());
        return session;
    }

    @Nested
    class InterruptStateKeys {
        @Test
        void childInterruptStateKeysMatchPython() {
            assertThat(SessionStateIsolator.CHILD_INTERRUPT_STATE_KEYS)
                    .containsExactlyInAnyOrder("questioner_state", "flow_qa_state", "flow_input_state");
        }

        @Test
        void isChildInterruptDetectsHangAndFlags() {
            assertThat(NestedWorkflowNodeHandler.isChildInterrupt(Map.of("hangState", "INPUT_REQUIRED")))
                    .isTrue();
            assertThat(NestedWorkflowNodeHandler.isChildInterrupt(Map.of("should_interrupt", true))).isTrue();
            assertThat(NestedWorkflowNodeHandler.isChildInterrupt(Map.of("flowInputState", "user_interact")))
                    .isTrue();
            assertThat(NestedWorkflowNodeHandler.isChildInterrupt(Map.of("result", "ok"))).isFalse();
        }
    }

    @Nested
    class ParentChildInvoke {
        @Test
        void nestedInvokesChildAndReturnsUserFieldsWithDepth() {
            AssembledWorkflow child = new AssembledWorkflow(
                    "child",
                    List.of(
                            AssembledNode.of(
                                    "sv",
                                    "jiuwen.setVariable",
                                    Map.of("variableMapping", Map.of("result", "modified_by_sub"))),
                            AssembledNode.of("e", "jiuwen.end", Map.of())));
            ComponentExecutable exec = registry.create(
                    AssembledNode.of("nest", "jiuwen.subWorkflow", Map.of("workflowId", "child")),
                    ctxWithChild(child));
            Map<String, Object> fields =
                    uf(exec.invoke(Map.of("userFields", Map.of("query", "standalone")), mock(NodeSessionApi.class), null));
            assertThat(fields)
                    .containsEntry("result", "modified_by_sub")
                    .containsEntry("__nestingDepth__", 1)
                    .containsEntry("nestedWorkflowState", "end")
                    .containsEntry("should_interrupt", false);
        }

        @Test
        void nestingDepthExceededAtCreate() {
            NodeBuildContext deep = new NodeBuildContext(
                    "wf",
                    5,
                    5,
                    null,
                    configs -> new AssembledWorkflow("c", List.of(AssembledNode.of("e", "jiuwen.end", Map.of()))),
                    new InMemoryToolRegistry(),
                    registry);
            assertThatThrownBy(() ->
                            registry.create(
                                    AssembledNode.of("nest", "jiuwen.subWorkflow", Map.of("workflowId", "c")), deep))
                    .isInstanceOf(NodeExecutionException.class)
                    .satisfies(ex -> {
                        NodeExecutionException ne = (NodeExecutionException) ex;
                        assertThat(ne.causeCode()).isEqualTo(NodeCauseCode.NESTING_DEPTH_EXCEEDED);
                    });
        }

        @Test
        void childFailureWrappedAsNodeInvokeFailed() {
            AssembledWorkflow child = new AssembledWorkflow(
                    "child",
                    List.of(AssembledNode.of(
                            "ex",
                            "jiuwen.exception",
                            Map.of("message", "boom", "abort", true))));
            ComponentExecutable exec = registry.create(
                    AssembledNode.of("nest", "jiuwen.subWorkflow", Map.of("workflowId", "child")),
                    ctxWithChild(child));
            assertThatThrownBy(() -> exec.invoke(Map.of("userFields", Map.of()), mock(NodeSessionApi.class), null))
                    .isInstanceOf(NodeExecutionException.class)
                    .hasMessageContaining("child failed");
        }
    }

    @Nested
    class SessionIsolationAndInterrupt {
        @Test
        void restoresParentSessionStateAndDoesNotLeakChildVars() {
            AtomicReference<Map<String, Object>> bucket = new AtomicReference<>(new LinkedHashMap<>());
            bucket.get().put("parentOnly", "keep");
            NodeSessionApi session = statefulSession(bucket);

            AssembledWorkflow child = new AssembledWorkflow(
                    "child",
                    List.of(
                            AssembledNode.of(
                                    "sv",
                                    "jiuwen.setVariable",
                                    Map.of("variableMapping", Map.of("childLeak", 1))),
                            AssembledNode.of("e", "jiuwen.end", Map.of())));
            ComponentExecutable exec = registry.create(
                    AssembledNode.of("nest", "jiuwen.subWorkflow", Map.of("workflowId", "child")),
                    ctxWithChild(child));
            exec.invoke(Map.of("userFields", Map.of("q", "x")), session, null);
            assertThat(bucket.get()).containsEntry("parentOnly", "keep").doesNotContainKey("childLeak");
        }

        @Test
        void childInputInterruptBubblesAndResumeCompletes() {
            AtomicReference<Map<String, Object>> bucket = new AtomicReference<>(new HashMap<>());
            NodeSessionApi session = statefulSession(bucket);

            AssembledWorkflow child = new AssembledWorkflow(
                    "child_input",
                    List.of(
                            AssembledNode.of(
                                    "node_input",
                                    "jiuwen.input",
                                    Map.of(
                                            "userFields",
                                            Map.of(
                                                    "inputs",
                                                    List.of(Map.of(
                                                            "id", "name", "type", "string", "required", true))))),
                            AssembledNode.of("e", "jiuwen.end", Map.of())));
            ComponentExecutable nest = registry.create(
                    AssembledNode.of("nest", "jiuwen.subWorkflow", Map.of("workflowId", "child_input")),
                    ctxWithChild(child));

            Map<String, Object> hang = uf(nest.invoke(Map.of("userFields", Map.of()), session, null));
            assertThat(hang.get("hangState")).isEqualTo("INPUT_REQUIRED");
            assertThat(hang.get("nestedWorkflowState")).isEqualTo("user_interact");
            assertThat(hang.get("should_interrupt")).isEqualTo(true);
            assertThat(bucket.get()).containsKey("flow_input_state");

            Map<String, Object> done =
                    uf(nest.invoke(Map.of("userFields", Map.of("name", "Kayla")), session, null));
            assertThat(done)
                    .containsEntry("name", "Kayla")
                    .containsEntry("nestedWorkflowState", "end")
                    .doesNotContainKey("inputReceived");
        }

        @Test
        void detectInterruptInSessionFromFlowInputState() {
            AtomicReference<Map<String, Object>> bucket = new AtomicReference<>(new LinkedHashMap<>());
            bucket.get()
                    .put(
                            "flow_input_state",
                            Map.of("status", "user_interact", "question", "请输入姓名"));
            NodeSessionApi session = statefulSession(bucket);
            assertThat(NestedWorkflowNodeHandler.detectInterruptInSession(session)).isTrue();
            bucket.set(Map.of("flow_input_state", Map.of("status", "start")));
            assertThat(NestedWorkflowNodeHandler.detectInterruptInSession(session)).isFalse();
        }

        @Test
        void childQuestionerInterruptPreservesInterruptKeysAndResumes() {
            AtomicReference<Map<String, Object>> bucket = new AtomicReference<>(new HashMap<>());
            NodeSessionApi session = statefulSession(bucket);

            AssembledWorkflow child = new AssembledWorkflow(
                    "child_q",
                    List.of(
                            AssembledNode.of(
                                    "q",
                                    "jiuwen.questioner",
                                    Map.of(
                                            "questionContent",
                                            "请输入城市",
                                            "extractFieldsFromResponse",
                                            false)),
                            AssembledNode.of(
                                    "e",
                                    "jiuwen.end",
                                    Map.of("responseTemplate", "city={{USER_RESPONSE}}"))));
            ComponentExecutable nest = registry.create(
                    AssembledNode.of("nest", "jiuwen.subWorkflow", Map.of("workflowId", "child_q")),
                    ctxWithChild(child));

            Map<String, Object> hang = uf(nest.invoke(Map.of("userFields", Map.of()), session, null));
            assertThat(hang.get("hangState")).isEqualTo("INPUT_REQUIRED");
            assertThat(hang.get("nestedWorkflowState")).isEqualTo("user_interact");
            assertThat(bucket.get()).containsKey("questioner_state");

            Map<String, Object> resumeUf = new LinkedHashMap<>(hang);
            resumeUf.put("query", "上海");
            resumeUf.put("__single_debug_recovery__", true);
            Map<String, Object> done = uf(nest.invoke(Map.of("userFields", resumeUf), session, null));
            assertThat(done.get("nestedWorkflowState")).isEqualTo("end");
            assertThat(done.get("USER_RESPONSE")).isEqualTo("上海");
        }

        @Test
        void nestedStartReadsRequestVariablesFromGlobalState() {
            AtomicReference<Map<String, Object>> bucket = new AtomicReference<>(new HashMap<>());
            AtomicReference<Map<String, Object>> global = new AtomicReference<>(new HashMap<>());
            global.get().put("_REQUEST", Map.of("tenant", "t-req", "channel", "web"));
            global.get().put("_request", Map.of("tenant", "t-req", "channel", "web"));
            NodeSessionApi session = statefulSession(bucket, global);

            AssembledWorkflow child = new AssembledWorkflow(
                    "child_req",
                    List.of(
                            AssembledNode.of("start", "jiuwen.start", Map.of()),
                            AssembledNode.of(
                                    "e",
                                    "jiuwen.end",
                                    Map.of("responseTemplate", "tenant={{tenant}} channel={{channel}}"))));
            ComponentExecutable nest = registry.create(
                    AssembledNode.of("nest", "jiuwen.subWorkflow", Map.of("workflowId", "child_req")),
                    ctxWithChild(child));

            Map<String, Object> done =
                    uf(nest.invoke(Map.of("userFields", Map.of("query", "hi")), session, null));
            assertThat(done)
                    .containsEntry("tenant", "t-req")
                    .containsEntry("channel", "web")
                    .containsEntry("nestedWorkflowState", "end");
            assertThat(String.valueOf(done.get("answer"))).contains("t-req").contains("web");
        }

        @Test
        void requestScope_bidirectionalSyncDropsChildOnlyKeys() {
            // Python _enter/_exit_sub_request_scope: parent {a,b}; child writes a + temp c;
            // exit keeps a synced, restores b, drops c.
            AtomicReference<Map<String, Object>> bucket = new AtomicReference<>(new HashMap<>());
            AtomicReference<Map<String, Object>> global = new AtomicReference<>(new HashMap<>());
            Map<String, Object> parentReq = new LinkedHashMap<>();
            parentReq.put("a", 1);
            parentReq.put("b", 2);
            global.get().put("_request", new LinkedHashMap<>(parentReq));
            global.get().put("_REQUEST", new LinkedHashMap<>(parentReq));
            NodeSessionApi session = statefulSession(bucket, global);

            AssembledWorkflow child = new AssembledWorkflow(
                    "child_sync",
                    List.of(
                            AssembledNode.of(
                                    "sv",
                                    "jiuwen.setVariable",
                                    Map.of(
                                            "variableMapping",
                                            Map.of(
                                                    "${_request.a}",
                                                    99,
                                                    "${_request.child_temp}",
                                                    "only-child"))),
                            AssembledNode.of("e", "jiuwen.end", Map.of())));
            ComponentExecutable nest = registry.create(
                    AssembledNode.of("nest", "jiuwen.subWorkflow", Map.of("workflowId", "child_sync")),
                    ctxWithChild(child));

            Map<String, Object> childInputs = new LinkedHashMap<>();
            childInputs.put("userFields", Map.of("query", "x"));
            childInputs.put("_REQUEST", Map.of("b", 2)); // child seed; a inherits from parent on enter
            nest.invoke(childInputs, session, null);

            Map<String, Object> restored = SubRequestScope.readRequest(session);
            assertThat(restored).containsEntry("a", 99).containsEntry("b", 2);
            assertThat(restored).doesNotContainKey("child_temp");
        }
    }

    @Nested
    class NestedStream {
        @Test
        void stream_emitsChildFramesAndWorkflowEnd() {
            AssembledWorkflow child = new AssembledWorkflow(
                    "child_stream",
                    List.of(
                            AssembledNode.of(
                                    "msg",
                                    "jiuwen.message",
                                    Map.of("template", "hello_{{name}}")),
                            AssembledNode.of("e", "jiuwen.end", Map.of("responseTemplate", "{{result}}"))));
            ComponentExecutable nest = registry.create(
                    AssembledNode.of("nest", "jiuwen.subWorkflow", Map.of("workflowId", "child_stream")),
                    ctxWithChild(child));

            List<Object> frames = new ArrayList<>();
            Iterator<Object> it =
                    nest.stream(Map.of("userFields", Map.of("name", "sub")), mock(NodeSessionApi.class), null);
            it.forEachRemaining(frames::add);

            assertThat(frames).isNotEmpty();
            assertThat(frames.stream()
                            .anyMatch(f -> f instanceof Map<?, ?> m
                                    && StudioStreamFrames.WORKFLOW_END.equals(String.valueOf(m.get("type")))))
                    .isTrue();
            boolean hasHello = frames.stream().anyMatch(f -> {
                if (!(f instanceof Map<?, ?> m)) {
                    return false;
                }
                Object data = m.get("data");
                return data instanceof Map<?, ?> d && String.valueOf(d.get("answer")).contains("hello_sub");
            });
            assertThat(hasHello).isTrue();
        }
    }

    @Nested
    class AliasAndResolver {
        @Test
        void workflowCompositeAliasCreatesNested() {
            AssembledWorkflow child =
                    new AssembledWorkflow("c", List.of(AssembledNode.of("e", "jiuwen.end", Map.of())));
            ComponentExecutable exec = registry.create(
                    AssembledNode.of("nest", "jiuwen.workflowComposite", Map.of("workflowId", "c")),
                    ctxWithChild(child));
            assertThat(uf(exec.invoke(Map.of("userFields", Map.of("x", 1)), mock(NodeSessionApi.class), null)))
                    .containsEntry("nestedWorkflowState", "end");
        }

        @Test
        void nullResolverIsRefInvalid() {
            NodeBuildContext ctx = new NodeBuildContext(
                    "wf",
                    0,
                    5,
                    null,
                    configs -> null,
                    new InMemoryToolRegistry(),
                    registry);
            assertThatThrownBy(() ->
                            registry.create(
                                    AssembledNode.of("nest", "jiuwen.subWorkflow", Map.of("workflowId", "missing")),
                                    ctx))
                    .isInstanceOf(NodeExecutionException.class)
                    .satisfies(ex -> assertThat(((NodeExecutionException) ex).causeCode())
                            .isEqualTo(NodeCauseCode.SUBWORKFLOW_REF_INVALID));
        }
    }

    @Nested
    class SanitizeAndInteractiveInput {
        @Test
        void sanitizeMessageMasksSecretsLikePython() {
            assertThat(SanitizeMessage.sanitize("secret key: abc123 password: p@ss api key: KKK"))
                    .isEqualTo("secret key: *** password: *** api key: ***");
            assertThat(SanitizeMessage.sanitize("Access Token: tok-1")).isEqualTo("Access Token: ***");
            assertThat(SanitizeMessage.sanitize(null)).isEmpty();
        }

        @Test
        void interactiveInputResumeUnwrapsIntoChildUserFields() {
            AtomicReference<Map<String, Object>> bucket = new AtomicReference<>(new HashMap<>());
            bucket.get()
                    .put(
                            "flow_input_state",
                            Map.of("status", "user_interact", "question", "请输入姓名"));
            NodeSessionApi session = statefulSession(bucket);

            AssembledWorkflow child = new AssembledWorkflow(
                    "child_input",
                    List.of(
                            AssembledNode.of(
                                    "node_input",
                                    "jiuwen.input",
                                    Map.of(
                                            "userFields",
                                            Map.of(
                                                    "inputs",
                                                    List.of(Map.of(
                                                            "id", "name", "type", "string", "required", true))))),
                            AssembledNode.of("e", "jiuwen.end", Map.of())));
            ComponentExecutable nest = registry.create(
                    AssembledNode.of("nest", "jiuwen.subWorkflow", Map.of("workflowId", "child_input")),
                    ctxWithChild(child));

            InteractiveInput resume = new InteractiveInput("name: Kayla");
            Map<String, Object> done =
                    uf(nest.invoke(Map.of("userFields", Map.of(), "interactiveInput", resume), session, null));
            assertThat(done)
                    .containsEntry("name", "Kayla")
                    .containsEntry("nestedWorkflowState", "end")
                    .doesNotContainKey("inputReceived");
        }
    }

    @Nested
    class CoreSubWorkflowPath {
        @Test
        void useCoreSubWorkflowFlagRunsThroughSubWorkflowComponent() {
            AssembledWorkflow child = new AssembledWorkflow(
                    "child",
                    List.of(
                            AssembledNode.of(
                                    "sv",
                                    "jiuwen.setVariable",
                                    Map.of("variableMapping", Map.of("result", "via_core"))),
                            AssembledNode.of("e", "jiuwen.end", Map.of())));
            ComponentExecutable exec = registry.create(
                    AssembledNode.of(
                            "nest",
                            "jiuwen.subWorkflow",
                            Map.of("workflowId", "child", "useCoreSubWorkflow", true)),
                    ctxWithChild(child));
            assertThat(exec).isInstanceOf(NestedWorkflowNodeHandler.NestedExecutable.class);
            NestedWorkflowNodeHandler.NestedExecutable nested =
                    (NestedWorkflowNodeHandler.NestedExecutable) exec;
            assertThat(nested.coreComponent()).isNotNull();

            WorkflowSession wf =
                    new WorkflowSession("parent-wf", null, null, InMemoryState.create(), null);
            NodeSessionApi session = new NodeSessionApi(new NodeSession(wf, "nest"));
            Map<String, Object> fields =
                    uf(exec.invoke(Map.of("userFields", Map.of("query", "q")), session, null));
            assertThat(fields)
                    .containsEntry("result", "via_core")
                    .containsEntry("nestedWorkflowState", "end")
                    .containsEntry("__nestingDepth__", 1);
        }
    }
}
