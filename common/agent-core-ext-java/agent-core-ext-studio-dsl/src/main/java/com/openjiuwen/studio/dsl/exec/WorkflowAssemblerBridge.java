package com.openjiuwen.studio.dsl.exec;

import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.model.AssembledWorkflow;
import com.openjiuwen.studio.dsl.registry.NodeTypeRegistry;
import java.util.Map;

/**
 * @deprecated Prefer {@link WorkflowAssemblyBridge} (L2 naming). Kept for binary compatibility.
 */
@Deprecated
public final class WorkflowAssemblerBridge {
    private final WorkflowAssemblyBridge delegate;

    public WorkflowAssemblerBridge(NodeTypeRegistry registry) {
        this.delegate = new WorkflowAssemblyBridge(registry);
    }

    public Map<String, ComponentExecutable> toExecutables(AssembledWorkflow workflow, NodeBuildContext ctx) {
        return delegate.mapExecutables(workflow, ctx);
    }
}
