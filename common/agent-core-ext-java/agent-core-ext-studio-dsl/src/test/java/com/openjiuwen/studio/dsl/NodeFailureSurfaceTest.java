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
import com.openjiuwen.studio.dsl.registry.CodeLogicRegistry;
import com.openjiuwen.studio.dsl.registry.NodeTypeRegistry;
import com.openjiuwen.studio.dsl.spi.NodeHandlerFactory;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** L2 §7.3 negative surfaces that were previously only implemented, not asserted. */
class NodeFailureSurfaceTest {

    @Test
    void subWorkflow_nullResolverResult_isRefInvalidAtCreate() {
        NodeTypeRegistry registry = NodeTypeRegistry.createWithBuiltins();
        NodeBuildContext ctx = new NodeBuildContext("wf", 0, 5, null, new CodeLogicRegistry(), configs -> null);
        assertThatThrownBy(() -> registry.create(
                        AssembledNode.of("nest", "jiuwen.subWorkflow", Map.of("workflowId", "missing")), ctx))
                .isInstanceOf(NodeExecutionException.class)
                .satisfies(e -> {
                    NodeExecutionException ne = (NodeExecutionException) e;
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
                new CodeLogicRegistry(),
                configs -> {
                    throw new IllegalArgumentException("bad ref");
                });
        assertThatThrownBy(() ->
                        registry.create(AssembledNode.of("nest", "jiuwen.subWorkflow", Map.of("workflowId", "x")), ctx))
                .isInstanceOf(NodeExecutionException.class)
                .extracting(e -> ((NodeExecutionException) e).causeCode())
                .isEqualTo(NodeCauseCode.SUBWORKFLOW_REF_INVALID);
    }

    @Test
    void unexpectedInvokeException_wrapsAsNodeInvokeFailed() {
        NodeTypeRegistry registry = NodeTypeRegistry.createWithBuiltins();
        registry.register(new NodeHandlerFactory() {
            @Override
            public String canonicalType() {
                return "demo.boom";
            }

            @Override
            public Set<String> aliases() {
                return Set.of();
            }

            @Override
            public ComponentExecutable create(AssembledNode node, NodeBuildContext ctx) {
                return new AbstractStudioNode(node) {
                    @Override
                    protected NodePayload doInvoke(
                            Map<String, Object> inputs, NodeSessionApi session, ModelContext context) {
                        throw new IllegalStateException("unexpected");
                    }
                };
            }
        });
        ComponentExecutable exec =
                registry.create(AssembledNode.of("b1", "demo.boom", Map.of()), NodeBuildContext.defaults("wf"));
        assertThatThrownBy(() -> exec.invoke(Map.of("userFields", Map.of()), mock(NodeSessionApi.class), mock(ModelContext.class)))
                .isInstanceOf(NodeExecutionException.class)
                .satisfies(e -> {
                    NodeExecutionException ne = (NodeExecutionException) e;
                    assertThat(ne.causeCode()).isEqualTo(NodeCauseCode.NODE_INVOKE_FAILED);
                    assertThat(ne.nodeId()).isEqualTo("b1");
                    assertThat(ne.nodeType()).isEqualTo("demo.boom");
                });
    }
}
