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
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.flowmessage.FlowMessageConfig;
import com.openjiuwen.studio.dsl.flowmessage.FlowMessageEngine;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.NodeCauseCode;
import com.openjiuwen.studio.dsl.model.NodePayload;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * jiuwen.message — strict 1:1 with Python {@code jiuwen/extension/workflow_node/flow_message.py}.
 *
 * @since 2026-08-17
 */

public final class MessageNodeHandler implements NodeHandlerFactory {

    /**
     * canonicalType.
     *
     * @return result
     * @since 0.1.0
     */

    @Override
    public String canonicalType() {
        return "jiuwen.message";
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
        FlowMessageConfig config = FlowMessageConfig.fromNodeConfigs(node.configs());
        if (config.template() == null || config.template().isBlank()) {
            throw new NodeExecutionException(
                    node.id(),
                    "jiuwen.message",
                    NodeCauseCode.NODE_CONFIG_INVALID,
                    "conf.template is required and must be non-empty");
        }
        return new MessageExecutable(node, config);
    }

    static final class MessageExecutable extends AbstractStudioNode {
        private final FlowMessageEngine engine;

        MessageExecutable(AssembledNode node, FlowMessageConfig config) {
            super(node);
            this.engine = new FlowMessageEngine(node.id(), config);
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
            // Python invoke → {"result": final_res} only.
            return NodePayload.ofFields(engine.invoke(inputs, session));
        }

        /**
         * stream.
         *
         * @param inputs inputs
         * @param session session
         * @param context context
         * @return result
         * @since 0.1.0
         */

        @Override
        public Iterator<Object> stream(Object inputs, NodeSessionApi session, ModelContext context) {
            return engine.stream(asMap(inputs), session);
        }

        /**
         * collect.
         *
         * @param inputs inputs
         * @param session session
         * @param context context
         * @return result
         * @since 0.1.0
         */

        @Override
        public Object collect(Object inputs, NodeSessionApi session, ModelContext context) {
            return engine.collect(inputs, session);
        }

        /**
         * transform.
         *
         * @param inputs inputs
         * @param session session
         * @param context context
         * @return result
         * @since 0.1.0
         */

        @Override
        public Iterator<Object> transform(Object inputs, NodeSessionApi session, ModelContext context) {
            return engine.transform(inputs, session);
        }
    }
}
