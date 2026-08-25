/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.adapter.interact;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.adapter.AbstractStudioNode;
import com.openjiuwen.studio.dsl.adapter.DelegatingStudioNode;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.NodePayload;
import com.openjiuwen.studio.dsl.spi.NodeHandlerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * jiuwen.questioner — interaction node (FEAT-031 §5.1 / L2 Interaction group).
 * Prefers core QuestionerExecutable when model wired; else session.interact hang (FEAT-008 INPUT_REQUIRED).
 *
 * @since 2026-08-17
 */
public final class QuestionerNodeHandler implements NodeHandlerFactory {
    /**
     * canonicalType.
     */
    @Override
    public String canonicalType() {
        return "jiuwen.questioner";
    }
    /**
     * aliases.
     */
    @Override
    public Set<String> aliases() {
        return Set.of();
    }
    /**
     * create.
     * @param node node
     * @param ctx ctx
     */
    @Override
    public ComponentExecutable create(AssembledNode node, NodeBuildContext ctx) {
        if (ctx.coreExecutableFactory() != null) {
            return ctx.coreExecutableFactory()
                    .createQuestioner(node)
                    .map(core -> (ComponentExecutable) new DelegatingStudioNode(node, core))
                    .orElseGet(() -> new QuestionerFallback(node));
        }
        return new QuestionerFallback(node);
    }

    static final class QuestionerFallback extends AbstractStudioNode {
        QuestionerFallback(AssembledNode node) {
            super(node);
        }
        /**
         * doInvoke.
         * @param inputs inputs
         * @param session session
         * @param context context
         */
        @Override
        protected NodePayload doInvoke(Map<String, Object> inputs, NodeSessionApi session, ModelContext context) {
            Map<String, Object> uf = new LinkedHashMap<>(userFieldsOf(inputs));
            if (uf.containsKey("answer") || uf.containsKey("userAnswer")) {
                uf.put("questionerState", "answered");
                uf.put("hangState", "Continue");
                return NodePayload.userFields(uf);
            }
            Object question = node.configs().getOrDefault("question", node.configs().get("prompt"));
            uf.put("question", question);
            uf.put("questionerState", "INPUT_REQUIRED");
            uf.put("hangState", "INPUT_REQUIRED");

            if (session != null) {
                try {
                    Map<String, Object> hang = new LinkedHashMap<>();
                    hang.put("hangState", "INPUT_REQUIRED");
                    hang.put("questionerState", "INPUT_REQUIRED");
                    hang.put("question", question);
                    hang.put("nodeId", node.id());
                    hang.put("feat008", "INPUT_REQUIRED");
                    session.updateState(hang);
                } catch (IllegalStateException
                | NullPointerException
                | ClassCastException
                | UnsupportedOperationException ignored) {
                    // mock session
                }
                try {
                    Map<String, Object> ask = new LinkedHashMap<>();
                    ask.put("type", "INPUT_REQUIRED");
                    ask.put("question", question);
                    ask.put("nodeId", node.id());
                    Object reply = session.interact(ask);
                    if (reply != null) {
                        if (reply instanceof Map<?, ?> m) {
                            m.forEach((k, v) -> uf.put(String.valueOf(k), v));
                        } else {
                            uf.put("answer", reply);
                        }
                        uf.put("questionerState", "answered");
                        uf.put("hangState", "Continue");
                        try {
                            session.updateState(Map.of("hangState", "Continue", "questionerState", "answered"));
                        } catch (IllegalStateException
                | NullPointerException
                | ClassCastException
                | UnsupportedOperationException ignored) {
                            // mock session
                        }
                    }
                } catch (IllegalStateException
                | NullPointerException
                | ClassCastException
                | UnsupportedOperationException ignored) {
                    // mock / no hang support — leave INPUT_REQUIRED fields
                }
            }
            return NodePayload.userFields(uf);
        }
    }
}
