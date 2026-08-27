/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.registry.NodeTypeRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Java port of {@code test_loop_component.py} — node-level LoopNodeHandler + loopBody.
 *
 * @since 2026-08-25
 */

class WorkflowNodeLoopCasesTest {
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

    private static Map<String, Object> bodyMsgThenIncrement() {
        return Map.of(
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
                                        Map.of("total", "increment")))),
                "output_schema",
                Map.of("text", "result", "total", "total"));
    }

    @Test
    void numberLoop_collectsRoundsAndTotals() {
        Map<String, Object> cfg = new java.util.LinkedHashMap<>(bodyMsgThenIncrement());
        cfg.put("loop_type", "number");
        cfg.put("loop_number", 3);
        cfg.put("intermediate_var", Map.of("total", 0));

        ComponentExecutable exec =
                registry.create(AssembledNode.of("loop_node", "jiuwen.loop", cfg), NodeBuildContext.defaults("wf"));
        Map<String, Object> fields = uf(exec.invoke(Map.of("userFields", Map.of()), mock(NodeSessionApi.class), null));
        assertThat(fields.get("loopCount")).isEqualTo(3);
        assertThat(fields.get("text")).isEqualTo(List.of("round_0", "round_1", "round_2"));
        assertThat(fields.get("total")).isEqualTo(List.of(1L, 2L, 3L));
    }

    @Test
    void arrayLoop_processesEachItem() {
        ComponentExecutable exec = registry.create(
                AssembledNode.of(
                        "loop_node",
                        "jiuwen.loop",
                        Map.of(
                                "loop_type",
                                "array",
                                "loop_array",
                                Map.of("item", List.of("Beijing", "Shanghai", "Guangzhou")),
                                "loopBody",
                                List.of(Map.of(
                                        "id",
                                        "body",
                                        "type",
                                        "jiuwen.message",
                                        "configs",
                                        Map.of("template", "hello_{{item}}"))),
                                "output_schema",
                                Map.of("text", "result"))),
                NodeBuildContext.defaults("wf"));
        Map<String, Object> fields = uf(exec.invoke(Map.of("userFields", Map.of()), mock(NodeSessionApi.class), null));
        assertThat(fields.get("text"))
                .isEqualTo(List.of("hello_Beijing", "hello_Shanghai", "hello_Guangzhou"));
        assertThat(fields.get("loopCount")).isEqualTo(3);
    }

    @Test
    void loopBreak_stopsBeforeMaxIterations() {
        Map<String, Object> cfg = new java.util.LinkedHashMap<>(bodyMsgThenIncrement());
        cfg.put("loop_number", 10);
        cfg.put("intermediate_var", Map.of("total", 0));
        cfg.put("breakCondition", Map.of("variable", "total", "operator", "gte", "value", 3));

        ComponentExecutable exec =
                registry.create(AssembledNode.of("loop_node", "jiuwen.loop", cfg), NodeBuildContext.defaults("wf"));
        Map<String, Object> fields = uf(exec.invoke(Map.of("userFields", Map.of()), mock(NodeSessionApi.class), null));
        assertThat(fields.get("text")).isEqualTo(List.of("round_0", "round_1", "round_2"));
        assertThat(fields.get("loopCount")).isEqualTo(3);
    }

    @Test
    void intermediateVar_seedsCrossIterationState() {
        Map<String, Object> cfg = new java.util.LinkedHashMap<>(bodyMsgThenIncrement());
        cfg.put("loop_number", 3);
        cfg.put("intermediate_var", Map.of("total", 10));

        ComponentExecutable exec =
                registry.create(AssembledNode.of("loop_node", "jiuwen.loop", cfg), NodeBuildContext.defaults("wf"));
        Map<String, Object> fields = uf(exec.invoke(Map.of("userFields", Map.of()), mock(NodeSessionApi.class), null));
        assertThat(fields.get("text")).isEqualTo(List.of("round_10", "round_11", "round_12"));
        assertThat(fields.get("total")).isEqualTo(List.of(11L, 12L, 13L));
    }

    @Test
    void outputCollection_listsPerSchemaKey() {
        Map<String, Object> cfg = new java.util.LinkedHashMap<>(bodyMsgThenIncrement());
        cfg.put("maxIterations", 3);
        cfg.put("intermediate_var", Map.of("total", 0));

        ComponentExecutable exec =
                registry.create(AssembledNode.of("loop_node", "jiuwen.loop", cfg), NodeBuildContext.defaults("wf"));
        Map<String, Object> fields = uf(exec.invoke(Map.of("userFields", Map.of()), mock(NodeSessionApi.class), null));
        assertThat(fields.get("text")).isEqualTo(List.of("round_0", "round_1", "round_2"));
        assertThat(fields.get("total")).isEqualTo(List.of(1L, 2L, 3L));
    }

    @Test
    void requestPrefix_writesGlobalState() {
        // Python test_set_variable_with_request_prefix — ${_request.xxx} → global_state["_REQUEST"]
        Map<String, Object> cfg = new java.util.LinkedHashMap<>();
        cfg.put("loop_type", "number");
        cfg.put("loop_number", 2);
        cfg.put("intermediate_var", Map.of("total", 0));
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
                                        Map.of("total", "increment"))),
                        Map.of(
                                "id",
                                "req",
                                "type",
                                "jiuwen.setVariable",
                                "configs",
                                Map.of("variableMapping", Map.of("${_request.global_counter}", "${total}")))));
        cfg.put("output_schema", Map.of("text", "result", "total", "total"));

        com.openjiuwen.core.session.state.WorkflowCommitState state =
                com.openjiuwen.core.session.state.InMemoryState.create();
        com.openjiuwen.core.session.internal.WorkflowSession wf =
                new com.openjiuwen.core.session.internal.WorkflowSession("wf", null, null, state, null);
        NodeSessionApi session =
                new NodeSessionApi(new com.openjiuwen.core.session.internal.NodeSession(wf, "loop_node"));

        ComponentExecutable exec =
                registry.create(AssembledNode.of("loop_node", "jiuwen.loop", cfg), NodeBuildContext.defaults("wf"));
        Map<String, Object> fields = uf(exec.invoke(Map.of("userFields", Map.of()), session, null));
        assertThat(fields.get("text")).isEqualTo(List.of("round_0", "round_1"));
        Object req = session.getGlobalState("_REQUEST");
        if (req == null) {
            req = session.getGlobalState("_request");
        }
        assertThat(req).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> reqMap = (Map<String, Object>) req;
        assertThat(reqMap.get("global_counter")).isNotNull();
    }

    @Test
    void explicitConnections_buildLoopGroupGraph() {
        // Same topology as Python LoopGroup: start/end/connections
        Map<String, Object> cfg = new java.util.LinkedHashMap<>();
        cfg.put("loop_number", 2);
        cfg.put("intermediate_var", Map.of("total", 0));
        cfg.put("startNodes", List.of("msg"));
        cfg.put("endNodes", List.of("upd"));
        cfg.put("connections", List.of(Map.of("from", "msg", "to", "upd")));
        cfg.put(
                "loopBody",
                List.of(
                        Map.of(
                                "id",
                                "msg",
                                "type",
                                "jiuwen.message",
                                "configs",
                                Map.of("template", "n{{total}}")),
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
        cfg.put("output_schema", Map.of("text", "result", "total", "total"));

        ComponentExecutable exec =
                registry.create(AssembledNode.of("loop_node", "jiuwen.loop", cfg), NodeBuildContext.defaults("wf"));
        Map<String, Object> fields = uf(exec.invoke(Map.of("userFields", Map.of()), mock(NodeSessionApi.class), null));
        assertThat(fields.get("loopCount")).isEqualTo(2);
        assertThat(fields.get("text")).isEqualTo(List.of("n0", "n1"));
        assertThat(fields.get("total")).isEqualTo(List.of(1L, 2L));
    }
}
