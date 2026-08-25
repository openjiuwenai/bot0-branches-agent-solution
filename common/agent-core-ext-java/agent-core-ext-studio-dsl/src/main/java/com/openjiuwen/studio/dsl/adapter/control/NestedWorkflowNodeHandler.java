/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.adapter.control;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.adapter.AbstractStudioNode;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.exec.WorkflowAssemblyBridge;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.AssembledWorkflow;
import com.openjiuwen.studio.dsl.model.NodeCauseCode;
import com.openjiuwen.studio.dsl.model.NodePayload;
import com.openjiuwen.studio.dsl.registry.NodeTypeRegistry;
import com.openjiuwen.studio.dsl.spi.NodeHandlerFactory;
import com.openjiuwen.studio.dsl.util.DeepCopies;
import com.openjiuwen.studio.dsl.util.SessionStateIsolator;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * NestedWorkflowNodeHandler for Studio DSL node-type extension (FEAT-031).
 *
 * @since 2026-08-17
 */
public final class NestedWorkflowNodeHandler implements NodeHandlerFactory {
    private final NodeTypeRegistry registry;
    /**
     * NestedWorkflowNodeHandler.
     * @param registry registry
     */
    public NestedWorkflowNodeHandler(NodeTypeRegistry registry) {
        this.registry = registry;
    }
    /**
     * canonicalType.
     */
    @Override
    public String canonicalType() {
        return "jiuwen.subWorkflow";
    }
    /**
     * aliases.
     */
    @Override
    public Set<String> aliases() {
        return Set.of("jiuwen.workflowComposite");
    }
    /**
     * create.
     * @param node node
     * @param ctx ctx
     */
    @Override
    public ComponentExecutable create(AssembledNode node, NodeBuildContext ctx) {
        int next = ctx.nestingDepth() + 1;
        if (next > ctx.maxNestingDepth()) {
            throw new NodeExecutionException(
                    node.id(),
                    "jiuwen.subWorkflow",
                    NodeCauseCode.NESTING_DEPTH_EXCEEDED,
                    "depth=" + next + ", max=" + ctx.maxNestingDepth());
        }
        AssembledWorkflow child;
        try {
            child = ctx.subWorkflowResolver().resolve(node.configs());
        } catch (NodeExecutionException e) {
            throw e;
        } catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException e) {
            throw new NodeExecutionException(
                    node.id(), "jiuwen.subWorkflow", NodeCauseCode.SUBWORKFLOW_REF_INVALID, e.getMessage(), e);
        }
        if (child == null) {
            throw new NodeExecutionException(
                    node.id(), "jiuwen.subWorkflow", NodeCauseCode.SUBWORKFLOW_REF_INVALID, "resolver returned null");
        }
        Map<String, ComponentExecutable> childExec =
                new WorkflowAssemblyBridge(registry).mapExecutables(child, ctx.childDepth());
        return new NestedExecutable(node, childExec, next);
    }

    static final class NestedExecutable extends AbstractStudioNode {
        private final Map<String, ComponentExecutable> childExec;
        private final int depth;

        NestedExecutable(AssembledNode node, Map<String, ComponentExecutable> childExec, int depth) {
            super(node);
            this.childExec = childExec;
            this.depth = depth;
        }
        /**
         * doInvoke.
         * @param inputs inputs
         * @param session session
         * @param context context
         */
        @Override
        protected NodePayload doInvoke(Map<String, Object> inputs, NodeSessionApi session, ModelContext context) {
            // Isolated child frame (deep copy + session local state) — L2 §4.5.1 / FEAT §5.5.
            return SessionStateIsolator.runIsolated(session, () -> invokeChild(inputs, session, context));
        }

        private NodePayload invokeChild(Map<String, Object> inputs, NodeSessionApi session, ModelContext context) {
            Map<String, Object> current = DeepCopies.map(asMap(inputs));
            Map<String, Object> uf = DeepCopies.map(userFieldsOf(inputs));
            uf.put("__nestingDepth__", depth);
            current.put("userFields", uf);
            try {
                for (ComponentExecutable exec : childExec.values()) {
                    Object out = exec.invoke(current, session, context);
                    if (out instanceof Map<?, ?>) {
                        current = DeepCopies.map(AbstractStudioNode.asMap(out));
                    }
                }
                return NodePayload.userFields(userFieldsOf(current));
            } catch (NodeExecutionException e) {
                throw new NodeExecutionException(
                        node.id(),
                        "jiuwen.subWorkflow",
                        NodeCauseCode.NODE_INVOKE_FAILED,
                        "child failed: " + e.getMessage(),
                        e);
            }
        }
    }
}
