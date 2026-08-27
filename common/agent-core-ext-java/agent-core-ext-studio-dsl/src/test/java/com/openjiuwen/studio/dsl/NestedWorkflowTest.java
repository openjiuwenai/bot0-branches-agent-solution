/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.AssembledWorkflow;
import com.openjiuwen.studio.dsl.registry.NodeTypeRegistry;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * NestedWorkflowTest for Studio DSL node-type extension (FEAT-031).
 *
 * @since 2026-08-17
 */

class NestedWorkflowTest {
    @Test
    void nested_invokesChildAndReturnsUserFields() {
        NodeTypeRegistry registry = NodeTypeRegistry.createWithBuiltins();
        NodeBuildContext ctx = new NodeBuildContext(
                "wf",
                0,
                5,
                null,
                configs -> new AssembledWorkflow(
                        "child",
                        List.of(
                                AssembledNode.of("s", "jiuwen.start", Map.of()),
                                AssembledNode.of("e", "jiuwen.end", Map.of()))));
        ComponentExecutable exec =
                registry.create(AssembledNode.of("nest", "jiuwen.subWorkflow", Map.of("workflowId", "child")), ctx);
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) exec.invoke(
                Map.of("userFields", Map.of("q", "hello")), mock(NodeSessionApi.class), mock(ModelContext.class));
        assertThat(out).containsKey("userFields");
    }
}
