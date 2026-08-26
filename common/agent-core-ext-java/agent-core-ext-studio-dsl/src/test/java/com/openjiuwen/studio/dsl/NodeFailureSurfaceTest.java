/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.adapter.AbstractStudioNode;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.NodeCauseCode;
import com.openjiuwen.studio.dsl.model.NodePayload;
import com.openjiuwen.studio.dsl.registry.NodeTypeRegistry;

import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * L2 §7.3 negative surfaces that were previously only implemented, not asserted.
 *
 * @since 2026-08-17
 */
class NodeFailureSurfaceTest {
    @Test
    void subWorkflow_nullResolverResult_isRefInvalidAtCreate() {
        NodeTypeRegistry registry = NodeTypeRegistry.createWithBuiltins();
        NodeBuildContext ctx = new NodeBuildContext("wf", 0, 5, null, configs -> null);
        assertThatThrownBy(() -> registry.create(
                        AssembledNode.of("nest", "jiuwen.subWorkflow", Map.of("workflowId", "missing")), ctx))
                .isInstanceOf(NodeExecutionException.class)
                .satisfies(e -> {
                    if (!(e instanceof NodeExecutionException ne)) {
                        throw new AssertionError("expected NodeExecutionException");
                    }
                    assertThat(ne.causeCode()).isEqualTo(NodeCauseCode.SUBWORKFLOW_REF_INVALID);
                    assertThat(ne.nodeId()).isEqualTo("nest");
                    assertThat(ne.nodeType()).isEqualTo("jiuwen.subWorkflow");
                });
    }

    @Test
    void subWorkflow_resolverThrows_isRefInvalidAtCreate() {
        NodeTypeRegistry registry = NodeTypeRegistry.createWithBuiltins();
        NodeBuildContext ctx = new NodeBuildContext(
                "wf",
                0,
                5,
                null,
                configs -> {
                    throw new IllegalArgumentException("bad ref");
                });
        assertThatThrownBy(() ->
                        registry.create(AssembledNode.of("nest", "jiuwen.subWorkflow", Map.of("workflowId", "x")), ctx))
                .isInstanceOf(NodeExecutionException.class)
                .extracting(e -> e instanceof NodeExecutionException ne ? ne.causeCode() : null)
                .isEqualTo(NodeCauseCode.SUBWORKFLOW_REF_INVALID);
    }

    @Test
    void unexpectedInvokeException_wrapsAsNodeInvokeFailed() {
        AbstractStudioNode exec = new AbstractStudioNode(AssembledNode.of("b1", "demo.boom", Map.of())) {
            @Override
            protected NodePayload doInvoke(
                    Map<String, Object> inputs, NodeSessionApi session, ModelContext context) {
                throw new IllegalStateException("unexpected");
            }
        };
        assertThatThrownBy(() -> exec.invoke(
                Map.of("userFields", Map.of()), mock(NodeSessionApi.class), mock(ModelContext.class)))
                .isInstanceOf(NodeExecutionException.class)
                .satisfies(e -> {
                    if (!(e instanceof NodeExecutionException ne)) {
                        throw new AssertionError("expected NodeExecutionException");
                    }
                    assertThat(ne.causeCode()).isEqualTo(NodeCauseCode.NODE_INVOKE_FAILED);
                    assertThat(ne.nodeId()).isEqualTo("b1");
                    assertThat(ne.nodeType()).isEqualTo("demo.boom");
                });
    }
}
