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

    public static NodeBuildContext defaults(String workflowId) {
        return defaults(workflowId, new StudioDslNodeProperties());
    }

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

    /** Bind registry after builtins registered (for loop body create). */
    public NodeBuildContext withRegistry(NodeTypeRegistry registry) {
        return copy(
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

    public String workflowId() {
        return workflowId;
    }

    public int nestingDepth() {
        return nestingDepth;
    }

    public int maxNestingDepth() {
        return maxNestingDepth;
    }

    public PythonCodeExecutor pythonExecutor() {
        return pythonExecutor;
    }

    public CodeLogicRegistry codeLogicRegistry() {
        return codeLogicRegistry;
    }

    public SubWorkflowResolver subWorkflowResolver() {
        return subWorkflowResolver;
    }

    public CoreExecutableFactory coreExecutableFactory() {
        return coreExecutableFactory;
    }

    public McpToolInvoker mcpToolInvoker() {
        return mcpToolInvoker;
    }

    public AgentInvoker agentInvoker() {
        return agentInvoker;
    }

    public ToolRegistry toolRegistry() {
        return toolRegistry;
    }

    public AgentRegistry agentRegistry() {
        return agentRegistry;
    }

    public NodeTypeRegistry nodeTypeRegistry() {
        return nodeTypeRegistry;
    }

    public WorkflowVariableScope variableScope() {
        return variableScope;
    }

    public String tenantId() {
        return tenantId;
    }

    public StudioDslNodeProperties properties() {
        return properties;
    }

    /** Nested child workflow: new depth + independent variable scope (L2 §3.7). */
    public NodeBuildContext childDepth() {
        return copy(
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

    private static NodeBuildContext copy(
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
                nodeTypeRegistry,
                variableScope,
                tenantId,
                properties);
    }
}
