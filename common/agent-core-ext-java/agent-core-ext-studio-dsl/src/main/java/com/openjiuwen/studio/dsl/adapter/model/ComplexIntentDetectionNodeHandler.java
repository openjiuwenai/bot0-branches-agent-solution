/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.adapter.model;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.adapter.AbstractStudioNode;
import com.openjiuwen.studio.dsl.complexintent.ComplexIntentDetectionEngine;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.NodePayload;
import com.openjiuwen.studio.dsl.contract.NodeHandlerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * EI.ComplexIntentDetection — strict 1:1 with Python {@code complex_intent_detection.py}.
 *
 * @since 2026-08-25
 */
public final class ComplexIntentDetectionNodeHandler implements NodeHandlerFactory {
    @Override
    public String canonicalType() {
        return "EI.ComplexIntentDetection";
    }

    @Override
    public Set<String> aliases() {
        return Set.of("ei.complexIntentDetection", "EI.complexIntentDetection");
    }

    @Override
    public ComponentExecutable create(AssembledNode node, NodeBuildContext ctx) {
        return new ComplexIntentExecutable(node, ctx);
    }

    static final class ComplexIntentExecutable extends AbstractStudioNode {
        private final ComplexIntentDetectionEngine engine;

        ComplexIntentExecutable(AssembledNode node, NodeBuildContext ctx) {
            super(node);
            ComplexIntentDetectionEngine.TestBridge bridge =
                    ctx != null && ctx.testOverrides() != null
                            ? ctx.testOverrides().complexIntentBridge()
                            : null;
            this.engine = new ComplexIntentDetectionEngine(
                    node.id(),
                    node.configs(),
                    ctx == null ? null : ctx.subWorkflowResolver(),
                    ctx == null ? null : ctx.nodeTypeRegistry(),
                    ctx,
                    ctx == null ? null : ctx.toolRegistry(),
                    bridge);
        }

        ComplexIntentDetectionEngine engine() {
            return engine;
        }

        @Override
        protected NodePayload doInvoke(Map<String, Object> inputs, NodeSessionApi session, ModelContext context) {
            Map<String, Object> result = engine.invoke(inputs == null ? Map.of() : inputs, session, context);
            return NodePayload.ofFields(new LinkedHashMap<>(result));
        }
    }
}
