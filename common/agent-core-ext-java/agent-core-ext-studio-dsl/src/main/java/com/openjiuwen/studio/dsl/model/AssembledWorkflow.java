package com.openjiuwen.studio.dsl.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Assembled workflow graph consumed by WorkflowAssemblerBridge. */
public final class AssembledWorkflow {
    private final String workflowId;
    private final List<AssembledNode> nodes;

    public AssembledWorkflow(String workflowId, List<AssembledNode> nodes) {
        this.workflowId = Objects.requireNonNull(workflowId, "workflowId");
        this.nodes = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(nodes, "nodes")));
    }

    public String workflowId() {
        return workflowId;
    }

    public List<AssembledNode> nodes() {
        return nodes;
    }
}
