/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.adapter.StudioStreamFrames;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.AssembledWorkflow;
import com.openjiuwen.studio.dsl.registry.NodeTypeRegistry;
import com.openjiuwen.studio.dsl.testsupport.LinearWorkflowTestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Java port of {@code test_start_end_components.py} — End template/stream/struct,
 * setVariable+message linear chain, nested memory via {@code NestedWorkflowNodeHandler}.
 *
 * @since 2026-08-26
 */

class WorkflowNodeStartEndCasesTest {
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

    @Test
    void test_end_stream_output() {
        ComponentExecutable end = registry.create(
                AssembledNode.of(
                        "end",
                        "jiuwen.end",
                        Map.of("responseTemplate", "Hello {{name}}!", "stream_output", true)),
                NodeBuildContext.defaults("wf_end_stream"));
        Iterator<Object> frames =
                end.stream(Map.of("userFields", Map.of("name", "World")), mock(NodeSessionApi.class), null);
        List<Object> collected = new ArrayList<>();
        frames.forEachRemaining(collected::add);
        assertThat(collected).isNotEmpty();
        boolean hasWorkflowEnd = collected.stream().anyMatch(f -> {
            if (!(f instanceof Map<?, ?> m)) {
                return false;
            }
            return StudioStreamFrames.WORKFLOW_END.equals(String.valueOf(m.get("type")));
        });
        assertThat(hasWorkflowEnd).isTrue();
        boolean hasAnswer = collected.stream().anyMatch(f -> {
            if (!(f instanceof Map<?, ?> m)) {
                return false;
            }
            Object data = m.get("data");
            return data instanceof Map<?, ?> d && String.valueOf(d.get("answer")).contains("Hello World");
        });
        assertThat(hasAnswer).isTrue();
    }

    @Test
    void test_end_structured_output() {
        ComponentExecutable end = registry.create(
                AssembledNode.of(
                        "end",
                        "jiuwen.end",
                        Map.of(
                                "responseTemplate",
                                "Hello {{name}}!",
                                "enable_struct_message",
                                true,
                                "struct_output_template",
                                "{\"greeting\": \"{{_NODE_OUTPUT}}\", \"user\": \"{{name}}\"}")),
                NodeBuildContext.defaults("wf_end_struct"));
        Map<String, Object> fields =
                uf(end.invoke(Map.of("userFields", Map.of("name", "Alice")), mock(NodeSessionApi.class), null));
        assertThat(fields.get("__terminal__")).isEqualTo(true);
        assertThat(String.valueOf(fields.get("origin_answer"))).contains("Hello Alice");
        assertThat(String.valueOf(fields.get("struct_answer")))
                .contains("Alice")
                .contains("Hello Alice");
        assertThat(String.valueOf(fields.get("answer"))).contains("Alice");
    }

    @Test
    void test_set_variable_then_message_linear() {
        AssembledWorkflow wf = new AssembledWorkflow(
                "wf_sv_msg",
                List.of(
                        AssembledNode.of("start", "jiuwen.start", Map.of()),
                        AssembledNode.of(
                                "sv",
                                "jiuwen.setVariable",
                                Map.of("variableMapping", Map.of("test_mem", "aaa"))),
                        AssembledNode.of("msg", "jiuwen.message", Map.of("template", "mem={{test_mem}}")),
                        AssembledNode.of(
                                "end",
                                "jiuwen.end",
                                Map.of("responseTemplate", "{{result}}"))));
        Map<String, Object> out = LinearWorkflowTestSupport.executeLinear(
                registry,
                wf,
                NodeBuildContext.defaults("wf_sv_msg"),
                Map.of("userFields", Map.of("query", "你好")),
                mock(NodeSessionApi.class),
                mock(ModelContext.class));
        Map<String, Object> fields = uf(out);
        assertThat(fields).containsEntry("test_mem", "aaa");
        assertThat(String.valueOf(fields.get("result"))).contains("mem=aaa");
        assertThat(fields.get("__terminal__")).isEqualTo(true);
    }

    @Test
    void test_sub_workflow_standalone_via_nested_handler() {
        AssembledWorkflow child = new AssembledWorkflow(
                "sub_flow",
                List.of(
                        AssembledNode.of("sub_start", "jiuwen.start", Map.of()),
                        AssembledNode.of(
                                "variable_modify",
                                "jiuwen.setVariable",
                                Map.of("variableMapping", Map.of("sub_mem", "ccc2"))),
                        AssembledNode.of(
                                "sub_end",
                                "jiuwen.end",
                                Map.of("responseTemplate", "{{sub_mem}}"))));
        NodeBuildContext ctx = new NodeBuildContext(
                "parent_wf", 0, 5, null, configs -> child);
        ComponentExecutable nest = registry.create(
                AssembledNode.of("workflowComposite", "jiuwen.subWorkflow", Map.of("workflowId", "sub_flow")),
                ctx);
        Map<String, Object> fields = uf(nest.invoke(
                Map.of("userFields", Map.of("input", "123")),
                mock(NodeSessionApi.class),
                mock(ModelContext.class)));
        assertThat(fields).containsEntry("sub_mem", "ccc2");
        assertThat(String.valueOf(fields.get("answer"))).contains("ccc2");
        assertThat(fields.get("__terminal__")).isEqualTo(true);
    }

    @Test
    void test_parent_child_workflow_variable_propagation() {
        AssembledWorkflow child = new AssembledWorkflow(
                "sub_flow",
                List.of(
                        AssembledNode.of("sub_start", "jiuwen.start", Map.of()),
                        AssembledNode.of(
                                "variable_modify",
                                "jiuwen.setVariable",
                                Map.of("variableMapping", Map.of("sub_mem", "ccc2"))),
                        AssembledNode.of(
                                "sub_end",
                                "jiuwen.end",
                                Map.of("responseTemplate", "{{sub_mem}}"))));
        AssembledWorkflow parent = new AssembledWorkflow(
                "parent_wf",
                List.of(
                        AssembledNode.of("start", "jiuwen.start", Map.of()),
                        AssembledNode.of(
                                "set_variable",
                                "jiuwen.setVariable",
                                Map.of("variableMapping", Map.of("test_mem", "parent_val"))),
                        AssembledNode.of("message", "jiuwen.message", Map.of("template", "parent={{test_mem}}")),
                        AssembledNode.of(
                                "workflowComposite", "jiuwen.subWorkflow", Map.of("workflowId", "sub_flow")),
                        AssembledNode.of(
                                "end",
                                "jiuwen.end",
                                Map.of("responseTemplate", "parent={{test_mem}} sub={{sub_mem}}"))));
        NodeBuildContext ctx = new NodeBuildContext(
                "parent_wf", 0, 5, null, configs -> child);
        Map<String, Object> out = LinearWorkflowTestSupport.executeLinear(
                registry,
                parent,
                ctx,
                Map.of("userFields", Map.of("query", "你好")),
                mock(NodeSessionApi.class),
                mock(ModelContext.class));
        Map<String, Object> fields = uf(out);
        assertThat(fields).containsEntry("test_mem", "parent_val");
        assertThat(fields).containsEntry("sub_mem", "ccc2");
        assertThat(String.valueOf(fields.get("answer"))).contains("parent_val").contains("ccc2");
        assertThat(fields.get("__terminal__")).isEqualTo(true);
    }
}
