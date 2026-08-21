package com.openjiuwen.studio.dsl.adapter.model;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.adapter.AbstractStudioNode;
import com.openjiuwen.studio.dsl.adapter.DelegatingStudioNode;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.NodeCauseCode;
import com.openjiuwen.studio.dsl.model.NodePayload;
import com.openjiuwen.studio.dsl.spi.NodeHandlerFactory;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** jiuwen.knowledgeRetrieval — core bridge or configs.mockDocuments. */
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
            ComponentExecutable core = ctx.coreExecutableFactory().createKnowledgeRetrieval(node);
            if (core != null) {
                return new DelegatingStudioNode(node, core);
            }
        }
        return new KnowledgeFallback(node);
    }

    static final class KnowledgeFallback extends AbstractStudioNode {
        KnowledgeFallback(AssembledNode node) {
            super(node);
        }

        @Override
        protected NodePayload doInvoke(Map<String, Object> inputs, NodeSessionApi session, ModelContext context) {
            Map<String, Object> uf = new LinkedHashMap<>(userFieldsOf(inputs));
            // Explicit mock only — empty docs must not masquerade as successful retrieval (L2 §1.2).
            Object docs = node.configs().get("mockDocuments");
            if (docs instanceof List<?> list) {
                uf.put("documents", list);
                uf.put("knowledgeResults", list);
                return NodePayload.userFields(uf);
            }
            throw new NodeExecutionException(
                    node.id(),
                    "jiuwen.knowledgeRetrieval",
                    NodeCauseCode.NODE_CONFIG_INVALID,
                    "KnowledgeRetrieval core executable unavailable; provide kbConfigs/kbId"
                            + " (and optional embed/vectorStore) via ConfigDrivenCoreExecutableFactory,"
                            + " or explicit mockDocuments");
        }
    }
}
