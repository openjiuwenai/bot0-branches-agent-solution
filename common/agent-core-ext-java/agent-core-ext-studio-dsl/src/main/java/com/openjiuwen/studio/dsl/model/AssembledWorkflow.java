/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Assembled workflow graph consumed by {@link com.openjiuwen.studio.dsl.exec.WorkflowAssemblyBridge}.
 *
 * @since 2026-08-17
 */

public final class AssembledWorkflow {
    private final String workflowId;
    private final List<AssembledNode> nodes;

    /**
     * AssembledWorkflow.
     *
     * @param workflowId workflowId
     * @param nodes nodes
     */

    public AssembledWorkflow(String workflowId, List<AssembledNode> nodes) {
        this.workflowId = Objects.requireNonNull(workflowId, "workflowId");
        this.nodes = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(nodes, "nodes")));
    }

    /**
     * workflowId.
     *
     * @return result
     */

    public String workflowId() {
        return workflowId;
    }

    /**
     * nodes.
     *
     * @return result
     */

    public List<AssembledNode> nodes() {
        return nodes;
    }
}
