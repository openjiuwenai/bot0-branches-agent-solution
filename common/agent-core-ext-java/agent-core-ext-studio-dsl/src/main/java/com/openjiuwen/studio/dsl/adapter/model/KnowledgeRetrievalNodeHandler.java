/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.adapter.model;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.adapter.AbstractStudioNode;
import com.openjiuwen.studio.dsl.adapter.DelegatingStudioNode;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.kb.KnowledgeRetrievalEngine;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.NodePayload;
import com.openjiuwen.studio.dsl.contract.NodeHandlerFactory;

import java.util.Map;
import java.util.Set;

/**
 * jiuwen.knowledgeRetrieval — core bridge or FlowKnowledgeRetrieval engine + kb adapters (P4).
 *
 * @since 2026-08-17
 */
public final class KnowledgeRetrievalNodeHandler implements NodeHandlerFactory {
    @Override
    public String canonicalType() {
        return "jiuwen.knowledgeRetrieval";
    }

    @Override
    public Set<String> aliases() {
        return Set.of();
    }

    @Override
    public ComponentExecutable create(AssembledNode node, NodeBuildContext ctx) {
        if (ctx.coreExecutableFactory() != null) {
            return ctx.coreExecutableFactory()
                    .createKnowledgeRetrieval(node)
                    .<ComponentExecutable>map(core -> new DelegatingStudioNode(node, core))
                    .orElseGet(() -> new KnowledgeExecutable(node));
        }
        return new KnowledgeExecutable(node);
    }

    static final class KnowledgeExecutable extends AbstractStudioNode {
        KnowledgeExecutable(AssembledNode node) {
            super(node);
        }

        @Override
        protected NodePayload doInvoke(Map<String, Object> inputs, NodeSessionApi session, ModelContext context) {
            KnowledgeRetrievalEngine engine = new KnowledgeRetrievalEngine(node.id(), node.configs());
            return NodePayload.userFields(engine.invoke(inputs, session));
        }
    }
}
