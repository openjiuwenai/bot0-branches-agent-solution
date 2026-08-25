/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.adapter.control;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.core.workflow.component.Start;
import com.openjiuwen.studio.dsl.adapter.AbstractStudioNode;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.NodePayload;
import com.openjiuwen.studio.dsl.spi.NodeHandlerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * jiuwen.start — prefer core Start; fill userFields/systemFields (Studio Start shape).
 *
 * @since 2026-08-17
 */
public final class StartNodeHandler implements NodeHandlerFactory {
    /**
     * canonicalType.
     *
     * @return result
     */
    @Override
    public String canonicalType() {
        return "jiuwen.start";
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
        return new StartExecutable(node);
    }

    static final class StartExecutable extends AbstractStudioNode {
        StartExecutable(AssembledNode node) {
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
            Map<String, Object> map = asMap(inputs);
            try {
                Object coreOut = new Start().invoke(inputs, session, context);
                if (coreOut != null) {
                    map = asMap(coreOut);
                }
            } catch (IllegalStateException
                | NullPointerException
                | ClassCastException
                | UnsupportedOperationException ignored) {
                // unit tests / missing session: keep Studio partition shape from inputs
            }
            Map<String, Object> uf = userFieldsOf(map);
            Map<String, Object> sf = new LinkedHashMap<>();
            Object sys = map.get("systemFields");
            if (sys instanceof Map<?, ?> m) {
                m.forEach((k, v) -> sf.put(String.valueOf(k), v));
            }
            Map<String, Object> wrap = new LinkedHashMap<>();
            wrap.put("userFields", uf);
            wrap.put("systemFields", sf);
            return NodePayload.ofFields(wrap);
        }
    }
}
