/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.adapter.control;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.core.workflow.component.End;
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
 * jiuwen.end — adapt core End when responseTemplate present; else Studio terminal shape.
 *
 * @since 2026-08-17
 */
public final class EndNodeHandler implements NodeHandlerFactory {
    /**
     * canonicalType.
     */
    @Override
    public String canonicalType() {
        return "jiuwen.end";
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
        Map<String, Object> configs = node.configs();
        Object template = configs.getOrDefault("responseTemplate", configs.get("template"));
        if (template != null && !String.valueOf(template).isBlank()) {
            try {
                return new DelegatingStudioNode(node, new End(configs));
            } catch (IllegalStateException
                | NullPointerException
                | ClassCastException
                | UnsupportedOperationException ignored) {
                // fall through to lightweight end
            }
        }
        return new EndExecutable(node);
    }

    static final class EndExecutable extends AbstractStudioNode {
        EndExecutable(AssembledNode node) {
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
            uf.put("__terminal__", true);
            return NodePayload.userFields(uf);
        }
    }
}
