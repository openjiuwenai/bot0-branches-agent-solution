/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.adapter.model;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.graph.Graph;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.BranchRouter;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.adapter.AbstractStudioNode;
import com.openjiuwen.studio.dsl.contract.NodeHandlerFactory;
import com.openjiuwen.studio.dsl.contract.ToolRegistry;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.intentdetection.IntentDetectionEngine;
import com.openjiuwen.studio.dsl.intentdetection.IntentDetectionLlmDetector;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.NodePayload;

import java.util.Map;
import java.util.Set;

/**
 * jiuwen.intentDetection — strict 1:1 with Python {@code intent_detection.py}.
 *
 * @since 2026-08-17
 */

public final class IntentDetectionNodeHandler implements NodeHandlerFactory {

    /**
     * canonicalType.
     *
     * @return result
     * @since 0.1.0
     */

    @Override
    public String canonicalType() {
        return "jiuwen.intentDetection";
    }

    /**
     * aliases.
     *
     * @return result
     * @since 0.1.0
     */

    @Override
    public Set<String> aliases() {
        return Set.of();
    }

    /**
     * create.
     *
     * @param node node
     * @param ctx ctx
     * @return result
     * @since 0.1.0
     */

    @Override
    public ComponentExecutable create(AssembledNode node, NodeBuildContext ctx) {
        return new IntentExecutable(node, ctx);
    }
    static final class IntentExecutable extends AbstractStudioNode {
        private final IntentDetectionEngine engine;

        IntentExecutable(AssembledNode node, NodeBuildContext ctx) {
            super(node);
            ToolRegistry toolRegistry = ctx == null ? null : ctx.toolRegistry();
            IntentDetectionLlmDetector.ModelInvoker invoker =
                    ctx != null && ctx.testOverrides() != null ? ctx.testOverrides().intentInvoker() : null;
            if (invoker != null) {
                this.engine = new IntentDetectionEngine(node.id(), node.configs(), invoker, toolRegistry);
            } else {
                this.engine = new IntentDetectionEngine(node.id(), node.configs(), toolRegistry);
            }
        }

        IntentDetectionEngine engine() {
            return engine;
        }

        /**
         * addBranch.
         *
         * @param condition condition
         * @param target target
         * @param branchId branchId
         * @since 0.1.0
         */

        public void addBranch(Object condition, Object target, String branchId) {
            engine.addBranch(condition, target, branchId);
        }

        /**
         * router.
         *
         * @return result
         * @since 0.1.0
         */

        public BranchRouter router() {
            return engine.router();
        }

        /**
         * addComponent.
         *
         * @param graph graph
         * @param nodeId nodeId
         * @param waitForAll waitForAll
         * @since 0.1.0
         */

        @Override
        public void addComponent(Graph graph, String nodeId, boolean waitForAll) {
            graph.addNode(nodeId, this, waitForAll);
            graph.addConditionalEdges(nodeId, engine.router());
        }

        /**
         * doInvoke.
         *
         * @param inputs inputs
         * @param session session
         * @param context context
         * @return result
         * @since 0.1.0
         */

        @Override
        protected NodePayload doInvoke(Map<String, Object> inputs, NodeSessionApi session, ModelContext context) {
            return NodePayload.userFields(engine.invoke(inputs, session, context));
        }
    }
}
