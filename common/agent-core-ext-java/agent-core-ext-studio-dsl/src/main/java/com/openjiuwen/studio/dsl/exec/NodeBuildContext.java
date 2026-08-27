/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.exec;

import com.openjiuwen.studio.dsl.config.StudioDslNodeProperties;
import com.openjiuwen.studio.dsl.contract.EmptyToolRegistry;
import com.openjiuwen.studio.dsl.contract.PythonCodeExecutor;
import com.openjiuwen.studio.dsl.contract.SubWorkflowResolver;
import com.openjiuwen.studio.dsl.contract.ToolRegistry;
import com.openjiuwen.studio.dsl.registry.NodeTypeRegistry;

/**
 * NodeBuildContext for Studio DSL node-type extension (FEAT-031).
 *
 * @since 2026-08-17
 */

public final class NodeBuildContext {
    private final String workflowId;
    private final int nestingDepth;
    private final int maxNestingDepth;
    private final PythonCodeExecutor pythonExecutor;
    private final SubWorkflowResolver subWorkflowResolver;
    private final ToolRegistry toolRegistry;
    private final NodeTypeRegistry nodeTypeRegistry;
    private final WorkflowVariableScope variableScope;
    private final String tenantId;
    private final StudioDslNodeProperties properties;
    private final StudioEngineTestOverrides testOverrides;

    /**
     * NodeBuildContext.
     *
     * @param workflowId workflowId
     * @param nestingDepth nestingDepth
     * @param maxNestingDepth maxNestingDepth
     * @param pythonExecutor pythonExecutor
     * @param subWorkflowResolver subWorkflowResolver
     */

    public NodeBuildContext(
            String workflowId,
            int nestingDepth,
            int maxNestingDepth,
            PythonCodeExecutor pythonExecutor,
            SubWorkflowResolver subWorkflowResolver) {
        this(
                workflowId,
                nestingDepth,
                maxNestingDepth,
                pythonExecutor,
                subWorkflowResolver,
                EmptyToolRegistry.INSTANCE,
                null,
                new WorkflowVariableScope(),
                null,
                new StudioDslNodeProperties(),
                null);
    }

    /**
     * NodeBuildContext.
     *
     * @param workflowId workflowId
     * @param nestingDepth nestingDepth
     * @param maxNestingDepth maxNestingDepth
     * @param pythonExecutor pythonExecutor
     * @param subWorkflowResolver subWorkflowResolver
     * @param toolRegistry toolRegistry
     * @param nodeTypeRegistry nodeTypeRegistry
     */

    public NodeBuildContext(
            String workflowId,
            int nestingDepth,
            int maxNestingDepth,
            PythonCodeExecutor pythonExecutor,
            SubWorkflowResolver subWorkflowResolver,
            ToolRegistry toolRegistry,
            NodeTypeRegistry nodeTypeRegistry) {
        this(
                workflowId,
                nestingDepth,
                maxNestingDepth,
                pythonExecutor,
                subWorkflowResolver,
                toolRegistry,
                nodeTypeRegistry,
                new WorkflowVariableScope(),
                null,
                new StudioDslNodeProperties(),
                null);
    }

    /**
     * NodeBuildContext.
     *
     * @param workflowId workflowId
     * @param nestingDepth nestingDepth
     * @param maxNestingDepth maxNestingDepth
     * @param pythonExecutor pythonExecutor
     * @param subWorkflowResolver subWorkflowResolver
     * @param toolRegistry toolRegistry
     * @param nodeTypeRegistry nodeTypeRegistry
     * @param variableScope variableScope
     * @param tenantId tenantId
     * @param properties properties
     * @param testOverrides test-only stubs (null in production)
     */

    public NodeBuildContext(
            String workflowId,
            int nestingDepth,
            int maxNestingDepth,
            PythonCodeExecutor pythonExecutor,
            SubWorkflowResolver subWorkflowResolver,
            ToolRegistry toolRegistry,
            NodeTypeRegistry nodeTypeRegistry,
            WorkflowVariableScope variableScope,
            String tenantId,
            StudioDslNodeProperties properties,
            StudioEngineTestOverrides testOverrides) {
        this.workflowId = workflowId;
        this.nestingDepth = nestingDepth;
        this.maxNestingDepth = maxNestingDepth;
        this.pythonExecutor = pythonExecutor;
        this.subWorkflowResolver = subWorkflowResolver;
        this.toolRegistry = toolRegistry == null ? EmptyToolRegistry.INSTANCE : toolRegistry;
        this.nodeTypeRegistry = nodeTypeRegistry;
        this.variableScope = variableScope == null ? new WorkflowVariableScope() : variableScope;
        this.tenantId = tenantId;
        this.properties = properties == null ? new StudioDslNodeProperties() : properties;
        this.testOverrides = testOverrides;
    }

    /**
     * defaults.
     *
     * @param workflowId workflowId
     * @return result
     */

    public static NodeBuildContext defaults(String workflowId) {
        return defaults(workflowId, new StudioDslNodeProperties());
    }

    /**
     * defaults.
     *
     * @param workflowId workflowId
     * @param props props
     * @return result
     */

    public static NodeBuildContext defaults(String workflowId, StudioDslNodeProperties props) {
        StudioDslNodeProperties p = props == null ? new StudioDslNodeProperties() : props;
        return new NodeBuildContext(
                workflowId,
                0,
                p.getMaxNestingDepth(),
                null,
                configs -> {
                    throw new NodeExecutionException(
                            "n/a",
                            "jiuwen.subWorkflow",
                            com.openjiuwen.studio.dsl.model.NodeCauseCode.SUBWORKFLOW_REF_INVALID,
                            "no SubWorkflowResolver configured");
                },
                EmptyToolRegistry.INSTANCE,
                null,
                new WorkflowVariableScope(),
                null,
                p,
                null);
    }

    /**
     * defaults.
     *
     * @param workflowId workflowId
     * @param tenantId tenantId
     * @param props props
     * @return result
     */

    public static NodeBuildContext defaults(String workflowId, String tenantId, StudioDslNodeProperties props) {
        StudioDslNodeProperties p = props == null ? new StudioDslNodeProperties() : props;
        return new NodeBuildContext(
                workflowId,
                0,
                p.getMaxNestingDepth(),
                null,
                configs -> {
                    throw new NodeExecutionException(
                            "n/a",
                            "jiuwen.subWorkflow",
                            com.openjiuwen.studio.dsl.model.NodeCauseCode.SUBWORKFLOW_REF_INVALID,
                            "no SubWorkflowResolver configured");
                },
                EmptyToolRegistry.INSTANCE,
                null,
                new WorkflowVariableScope(),
                tenantId,
                p,
                null);
    }

    /**
     * Bind registry after builtins registered (for loop body create).
     *
     * @param registry registry
     * @return result
     */

    public NodeBuildContext withRegistry(NodeTypeRegistry registry) {
        return new NodeBuildContext(
                workflowId,
                nestingDepth,
                maxNestingDepth,
                pythonExecutor,
                subWorkflowResolver,
                toolRegistry,
                registry,
                variableScope,
                tenantId,
                properties,
                testOverrides);
    }

    /**
     * Attach test stubs for handler integration tests (production never calls this).
     *
     * @param overrides overrides
     * @return copy with test wiring
     */

    public NodeBuildContext withTestOverrides(StudioEngineTestOverrides overrides) {
        return new NodeBuildContext(
                workflowId,
                nestingDepth,
                maxNestingDepth,
                pythonExecutor,
                subWorkflowResolver,
                toolRegistry,
                nodeTypeRegistry,
                variableScope,
                tenantId,
                properties,
                overrides);
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
     * nestingDepth.
     *
     * @return result
     */

    public int nestingDepth() {
        return nestingDepth;
    }

    /**
     * maxNestingDepth.
     *
     * @return result
     */

    public int maxNestingDepth() {
        return maxNestingDepth;
    }

    /**
     * pythonExecutor.
     *
     * @return result
     */

    public PythonCodeExecutor pythonExecutor() {
        return pythonExecutor;
    }

    /**
     * subWorkflowResolver.
     *
     * @return result
     */

    public SubWorkflowResolver subWorkflowResolver() {
        return subWorkflowResolver;
    }

    /**
     * toolRegistry.
     *
     * @return result
     */

    public ToolRegistry toolRegistry() {
        return toolRegistry;
    }

    /**
     * nodeTypeRegistry.
     *
     * @return result
     */

    public NodeTypeRegistry nodeTypeRegistry() {
        return nodeTypeRegistry;
    }

    /**
     * variableScope.
     *
     * @return result
     */

    public WorkflowVariableScope variableScope() {
        return variableScope;
    }

    /**
     * tenantId.
     *
     * @return result
     */

    public String tenantId() {
        return tenantId;
    }

    /**
     * properties.
     *
     * @return result
     */

    public StudioDslNodeProperties properties() {
        return properties;
    }

    /**
     * Test-only engine stubs; {@code null} in production.
     *
     * @return result
     * @since 0.1.0
     */

    public StudioEngineTestOverrides testOverrides() {
        return testOverrides;
    }

    /**
     * Nested child workflow: new depth + independent variable scope (L2 §3.7).
     *
     * @return result
     */

    public NodeBuildContext childDepth() {
        return new NodeBuildContext(
                workflowId + "/child@" + (nestingDepth + 1),
                nestingDepth + 1,
                maxNestingDepth,
                pythonExecutor,
                subWorkflowResolver,
                toolRegistry,
                nodeTypeRegistry,
                new WorkflowVariableScope(),
                tenantId,
                properties,
                testOverrides);
    }
}
