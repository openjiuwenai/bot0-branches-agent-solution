/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.adapter.external;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.adapter.AbstractStudioNode;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.NodePayload;
import com.openjiuwen.studio.dsl.contract.NodeHandlerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * EI.ParamOutput — passthrough userFields (+ optional systemFields).
 *
 * @since 2026-08-25
 */
public final class ParamOutputNodeHandler implements NodeHandlerFactory {
    @Override
    public String canonicalType() {
        return "EI.ParamOutput";
    }

    @Override
    public Set<String> aliases() {
        return Set.of("ei.paramOutput", "ei.ParamOutput");
    }

    @Override
    public ComponentExecutable create(AssembledNode node, NodeBuildContext ctx) {
        return new ParamOutputExecutable(node);
    }

    static final class ParamOutputExecutable extends AbstractStudioNode {
        ParamOutputExecutable(AssembledNode node) {
            super(node);
        }

        @Override
        protected NodePayload doInvoke(Map<String, Object> inputs, NodeSessionApi session, ModelContext context) {
            Map<String, Object> wrap = new LinkedHashMap<>();
            wrap.put("userFields", new LinkedHashMap<>(userFieldsOf(inputs)));
            Object sys = inputs.get("systemFields");
            if (sys != null) {
                wrap.put("systemFields", sys);
            }
            return NodePayload.ofFields(wrap);
        }
    }
}
