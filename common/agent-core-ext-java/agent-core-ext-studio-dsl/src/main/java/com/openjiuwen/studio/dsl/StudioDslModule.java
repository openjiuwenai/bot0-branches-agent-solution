/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl;

import com.openjiuwen.studio.dsl.bridge.ConfigDrivenCoreExecutableFactory;
import com.openjiuwen.studio.dsl.bridge.DefaultMcpToolInvoker;
import com.openjiuwen.studio.dsl.bridge.InMemoryAgentRegistry;
import com.openjiuwen.studio.dsl.bridge.InMemoryToolRegistry;
import com.openjiuwen.studio.dsl.config.StudioDslNodeProperties;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.exec.WorkflowAssemblyBridge;
import com.openjiuwen.studio.dsl.exec.WorkflowVariableScope;
import com.openjiuwen.studio.dsl.model.NodeCauseCode;
import com.openjiuwen.studio.dsl.python.SubprocessPythonCodeExecutor;
import com.openjiuwen.studio.dsl.registry.CodeLogicRegistry;
import com.openjiuwen.studio.dsl.registry.NodeTypeRegistry;
import com.openjiuwen.studio.dsl.contract.AgentInvoker;
import com.openjiuwen.studio.dsl.contract.AgentRegistry;
import com.openjiuwen.studio.dsl.contract.CoreExecutableFactory;
import com.openjiuwen.studio.dsl.contract.McpToolInvoker;
import com.openjiuwen.studio.dsl.contract.PythonCodeExecutor;
import com.openjiuwen.studio.dsl.contract.SubWorkflowResolver;
import com.openjiuwen.studio.dsl.contract.ToolRegistry;

/**
 * Programmatic module bootstrap (L2 §4.6 / §6.3 without requiring Spring).
 * Hosts should prefer {@link WorkflowAssemblyBridge#executeLinear} so variable scope closes on completion.
 *
 * @since 2026-08-17
 */
public final class StudioDslModule {
    private final StudioDslNodeProperties properties;
    private final NodeTypeRegistry registry;
    private final PythonCodeExecutor pythonExecutor;
    private final CodeLogicRegistry codeLogicRegistry;
    private final CoreExecutableFactory coreExecutableFactory;
    private final McpToolInvoker mcpToolInvoker;
    private final AgentInvoker agentInvoker;
    private final ToolRegistry toolRegistry;
    private final AgentRegistry agentRegistry;
    private final SubWorkflowResolver subWorkflowResolver;
    private final WorkflowAssemblyBridge assemblyBridge;

    private StudioDslModule(
            StudioDslNodeProperties properties,
            NodeTypeRegistry registry,
            PythonCodeExecutor pythonExecutor,
            CodeLogicRegistry codeLogicRegistry,
            CoreExecutableFactory coreExecutableFactory,
            McpToolInvoker mcpToolInvoker,
            AgentInvoker agentInvoker,
            ToolRegistry toolRegistry,
            AgentRegistry agentRegistry,
            SubWorkflowResolver subWorkflowResolver) {
        this.properties = properties == null ? new StudioDslNodeProperties() : properties;
        this.registry = registry;
        this.pythonExecutor = pythonExecutor;
        this.codeLogicRegistry = codeLogicRegistry;
        this.coreExecutableFactory = coreExecutableFactory;
        this.mcpToolInvoker = mcpToolInvoker;
        this.toolRegistry = toolRegistry;
        this.agentRegistry = agentRegistry;
        this.agentInvoker = agentInvoker;
        this.subWorkflowResolver = subWorkflowResolver;
        this.assemblyBridge = new WorkflowAssemblyBridge(registry);
    }

    /**
     * create.
     *
     * @return result
     */
    public static StudioDslModule create() {
        return create(new StudioDslNodeProperties());
    }

    /**
     * create.
     *
     * @param properties properties
     * @return result
     */
    public static StudioDslModule create(StudioDslNodeProperties properties) {
        StudioDslNodeProperties props = properties == null ? new StudioDslNodeProperties() : properties;
        NodeTypeRegistry registry = NodeTypeRegistry.createWithBuiltins();
        PythonCodeExecutor python = new SubprocessPythonCodeExecutor();
        return new StudioDslModule(
                props,
                registry,
                python,
                new CodeLogicRegistry(),
                new ConfigDrivenCoreExecutableFactory(),
                new DefaultMcpToolInvoker(),
                null,
                new InMemoryToolRegistry(),
                new InMemoryAgentRegistry(),
                configs -> {
                    throw new NodeExecutionException(
                            "n/a",
                            "jiuwen.subWorkflow",
                            NodeCauseCode.SUBWORKFLOW_REF_INVALID,
                            "no SubWorkflowResolver configured");
                });
    }

    /**
     * withSubWorkflowResolver.
     *
     * @param resolver resolver
     * @return result
     */
    public StudioDslModule withSubWorkflowResolver(SubWorkflowResolver resolver) {
        return new StudioDslModule(
                properties,
                registry,
                pythonExecutor,
                codeLogicRegistry,
                coreExecutableFactory,
                mcpToolInvoker,
                agentInvoker,
                toolRegistry,
                agentRegistry,
                resolver);
    }

    /**
     * withToolRegistry.
     *
     * @param tools tools
     * @return result
     */
    public StudioDslModule withToolRegistry(ToolRegistry tools) {
        return new StudioDslModule(
                properties,
                registry,
                pythonExecutor,
                codeLogicRegistry,
                coreExecutableFactory,
                mcpToolInvoker,
                agentInvoker,
                tools,
                agentRegistry,
                subWorkflowResolver);
    }

    /**
     * withAgentRegistry.
     *
     * @param agents agents
     * @return result
     */
    public StudioDslModule withAgentRegistry(AgentRegistry agents) {
        return new StudioDslModule(
                properties,
                registry,
                pythonExecutor,
                codeLogicRegistry,
                coreExecutableFactory,
                mcpToolInvoker,
                agentInvoker,
                toolRegistry,
                agents,
                subWorkflowResolver);
    }

    /**
     * withMcpToolInvoker.
     *
     * @param mcp mcp
     * @return result
     */
    public StudioDslModule withMcpToolInvoker(McpToolInvoker mcp) {
        return new StudioDslModule(
                properties,
                registry,
                pythonExecutor,
                codeLogicRegistry,
                coreExecutableFactory,
                mcp,
                agentInvoker,
                toolRegistry,
                agentRegistry,
                subWorkflowResolver);
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
     * registry.
     *
     * @return result
     */
    public NodeTypeRegistry registry() {
        return registry;
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
     * codeLogicRegistry.
     *
     * @return result
     */
    public CodeLogicRegistry codeLogicRegistry() {
        return codeLogicRegistry;
    }

    /**
     * assemblyBridge.
     *
     * @return result
     */
    public WorkflowAssemblyBridge assemblyBridge() {
        return assemblyBridge;
    }

    /**
     * Root build context for one workflow execution (fresh variable scope).
     *
     * @param workflowId workflowId
     * @return result
     */
    public NodeBuildContext newRootContext(String workflowId) {
        return newRootContext(workflowId, null);
    }

    /**
     * newRootContext.
     *
     * @param workflowId workflowId
     * @param tenantId tenantId
     * @return result
     */
    public NodeBuildContext newRootContext(String workflowId, String tenantId) {
        return new NodeBuildContext(
                workflowId,
                0,
                properties.getMaxNestingDepth(),
                pythonExecutor,
                codeLogicRegistry,
                subWorkflowResolver,
                coreExecutableFactory,
                mcpToolInvoker,
                agentInvoker,
                toolRegistry,
                agentRegistry,
                registry,
                new WorkflowVariableScope(),
                tenantId,
                properties);
    }
}
