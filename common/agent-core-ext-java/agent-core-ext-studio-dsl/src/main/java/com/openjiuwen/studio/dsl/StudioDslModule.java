/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl;

import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.config.StudioDslNodeProperties;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.exec.WorkflowAssemblyBridge;
import com.openjiuwen.studio.dsl.exec.WorkflowVariableScope;
import com.openjiuwen.studio.dsl.model.AssembledWorkflow;
import com.openjiuwen.studio.dsl.model.NodeCauseCode;
import com.openjiuwen.studio.dsl.python.SubprocessPythonCodeExecutor;
import com.openjiuwen.studio.dsl.registry.NodeTypeRegistry;
import com.openjiuwen.studio.dsl.contract.EmptyToolRegistry;
import com.openjiuwen.studio.dsl.contract.PythonCodeExecutor;
import com.openjiuwen.studio.dsl.contract.SubWorkflowResolver;
import com.openjiuwen.studio.dsl.contract.ToolRegistry;

import java.util.Map;

/**
 * Programmatic module bootstrap (L2 §4.6 / §6.3 without requiring Spring).
 *
 * @since 2026-08-17
 */
public final class StudioDslModule {
    private final StudioDslNodeProperties properties;
    private final NodeTypeRegistry registry;
    private final PythonCodeExecutor pythonExecutor;
    private final ToolRegistry toolRegistry;
    private final SubWorkflowResolver subWorkflowResolver;

    private StudioDslModule(
            StudioDslNodeProperties properties,
            NodeTypeRegistry registry,
            PythonCodeExecutor pythonExecutor,
            ToolRegistry toolRegistry,
            SubWorkflowResolver subWorkflowResolver) {
        this.properties = properties == null ? new StudioDslNodeProperties() : properties;
        this.registry = registry;
        this.pythonExecutor = pythonExecutor;
        this.toolRegistry = toolRegistry;
        this.subWorkflowResolver = subWorkflowResolver;
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
                EmptyToolRegistry.INSTANCE,
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
                toolRegistry,
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
                tools,
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
     * Map assembled workflow nodes to executables (scheduling / IR binding is host responsibility).
     *
     * @param workflow assembled workflow
     * @param ctx build context
     * @return node id → executable
     */
    public Map<String, ComponentExecutable> mapExecutables(AssembledWorkflow workflow, NodeBuildContext ctx) {
        return new WorkflowAssemblyBridge(registry).mapExecutables(workflow, ctx);
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
                subWorkflowResolver,
                toolRegistry,
                registry,
                new WorkflowVariableScope(),
                tenantId,
                properties,
                null);
    }
}
