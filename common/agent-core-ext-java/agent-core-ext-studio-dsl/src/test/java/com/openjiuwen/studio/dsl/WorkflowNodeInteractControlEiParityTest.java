/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.adapter.control.SetVariableNodeHandler;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.registry.NodeTypeRegistry;
import com.openjiuwen.studio.dsl.store.ConversationValsStores;
import com.openjiuwen.studio.dsl.store.InMemoryConversationValsStore;
import com.openjiuwen.studio.dsl.testsupport.StudioEngineTestSupport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parity for interact/control nodes (message / input / end / setVariable / aggregate / exception)
 * and EI.* nodes (ParamOutput / qa / ComplexIntentDetection).
 *
 * @since 2026-08-25
 */

class WorkflowNodeInteractControlEiParityTest {
    private InMemoryConversationValsStore store;
    private NodeTypeRegistry registry;

    @BeforeEach
    void setUp() {
        store = new InMemoryConversationValsStore();
        ConversationValsStores.setDefault(store);
        registry = NodeTypeRegistry.createWithBuiltins();
    }

    @AfterEach
    void tearDown() {
        ConversationValsStores.setDefault(null);
        ConversationValsStores.memoryStore().clear();
    }

    @Test
    void setVariable_memoryPrefix_writesRedis() {
        NodeSessionApi session = mock(NodeSessionApi.class);
        when(session.getGlobalState("_session_var_defs")).thenReturn(Map.of("counter", Map.of("type", "number")));
        when(session.getSessionId()).thenReturn("c1");

        AssembledNode node =
                AssembledNode.of(
                        "sv",
                        "jiuwen.setVariable",
                        Map.of(
                                "settings",
                                List.of(Map.of(
                                        "left",
                                        Map.of("value", "MEMORY_VARIABLE.counter"),
                                        "right",
                                        Map.of("value", 7))),
                                "operatorMapping",
                                Map.of()));
        ComponentExecutable exec =
                new SetVariableNodeHandler().create(node, StudioEngineTestSupport.context("wf-sv"));
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>)
                exec.invoke(Map.of("userFields", Map.of("conversationId", "c1")), session, null);
        assertThat(out).isEmpty();
        Map<String, Object> redis = store.getMap("global.vals.wf-sv.c1");
        assertThat(redis).containsEntry("counter", 7);
    }

    @Test
    void setVariable_incrementOperator() {
        NodeBuildContext ctx = StudioEngineTestSupport.context("wf-inc");
        ctx.variableScope().putAll(Map.of("n", 3));
        AssembledNode node =
                AssembledNode.of(
                        "sv",
                        "jiuwen.setVariable",
                        Map.of(
                                "variableMapping",
                                Map.of("n", 0),
                                "operatorMapping",
                                Map.of("n", "increment")));
        ComponentExecutable exec = new SetVariableNodeHandler().create(node, ctx);
        @SuppressWarnings("unchecked")
        Map<String, Object> out =
                (Map<String, Object>) exec.invoke(Map.of("userFields", Map.of()), null, null);
        assertThat(out).isEmpty();
        assertThat(ctx.variableScope().get("n")).isEqualTo(4L);
    }

    @Test
    void message_prefersTemplate_andMustache() {
        ComponentExecutable exec = registry.create(
                AssembledNode.of("m", "jiuwen.message", Map.of("template", "hi {{name}}")),
                StudioEngineTestSupport.context("wf"));
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>)
                exec.invoke(Map.of("userFields", Map.of("name", "Ada")), mock(NodeSessionApi.class), null);
        assertThat(out.get("result")).isEqualTo("hi Ada");
        assertThat(out).doesNotContainKey("userFields");
    }

    @Test
    void input_firstPass_returnsEmptyAndParksState() {
        ComponentExecutable exec = registry.create(
                AssembledNode.of(
                        "i",
                        "jiuwen.input",
                        Map.of(
                                "userFields",
                                Map.of(
                                        "inputs",
                                        List.of(Map.of("id", "name", "required", true, "type", "string"))))),
                StudioEngineTestSupport.context("wf"));
        AtomicReference<Map<String, Object>> stateBucket = new AtomicReference<>(new HashMap<>());
        NodeSessionApi session = mock(NodeSessionApi.class);
        when(session.getState(any())).thenAnswer(inv -> {
            Object key = inv.getArgument(0);
            if (key == null) {
                return stateBucket.get();
            }
            return stateBucket.get().get(String.valueOf(key));
        });
        org.mockito.Mockito.doAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> patch = inv.getArgument(0);
                    Map<String, Object> cur = new HashMap<>(stateBucket.get());
                    cur.putAll(patch);
                    stateBucket.set(cur);
                    return null;
                })
                .when(session)
                .updateState(any());
        @SuppressWarnings("unchecked")
        Map<String, Object> out =
                (Map<String, Object>) exec.invoke(Map.of("userFields", Map.of()), session, null);
        assertThat(out).isEmpty();
        assertThat(stateBucket.get()).containsKey("flow_input_state");
    }

    @Test
    void input_resume_fillsFields() {
        AtomicReference<Map<String, Object>> stateBucket = new AtomicReference<>(new HashMap<>());
        NodeSessionApi session = mock(NodeSessionApi.class);
        when(session.getState(any())).thenAnswer(inv -> {
            Object key = inv.getArgument(0);
            if (key == null) {
                return stateBucket.get();
            }
            return stateBucket.get().get(String.valueOf(key));
        });
        org.mockito.Mockito.doAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> patch = inv.getArgument(0);
                    Map<String, Object> cur = new HashMap<>(stateBucket.get());
                    cur.putAll(patch);
                    stateBucket.set(cur);
                    return null;
                })
                .when(session)
                .updateState(any());

        ComponentExecutable exec = registry.create(
                AssembledNode.of(
                        "i",
                        "jiuwen.input",
                        Map.of(
                                "userFields",
                                Map.of(
                                        "inputs",
                                        List.of(Map.of("id", "name", "required", true, "type", "string"))))),
                StudioEngineTestSupport.context("wf"));
        assertThat(exec.invoke(Map.of("userFields", Map.of()), session, null)).isEqualTo(Map.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> out2 = (Map<String, Object>)
                exec.invoke(Map.of("userFields", Map.of("name", "Bob")), session, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> uf = (Map<String, Object>) out2.get("userFields");
        assertThat(uf.get("name")).isEqualTo("Bob");
    }

    @Test
    void end_mapsEndPrefix_andMarksTerminal() {
        ComponentExecutable exec =
                registry.create(AssembledNode.of("e", "jiuwen.end", Map.of()), StudioEngineTestSupport.context("wf"));
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>)
                exec.invoke(Map.of("userFields", Map.of("#end_answer", "done", "x", 1)), mock(NodeSessionApi.class), null);
        @SuppressWarnings("unchecked")
        Map<String, Object> uf = (Map<String, Object>) out.get("userFields");
        assertThat(uf.get("answer")).isEqualTo("done");
        assertThat(uf.get("__terminal__")).isEqualTo(true);
    }

    @Test
    void aggregate_skipsEmptyString() {
        ComponentExecutable exec = registry.create(
                AssembledNode.of(
                        "a",
                        "jiuwen.aggregate",
                        Map.of("groups", Map.of("out", List.of("a", "b")))),
                StudioEngineTestSupport.context("wf"));
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>)
                exec.invoke(Map.of("userFields", Map.of("a", "", "b", "ok")), null, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> uf = (Map<String, Object>) out.get("userFields");
        assertThat(uf.get("out")).isEqualTo("ok");
    }

    @Test
    void exception_abort_throws() {
        ComponentExecutable exec = registry.create(
                AssembledNode.of("ex", "jiuwen.exception", Map.of("handleType", "abort")),
                StudioEngineTestSupport.context("wf"));
        assertThatThrownBy(() -> exec.invoke(Map.of("userFields", Map.of("message", "boom")), null, null))
                .isInstanceOf(com.openjiuwen.studio.dsl.flowexception.WorkflowAbortException.class)
                .satisfies(ex -> assertThat(
                                ((com.openjiuwen.studio.dsl.flowexception.WorkflowAbortException) ex).data())
                        .containsEntry("message", "boom"));
    }

    @Test
    void ei_paramOutput_passthrough() {
        ComponentExecutable exec =
                registry.create(AssembledNode.of("p", "EI.ParamOutput", Map.of()), StudioEngineTestSupport.context("wf"));
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>)
                exec.invoke(Map.of("userFields", Map.of("a", 1), "systemFields", Map.of("t", 2)), null, null);
        assertThat(out.get("userFields")).isEqualTo(Map.of("a", 1));
        assertThat(out.get("systemFields")).isEqualTo(Map.of("t", 2));
    }

    @Test
    void ei_paramOutput_mapWithoutUserFields_yieldsEmpty() {
        ComponentExecutable exec =
                registry.create(AssembledNode.of("p", "EI.ParamOutput", Map.of()), StudioEngineTestSupport.context("wf"));
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>)
                exec.invoke(Map.of("a", 1, "systemFields", Map.of("t", 2)), null, null);
        assertThat(out.get("userFields")).isEqualTo(Map.of());
        assertThat(out.get("systemFields")).isEqualTo(Map.of("t", 2));
    }

    @Test
    void ei_paramOutput_nonMapInput_wrapsAsUserFields() {
        ComponentExecutable exec =
                registry.create(AssembledNode.of("p", "EI.ParamOutput", Map.of()), StudioEngineTestSupport.context("wf"));
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) exec.invoke("hello", null, null);
        assertThat(out.get("userFields")).isEqualTo("hello");
        assertThat(out).doesNotContainKey("systemFields");
    }

    @Test
    void ei_qa_index_noReply() {
        ComponentExecutable exec = registry.create(
                AssembledNode.of(
                        "q",
                        "EI.qa",
                        Map.of(
                                "needReply",
                                false,
                                "qaStrategy",
                                "index",
                                "options",
                                List.of("A", "B", "C"),
                                "index_key",
                                "index")),
                StudioEngineTestSupport.context("wf"));
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>)
                exec.invoke(Map.of("userFields", Map.of("index", 1)), mock(NodeSessionApi.class), null);
        @SuppressWarnings("unchecked")
        Map<String, Object> uf = (Map<String, Object>) out.get("userFields");
        assertThat(uf.get("response")).isEqualTo("B");
    }

    @Test
    void ei_qa_needReply_hangs() {
        ComponentExecutable exec = registry.create(
                AssembledNode.of(
                        "q",
                        "EI.qa",
                        Map.of("needReply", true, "options", List.of("hello"), "qaStrategy", "index")),
                StudioEngineTestSupport.context("wf"));
        NodeSessionApi session = mock(NodeSessionApi.class);
        when(session.getState(any())).thenReturn(null);
        @SuppressWarnings("unchecked")
        Map<String, Object> out =
                (Map<String, Object>) exec.invoke(Map.of("userFields", Map.of()), session, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> uf = (Map<String, Object>) out.get("userFields");
        assertThat(uf.get("hangState")).isEqualTo("INPUT_REQUIRED");
    }

    @Test
    void ei_complexIntent_matchesViaIntentStub() {
        StudioEngineTestSupport.installComplexIntent(
                new com.openjiuwen.studio.dsl.complexintent.ComplexIntentDetectionEngine.TestBridge() {
                    @Override
                    public Map<String, Object> intentResult(Map<String, Object> convertedInputs) {
        return Map.of("classificationId", 1, "name", "refund", "result", "分类1");
    }
                    @Override
                    public Map<String, Object> subWorkflowResult(String workflowId, Map<String, Object> subInputs) {
                        return Map.of();
                    }
                });
        try {
            ComponentExecutable exec = registry.create(
                    AssembledNode.of(
                            "c",
                            "EI.ComplexIntentDetection",
                            Map.of(
                                    "intentDetectionContainerNodeId",
                                    "box",
                                    "branches",
                                    List.of(
                                            Map.of("id", "branch_0", "catalog", "其他"),
                                            Map.of("id", "branch_1", "catalog", "refund")),
                                    "groups",
                                    Map.of("reply", List.of("${branch_1.userFields.answer}")))),
                    StudioEngineTestSupport.context("wf"));
            @SuppressWarnings("unchecked")
            Map<String, Object> out = (Map<String, Object>)
                    exec.invoke(Map.of("userFields", Map.of("input", "I want a refund please")), null, null);
            @SuppressWarnings("unchecked")
            Map<String, Object> uf = (Map<String, Object>) out.get("userFields");
            assertThat(uf.get("classificationId")).isEqualTo(1);
            assertThat(uf.get("name")).isEqualTo("refund");
        } finally {
            StudioEngineTestSupport.clear();
        }
    }
}
