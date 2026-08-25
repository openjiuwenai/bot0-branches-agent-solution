/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.exec;

import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.model.AssembledWorkflow;
import com.openjiuwen.studio.dsl.registry.NodeTypeRegistry;

import java.util.Map;

/**
 * Deprecated alias of {@link WorkflowAssemblyBridge}.
 *
 * @deprecated Prefer {@link WorkflowAssemblyBridge} (L2 naming). Kept for binary compatibility.
 *
 * @since 2026-08-17
 */
@Deprecated
public final class WorkflowAssemblerBridge {
    private final WorkflowAssemblyBridge delegate;

    /**
     * WorkflowAssemblerBridge.
     *
     * @param registry registry
     */
    public WorkflowAssemblerBridge(NodeTypeRegistry registry) {
        this.delegate = new WorkflowAssemblyBridge(registry);
    }

    /**
     * toExecutables.
     *
     * @param workflow workflow
     * @param ctx ctx
     * @return result
     */
    public Map<String, ComponentExecutable> toExecutables(AssembledWorkflow workflow, NodeBuildContext ctx) {
        return delegate.mapExecutables(workflow, ctx);
    }
}
