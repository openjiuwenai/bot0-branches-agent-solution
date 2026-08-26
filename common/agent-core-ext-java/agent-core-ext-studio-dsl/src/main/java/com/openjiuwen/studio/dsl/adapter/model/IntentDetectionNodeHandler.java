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
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.intentdetection.IntentDetectionEngine;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.NodePayload;
import com.openjiuwen.studio.dsl.contract.NodeHandlerFactory;
import com.openjiuwen.studio.dsl.contract.ToolRegistry;

import java.util.Map;
import java.util.Set;

/**
 * jiuwen.intentDetection — strict 1:1 with Python {@code intent_detection.py}.
 *
 * @since 2026-08-17
 */
public final class IntentDetectionNodeHandler implements NodeHandlerFactory {
    @Override
    public String canonicalType() {
        return "jiuwen.intentDetection";
    }

    @Override
    public Set<String> aliases() {
        return Set.of();
    }

    @Override
    public ComponentExecutable create(AssembledNode node, NodeBuildContext ctx) {
        return new IntentExecutable(node, ctx.toolRegistry());
    }

    static final class IntentExecutable extends AbstractStudioNode {
        private final IntentDetectionEngine engine;

        IntentExecutable(AssembledNode node, ToolRegistry toolRegistry) {
            super(node);
            this.engine = new IntentDetectionEngine(node.id(), node.configs(), toolRegistry);
        }

        IntentDetectionEngine engine() {
            return engine;
        }

        public void addBranch(Object condition, Object target, String branchId) {
            engine.addBranch(condition, target, branchId);
        }

        public BranchRouter router() {
            return engine.router();
        }

        @Override
        public void addComponent(Graph graph, String nodeId, boolean waitForAll) {
            graph.addNode(nodeId, this, waitForAll);
            graph.addConditionalEdges(nodeId, engine.router());
        }

        @Override
        protected NodePayload doInvoke(Map<String, Object> inputs, NodeSessionApi session, ModelContext context) {
            return NodePayload.userFields(engine.invoke(inputs, session, context));
        }
    }
}
