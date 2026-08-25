/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.adapter.interact;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.adapter.AbstractStudioNode;
import com.openjiuwen.studio.dsl.adapter.PassthroughStudioNode;
import com.openjiuwen.studio.dsl.adapter.StudioStreamFrames;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.NodePayload;
import com.openjiuwen.studio.dsl.contract.NodeHandlerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * jiuwen.card — emit card payload via session stream (Studio FlowCard).
 *
 * @since 2026-08-17
 */
public final class CardNodeHandler implements NodeHandlerFactory {
    /**
     * canonicalType.
     *
     * @return result
     */
    @Override
    public String canonicalType() {
        return "jiuwen.card";
    }

    /**
     * aliases.
     *
     * @return result
     */
    @Override
    public Set<String> aliases() {
        return Set.of("jiuwen.flowCard");
    }

    /**
     * create.
     *
     * @param node node
     * @param ctx ctx
     * @return result
     */
    @Override
    public ComponentExecutable create(AssembledNode node, NodeBuildContext ctx) {
        return new CardExecutable(node);
    }

    static final class CardExecutable extends AbstractStudioNode {
        CardExecutable(AssembledNode node) {
            super(node);
        }

        /**
         * doInvoke.
         *
         * @param inputs inputs
         * @param session session
         * @param context context
         * @return result
         */
        @Override
        protected NodePayload doInvoke(Map<String, Object> inputs, NodeSessionApi session, ModelContext context) {
            Map<String, Object> uf = new LinkedHashMap<>(userFieldsOf(inputs));
            Object card = node.configs().getOrDefault("card", node.configs().get("cardConfig"));
            if (card == null) {
                card = node.configs();
            }
            uf.put("card", card);
            uf.put("componentType", "card");
            if (session != null) {
                try {
                    Map<String, Object> stream = new LinkedHashMap<>();
                    stream.put("answer", card);
                    stream.put("result", card);
                    stream.put("card", card);
                    stream.put("node_id", node.id());
                    stream.put("node_type", "jiuwen.card");
                    stream.put("should_interrupt", false);
                    StudioStreamFrames.emitPartialAndMessageEnd(session, stream);
                    Map<String, Object> frame = new LinkedHashMap<>();
                    frame.put("type", "jiuwen.card");
                    frame.put("event", "card");
                    frame.put("card", card);
                    frame.put("nodeId", node.id());
                    session.writeCustomStream(frame);
                } catch (IllegalStateException
                        | NullPointerException
                        | ClassCastException
                        | UnsupportedOperationException ignored) {
                    // mock session
                }
            }
            return NodePayload.userFields(uf).withMediaPassthrough(PassthroughStudioNode.extractMedia(inputs));
        }
    }
}
