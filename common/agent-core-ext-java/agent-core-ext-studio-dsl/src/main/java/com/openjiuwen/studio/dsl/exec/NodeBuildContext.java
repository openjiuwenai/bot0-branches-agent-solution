/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.exec;

import com.openjiuwen.studio.dsl.bridge.ConfigDrivenCoreExecutableFactory;
import com.openjiuwen.studio.dsl.bridge.DefaultAgentInvoker;
import com.openjiuwen.studio.dsl.bridge.DefaultMcpToolInvoker;
import com.openjiuwen.studio.dsl.bridge.InMemoryAgentRegistry;
import com.openjiuwen.studio.dsl.bridge.InMemoryToolRegistry;
import com.openjiuwen.studio.dsl.config.StudioDslNodeProperties;
import com.openjiuwen.studio.dsl.registry.CodeLogicRegistry;
import com.openjiuwen.studio.dsl.registry.NodeTypeRegistry;
import com.openjiuwen.studio.dsl.spi.AgentInvoker;
import com.openjiuwen.studio.dsl.spi.AgentRegistry;
import com.openjiuwen.studio.dsl.spi.CoreExecutableFactory;
import com.openjiuwen.studio.dsl.spi.McpToolInvoker;
import com.openjiuwen.studio.dsl.spi.PythonCodeExecutor;
import com.openjiuwen.studio.dsl.spi.SubWorkflowResolver;
import com.openjiuwen.studio.dsl.spi.ToolRegistry;

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
    private final CodeLogicRegistry codeLogicRegistry;
    private final SubWorkflowResolver subWorkflowResolver;
    private final CoreExecutableFactory coreExecutableFactory;
    private final McpToolInvoker mcpToolInvoker;
    private final AgentInvoker agentInvoker;
    private final ToolRegistry toolRegistry;
    private final AgentRegistry agentRegistry;
    private final NodeTypeRegistry nodeTypeRegistry;
    private final WorkflowVariableScope variableScope;
    private final String tenantId;
    private final StudioDslNodeProperties properties;
    /**
     * NodeBuildContext.
     * @param workflowId workflowId
     * @param nestingDepth nestingDepth
     * @param maxNestingDepth maxNestingDepth
     * @param pythonExecutor pythonExecutor
     * @param codeLogicRegistry codeLogicRegistry
     * @param subWorkflowResolver subWorkflowResolver
     */
    public NodeBuildContext(
            String workflowId,
            int nestingDepth,
            int maxNestingDepth,
            PythonCodeExecutor pythonExecutor,
            CodeLogicRegistry codeLogicRegistry,
            SubWorkflowResolver subWorkflowResolver) {
        this(
                workflowId,
                nestingDepth,
                maxNestingDepth,
                pythonExecutor,
                codeLogicRegistry,
                subWorkflowResolver,
                new ConfigDrivenCoreExecutableFactory(),
                new DefaultMcpToolInvoker(),
                null,
                new InMemoryToolRegistry(),
                new InMemoryAgentRegistry(),
                null,
                new WorkflowVariableScope(),
                null,
                new StudioDslNodeProperties());
    }
    /**
     * NodeBuildContext.
     * @param workflowId workflowId
     * @param nestingDepth nestingDepth
     * @param maxNestingDepth maxNestingDepth
     * @param pythonExecutor pythonExecutor
     * @param codeLogicRegistry codeLogicRegistry
     * @param subWorkflowResolver subWorkflowResolver
     * @param coreExecutableFactory coreExecutableFactory
     * @param mcpToolInvoker mcpToolInvoker
     * @param agentInvoker agentInvoker
     * @param toolRegistry toolRegistry
     * @param agentRegistry agentRegistry
     * @param nodeTypeRegistry nodeTypeRegistry
     */
    public NodeBuildContext(
            String workflowId,
            int nestingDepth,
            int maxNestingDepth,
            PythonCodeExecutor pythonExecutor,
            CodeLogicRegistry codeLogicRegistry,
            SubWorkflowResolver subWorkflowResolver,
            CoreExecutableFactory coreExecutableFactory,
            McpToolInvoker mcpToolInvoker,
            AgentInvoker agentInvoker,
            ToolRegistry toolRegistry,
            AgentRegistry agentRegistry,
            NodeTypeRegistry nodeTypeRegistry) {
        this(
                workflowId,
                nestingDepth,
                maxNestingDepth,
                pythonExecutor,
                codeLogicRegistry,
                subWorkflowResolver,
                coreExecutableFactory,
                mcpToolInvoker,
                agentInvoker,
                toolRegistry,
                agentRegistry,
                nodeTypeRegistry,
                new WorkflowVariableScope(),
                null,
                new StudioDslNodeProperties());
    }
    /**
     * NodeBuildContext.
     * @param workflowId workflowId
     * @param nestingDepth nestingDepth
     * @param maxNestingDepth maxNestingDepth
     * @param pythonExecutor pythonExecutor
     * @param codeLogicRegistry codeLogicRegistry
     * @param subWorkflowResolver subWorkflowResolver
     * @param coreExecutableFactory coreExecutableFactory
     * @param mcpToolInvoker mcpToolInvoker
     * @param agentInvoker agentInvoker
     * @param toolRegistry toolRegistry
     * @param agentRegistry agentRegistry
     * @param nodeTypeRegistry nodeTypeRegistry
     * @param variableScope variableScope
     * @param tenantId tenantId
     * @param properties properties
     */
    public NodeBuildContext(
            String workflowId,
            int nestingDepth,
            int maxNestingDepth,
            PythonCodeExecutor pythonExecutor,
            CodeLogicRegistry codeLogicRegistry,
            SubWorkflowResolver subWorkflowResolver,
            CoreExecutableFactory coreExecutableFactory,
            McpToolInvoker mcpToolInvoker,
            AgentInvoker agentInvoker,
            ToolRegistry toolRegistry,
            AgentRegistry agentRegistry,
            NodeTypeRegistry nodeTypeRegistry,
            WorkflowVariableScope variableScope,
            String tenantId,
            StudioDslNodeProperties properties) {
        this.workflowId = workflowId;
        this.nestingDepth = nestingDepth;
        this.maxNestingDepth = maxNestingDepth;
        this.pythonExecutor = pythonExecutor;
        this.codeLogicRegistry = codeLogicRegistry;
        this.subWorkflowResolver = subWorkflowResolver;
        this.coreExecutableFactory = coreExecutableFactory;
        this.mcpToolInvoker = mcpToolInvoker;
        this.toolRegistry = toolRegistry == null ? new InMemoryToolRegistry() : toolRegistry;
        this.agentRegistry = agentRegistry == null ? new InMemoryAgentRegistry() : agentRegistry;
        this.agentInvoker =
                agentInvoker != null ? agentInvoker : new DefaultAgentInvoker(this.agentRegistry);
        this.nodeTypeRegistry = nodeTypeRegistry;
        this.variableScope = variableScope == null ? new WorkflowVariableScope() : variableScope;
        this.tenantId = tenantId;
        this.properties = properties == null ? new StudioDslNodeProperties() : properties;
    }
    /**
     * defaults.
     * @param workflowId workflowId
     */
    public static NodeBuildContext defaults(String workflowId) {
        return defaults(workflowId, new StudioDslNodeProperties());
    }
    /**
     * defaults.
     * @param workflowId workflowId
     * @param props props
     */
    public static NodeBuildContext defaults(String workflowId, StudioDslNodeProperties props) {
        StudioDslNodeProperties p = props == null ? new StudioDslNodeProperties() : props;
        return new NodeBuildContext(
                workflowId,
                0,
                p.getMaxNestingDepth(),
                null,
                new CodeLogicRegistry(),
                configs -> {
                    throw new NodeExecutionException(
                            "n/a",
                            "jiuwen.subWorkflow",
                            com.openjiuwen.studio.dsl.model.NodeCauseCode.SUBWORKFLOW_REF_INVALID,
                            "no SubWorkflowResolver configured");
                },
                new ConfigDrivenCoreExecutableFactory(),
                new DefaultMcpToolInvoker(),
                null,
                new InMemoryToolRegistry(),
                new InMemoryAgentRegistry(),
                null,
                new WorkflowVariableScope(),
                null,
                p);
    }
    /**
     * defaults.
     * @param workflowId workflowId
     * @param tenantId tenantId
     * @param props props
     */
    public static NodeBuildContext defaults(String workflowId, String tenantId, StudioDslNodeProperties props) {
        StudioDslNodeProperties p = props == null ? new StudioDslNodeProperties() : props;
        return new NodeBuildContext(
                workflowId,
                0,
                p.getMaxNestingDepth(),
                null,
                new CodeLogicRegistry(),
                configs -> {
                    throw new NodeExecutionException(
                            "n/a",
                            "jiuwen.subWorkflow",
                            com.openjiuwen.studio.dsl.model.NodeCauseCode.SUBWORKFLOW_REF_INVALID,
                            "no SubWorkflowResolver configured");
                },
                new ConfigDrivenCoreExecutableFactory(),
                new DefaultMcpToolInvoker(),
                null,
                new InMemoryToolRegistry(),
                new InMemoryAgentRegistry(),
                null,
                new WorkflowVariableScope(),
                tenantId,
                p);
    }

    /**
     * Bind registry after builtins registered (for loop body create).
     */
    public NodeBuildContext withRegistry(NodeTypeRegistry registry) {
        return new NodeBuildContext(
                workflowId,
                nestingDepth,
                maxNestingDepth,
                pythonExecutor,
                codeLogicRegistry,
                subWorkflowResolver,
                coreExecutableFactory,
                mcpToolInvoker,
                agentInvoker,
                toolRegistry,
                agentRegistry,
                registry,
                variableScope,
                tenantId,
                properties);
    }
    /**
     * workflowId.
     */
    public String workflowId() {
        return workflowId;
    }
    /**
     * nestingDepth.
     */
    public int nestingDepth() {
        return nestingDepth;
    }
    /**
     * maxNestingDepth.
     */
    public int maxNestingDepth() {
        return maxNestingDepth;
    }
    /**
     * pythonExecutor.
     */
    public PythonCodeExecutor pythonExecutor() {
        return pythonExecutor;
    }
    /**
     * codeLogicRegistry.
     */
    public CodeLogicRegistry codeLogicRegistry() {
        return codeLogicRegistry;
    }
    /**
     * subWorkflowResolver.
     */
    public SubWorkflowResolver subWorkflowResolver() {
        return subWorkflowResolver;
    }
    /**
     * coreExecutableFactory.
     */
    public CoreExecutableFactory coreExecutableFactory() {
        return coreExecutableFactory;
    }
    /**
     * mcpToolInvoker.
     */
    public McpToolInvoker mcpToolInvoker() {
        return mcpToolInvoker;
    }
    /**
     * agentInvoker.
     */
    public AgentInvoker agentInvoker() {
        return agentInvoker;
    }
    /**
     * toolRegistry.
     */
    public ToolRegistry toolRegistry() {
        return toolRegistry;
    }
    /**
     * agentRegistry.
     */
    public AgentRegistry agentRegistry() {
        return agentRegistry;
    }
    /**
     * nodeTypeRegistry.
     */
    public NodeTypeRegistry nodeTypeRegistry() {
        return nodeTypeRegistry;
    }
    /**
     * variableScope.
     */
    public WorkflowVariableScope variableScope() {
        return variableScope;
    }
    /**
     * tenantId.
     */
    public String tenantId() {
        return tenantId;
    }
    /**
     * properties.
     */
    public StudioDslNodeProperties properties() {
        return properties;
    }

    /**
     * Nested child workflow: new depth + independent variable scope (L2 §3.7).
     */
    public NodeBuildContext childDepth() {
        return new NodeBuildContext(
                workflowId + "/child@" + (nestingDepth + 1),
                nestingDepth + 1,
                maxNestingDepth,
                pythonExecutor,
                codeLogicRegistry,
                subWorkflowResolver,
                coreExecutableFactory,
                mcpToolInvoker,
                agentInvoker,
                toolRegistry,
                agentRegistry,
                nodeTypeRegistry,
                new WorkflowVariableScope(),
                tenantId,
                properties);
    }
}
