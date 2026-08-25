/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.adapter.interact;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.adapter.AbstractStudioNode;
import com.openjiuwen.studio.dsl.adapter.PassthroughStudioNode;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.MediaPart;
import com.openjiuwen.studio.dsl.model.NodePayload;
import com.openjiuwen.studio.dsl.spi.NodeHandlerFactory;
import com.openjiuwen.studio.dsl.util.TemplateRenderer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * jiuwen.message — template render + session.writeStream / writeCustomStream (Studio FlowMessage).
 *
 * @since 2026-08-17
 */
public final class MessageNodeHandler implements NodeHandlerFactory {

    /**
     * canonicalType.
     *
     * @return result
     */
    @Override
    public String canonicalType() {
        return "jiuwen.message";
    }

    /**
     * aliases.
     *
     * @return result
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
     */
    @Override
    public ComponentExecutable create(AssembledNode node, NodeBuildContext ctx) {
        return new MessageExecutable(node);
    }

    static final class MessageExecutable extends AbstractStudioNode {
        MessageExecutable(AssembledNode node) {
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
            Map<String, Object> uf = userFieldsOf(inputs);
            String template = firstString(
                    node.configs().get("message"),
                    node.configs().get("content"),
                    node.configs().get("text"),
                    uf.get("message"));
            String rendered = TemplateRenderer.render(template, uf);
            Map<String, Object> out = new LinkedHashMap<>(uf);
            out.put("result", rendered);
            List<Map<String, Object>> outputs = new ArrayList<>();
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("result", rendered);
            rec.put("componentType", "message");
            rec.put("nodeId", node.id());
            outputs.add(rec);
            out.put("message_outputs", outputs);

            emit(session, rendered, rec);

            List<MediaPart> media = PassthroughStudioNode.extractMedia(inputs);
            return NodePayload.userFields(out).withMediaPassthrough(media);
        }

        private void emit(NodeSessionApi session, String rendered, Map<String, Object> rec) {
            if (session == null) {
                return;
            }
            try {
                Map<String, Object> frame = new LinkedHashMap<>();
                frame.put("type", "jiuwen.message");
                frame.put("event", "message");
                frame.put("content", rendered);
                frame.put("payload", rec);
                session.writeCustomStream(frame);
            } catch (IllegalStateException
                | NullPointerException
                | ClassCastException
                | UnsupportedOperationException ignored) {
                try {
                    session.writeStream(rendered);
                } catch (IllegalStateException
                | NullPointerException
                | ClassCastException
                | UnsupportedOperationException ignored2) {
                    // session may be mock in unit tests
                }
            }
        }

        private static String firstString(Object a, Object b, Object c, Object d) {
            if (a != null && !String.valueOf(a).isBlank()) {
                return String.valueOf(a);
            }
            if (b != null && !String.valueOf(b).isBlank()) {
                return String.valueOf(b);
            }
            if (c != null && !String.valueOf(c).isBlank()) {
                return String.valueOf(c);
            }
            if (d != null && !String.valueOf(d).isBlank()) {
                return String.valueOf(d);
            }
            return "";
        }
    }
}
