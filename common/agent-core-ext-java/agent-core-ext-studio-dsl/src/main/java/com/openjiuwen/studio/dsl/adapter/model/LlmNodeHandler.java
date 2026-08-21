package com.openjiuwen.studio.dsl.adapter.model;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.adapter.AbstractStudioNode;
import com.openjiuwen.studio.dsl.adapter.DelegatingStudioNode;
import com.openjiuwen.studio.dsl.adapter.PassthroughStudioNode;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.MediaPart;
import com.openjiuwen.studio.dsl.model.NodeCauseCode;
import com.openjiuwen.studio.dsl.model.NodePayload;
import com.openjiuwen.studio.dsl.spi.NodeHandlerFactory;
import com.openjiuwen.studio.dsl.util.MediaSupport;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * jiuwen.LLMComponent — ConfigDrivenCoreExecutableFactory → LLMExecutable when model wired;
 * consumes MediaPart into prompt context (FEAT multimodal).
 */
public final class LlmNodeHandler implements NodeHandlerFactory {
    @Override
    public String canonicalType() {
        return "jiuwen.LLMComponent";
    }

    @Override
    public Set<String> aliases() {
        return Set.of("jiuwen.llm", "jiuwen.llm_chain", "jiuwen.llmChain");
    }

    @Override
    public ComponentExecutable create(AssembledNode node, NodeBuildContext ctx) {
        if (ctx.coreExecutableFactory() != null) {
            ComponentExecutable core = ctx.coreExecutableFactory().createLlm(node);
            if (core != null) {
                return new MediaAwareDelegate(node, core);
            }
        }
        return new LlmFallbackExecutable(node);
    }

    /** Inject consumable media into inputs before core invoke. */
    static final class MediaAwareDelegate extends AbstractStudioNode {
        private final ComponentExecutable delegate;

        MediaAwareDelegate(AssembledNode node, ComponentExecutable delegate) {
            super(node);
            this.delegate = delegate;
        }

        @Override
        protected NodePayload doInvoke(Map<String, Object> inputs, NodeSessionApi session, ModelContext context)
                throws Exception {
            List<MediaPart> media = MediaSupport.mediaOf(inputs);
            Map<String, Object> uf = MediaSupport.withConsumableMedia(userFieldsOf(inputs), media);
            Map<String, Object> in = MediaSupport.flattenForPrompt(inputs, uf);
            Object out = delegate.invoke(in, session, context);
            Map<String, Object> map = asMap(out);
            NodePayload payload;
            if (map.containsKey("userFields")) {
                payload = NodePayload.userFields(userFieldsOf(map));
            } else {
                payload = NodePayload.ofFields(map);
            }
            return payload.withMediaPassthrough(media);
        }
    }

    static final class LlmFallbackExecutable extends AbstractStudioNode {
        LlmFallbackExecutable(AssembledNode node) {
            super(node);
        }

        @Override
        protected NodePayload doInvoke(Map<String, Object> inputs, NodeSessionApi session, ModelContext context) {
            List<MediaPart> media = MediaSupport.mediaOf(inputs);
            Map<String, Object> uf = MediaSupport.withConsumableMedia(userFieldsOf(inputs), media);
            // Explicit test/mock only — never echo prompt as fake LLM success (FEAT-031 / L2 §1.2).
            Object mock = node.configs().get("mockOutput");
            if (mock != null) {
                uf.put("text", String.valueOf(mock));
                uf.put("content", String.valueOf(mock));
                uf.put("mediaConsumed", !media.isEmpty());
                return NodePayload.userFields(uf).withMediaPassthrough(media);
            }
            throw new NodeExecutionException(
                    node.id(),
                    "jiuwen.LLMComponent",
                    NodeCauseCode.NODE_CONFIG_INVALID,
                    "LLM core executable unavailable; provide model client wiring"
                            + " (apiKey/apiBase/modelClientConfig) via ConfigDrivenCoreExecutableFactory,"
                            + " or explicit mockOutput");
        }
    }
}
