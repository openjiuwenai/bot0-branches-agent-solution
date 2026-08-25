/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.adapter.control;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.adapter.AbstractStudioNode;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.NodePayload;
import com.openjiuwen.studio.dsl.spi.NodeHandlerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * jiuwen.exception — carry exception branch payload (Studio ExceptionInfo).
 *
 * @since 2026-08-17
 */
public final class ExceptionNodeHandler implements NodeHandlerFactory {
    /**
     * canonicalType.
     */
    @Override
    public String canonicalType() {
        return "jiuwen.exception";
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
        return new ExceptionExecutable(node);
    }

    static final class ExceptionExecutable extends AbstractStudioNode {
        ExceptionExecutable(AssembledNode node) {
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
            Map<String, Object> ex = new LinkedHashMap<>();
            Object incoming = inputs.get("exception");
            if (incoming instanceof Map<?, ?> m) {
                m.forEach((k, v) -> ex.put(String.valueOf(k), v));
            } else if (uf.containsKey("exception")) {
                Object e = uf.get("exception");
                if (e instanceof Map<?, ?> m) {
                    m.forEach((k, v) -> ex.put(String.valueOf(k), v));
                } else {
                    ex.put("message", String.valueOf(e));
                }
            } else {
                ex.put("message", uf.getOrDefault("error", uf.getOrDefault("message", "exception branch")));
            }
            Object code = node.configs().get("errorCode");
            if (code != null) {
                ex.putIfAbsent("errorCode", code);
            }
            uf.put("exception", ex);
            return NodePayload.userFields(uf);
        }
    }
}
