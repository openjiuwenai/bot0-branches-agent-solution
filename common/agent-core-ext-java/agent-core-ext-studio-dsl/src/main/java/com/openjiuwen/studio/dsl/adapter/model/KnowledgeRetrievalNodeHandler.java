/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.adapter.model;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.adapter.AbstractStudioNode;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.kb.KnowledgeRetrievalEngine;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.NodePayload;
import com.openjiuwen.studio.dsl.contract.NodeHandlerFactory;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * jiuwen.knowledgeRetrieval — strict 1:1 with Python {@code flow_knowledge_retrieval.FlowKnowledgeRetrieval}.
 *
 * <p>Always uses {@link KnowledgeRetrievalEngine} (OBS/inline kbConfig, FAQ/CLOSE/CUSTOM, adapters,
 * Redis cache). Does not route through core {@code KnowledgeRetrievalExecutable}.
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
        return Set.of("jiuwen.knowledge_retrieval", "jiuwen.KnowledgeRetrieval");
    }

    @Override
    public ComponentExecutable create(AssembledNode node, NodeBuildContext ctx) {
        return new KnowledgeExecutable(node);
    }

    static final class KnowledgeExecutable extends AbstractStudioNode {
        private final KnowledgeRetrievalEngine engine;

        KnowledgeExecutable(AssembledNode node) {
            super(node);
            this.engine = new KnowledgeRetrievalEngine(node.id(), node.configs());
        }

        KnowledgeRetrievalEngine engine() {
            return engine;
        }

        @Override
        protected NodePayload doInvoke(Map<String, Object> inputs, NodeSessionApi session, ModelContext context) {
            // Python returns {USER_FIELDS: {output_list: ...}}; engine builds that map's userFields body
            return NodePayload.userFields(engine.invoke(inputs, session));
        }

        /** Python {@code stream} — single chunk = invoke result. */
        @Override
        public Iterator<Object> stream(Object inputs, NodeSessionApi session, ModelContext context) {
            Object out = invoke(inputs, session, context);
            return List.of(out).iterator();
        }
    }
}
