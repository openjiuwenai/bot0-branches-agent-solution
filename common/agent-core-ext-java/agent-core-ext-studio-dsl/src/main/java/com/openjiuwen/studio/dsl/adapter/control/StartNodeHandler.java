/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.adapter.control;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.adapter.AbstractStudioNode;
import com.openjiuwen.studio.dsl.contract.NodeHandlerFactory;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.flowstart.FlowStartConfig;
import com.openjiuwen.studio.dsl.flowstart.FlowStartEngine;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.NodePayload;
import com.openjiuwen.studio.dsl.store.ConversationValsStores;

import java.util.Map;
import java.util.Set;

/**
 * jiuwen.start — strict 1:1 with Python {@code jiuwen/extension/workflow_node/start.py}.
 *
 * @since 2026-08-17
 */

public final class StartNodeHandler implements NodeHandlerFactory {

    /**
     * canonicalType.
     *
     * @return result
     * @since 0.1.0
     */

    @Override
    public String canonicalType() {
        return "jiuwen.start";
    }

    /**
     * aliases.
     *
     * @return result
     * @since 0.1.0
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
     * @since 0.1.0
     */

    @Override
    public ComponentExecutable create(AssembledNode node, NodeBuildContext ctx) {
        FlowStartConfig config = FlowStartConfig.fromNodeConfigs(node.configs(), node.id());
        String buildWf = ctx == null ? null : ctx.workflowId();
        FlowStartEngine engine =
                new FlowStartEngine(node.id(), config, buildWf, ConversationValsStores.get());
        return new StartExecutable(node, engine);
    }

    static final class StartExecutable extends AbstractStudioNode {
        private final FlowStartEngine engine;

        StartExecutable(AssembledNode node, FlowStartEngine engine) {
            super(node);
            this.engine = engine;
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
            return NodePayload.ofFields(engine.invoke(inputs, session));
        }
    }
}
