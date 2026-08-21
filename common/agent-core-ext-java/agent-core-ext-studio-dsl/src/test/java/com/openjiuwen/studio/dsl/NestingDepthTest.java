package com.openjiuwen.studio.dsl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.AssembledWorkflow;
import com.openjiuwen.studio.dsl.model.NodeCauseCode;
import com.openjiuwen.studio.dsl.registry.CodeLogicRegistry;
import com.openjiuwen.studio.dsl.registry.NodeTypeRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NestingDepthTest {

    @Test
    void nestingBeyondMax_rejectedAtCreate() {
        NodeTypeRegistry registry = NodeTypeRegistry.createWithBuiltins();
        NodeBuildContext ctx = new NodeBuildContext(
                "wf",
                5,
                5,
                null,
                new CodeLogicRegistry(),
                configs -> new AssembledWorkflow("child", List.of(AssembledNode.of("s", "jiuwen.start", Map.of()))));

        assertThatThrownBy(() -> registry.create(
                        AssembledNode.of("nest", "jiuwen.subWorkflow", Map.of("workflowId", "x")), ctx))
                .isInstanceOf(NodeExecutionException.class)
                .satisfies(ex -> {
                    NodeExecutionException ne = (NodeExecutionException) ex;
                    assert ne.causeCode() == NodeCauseCode.NESTING_DEPTH_EXCEEDED;
                    assert ne.nodeId().equals("nest");
                });
    }
}
