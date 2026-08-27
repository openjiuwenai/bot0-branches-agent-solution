/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.adapter.interact;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.adapter.AbstractStudioNode;
import com.openjiuwen.studio.dsl.contract.NodeHandlerFactory;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.flowqa.FlowQaEngine;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.NodePayload;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * EI.qa — strict 1:1 with Python {@code flow_qa.FlowQA}.
 *
 * @since 2026-08-25
 */

public final class QaNodeHandler implements NodeHandlerFactory {

    /**
     * Exposed for {@link com.openjiuwen.studio.dsl.util.SessionStateIsolator}.
     */
    public static final String STATE_KEY = FlowQaEngine.STATE_KEY;

    /**
     * canonicalType.
     *
     * @return result
     * @since 0.1.0
     */

    @Override
    public String canonicalType() {
        return "EI.qa";
    }

    /**
     * aliases.
     *
     * @return result
     * @since 0.1.0
     */

    @Override
    public Set<String> aliases() {
        return Set.of("ei.qa", "jiuwen.flowQa");
    }

    /**
     * create.
     *
     * @param node node
     * @param ctx ctx
     * @return result
     * @since 0.1.0
     */

    @Override
    public ComponentExecutable create(AssembledNode node, NodeBuildContext ctx) {
        return new QaExecutable(node);
    }
    static final class QaExecutable extends AbstractStudioNode {
        private final FlowQaEngine engine;

        QaExecutable(AssembledNode node) {
            super(node);
            this.engine = FlowQaEngine.fromConfigs(node.id(), node.configs());
        }

        FlowQaEngine engine() {
            return engine;
        }

        /**
         * doInvoke.
         *
         * @param inputs inputs
         * @param session session
         * @param context context
         * @return result
         * @since 0.1.0
         */

        @Override
        protected NodePayload doInvoke(Map<String, Object> inputs, NodeSessionApi session, ModelContext context) {
            Map<String, Object> result = engine.invoke(inputs == null ? Map.of() : inputs, session, context);
            return NodePayload.ofFields(new LinkedHashMap<>(result));
        }
    }
}
