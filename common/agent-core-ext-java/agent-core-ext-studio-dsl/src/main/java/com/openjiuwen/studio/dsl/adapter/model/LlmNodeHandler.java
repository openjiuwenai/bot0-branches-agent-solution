/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.adapter.model;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.adapter.AbstractStudioNode;
import com.openjiuwen.studio.dsl.contract.NodeHandlerFactory;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.llmchain.LlmChainConfig;
import com.openjiuwen.studio.dsl.llmchain.LlmChainEngine;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.NodePayload;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * jiuwen.LLMComponent — strict 1:1 with Python {@code llm_chain.LLMChain}.
 *
 * @since 2026-08-17
 */

public final class LlmNodeHandler implements NodeHandlerFactory {

    /**
     * canonicalType.
     *
     * @return result
     * @since 0.1.0
     */

    @Override
    public String canonicalType() {
        return "jiuwen.LLMComponent";
    }

    /**
     * aliases.
     *
     * @return result
     * @since 0.1.0
     */

    @Override
    public Set<String> aliases() {
        return Set.of("jiuwen.llm", "jiuwen.llm_chain", "jiuwen.llmChain");
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
        return new LlmChainExecutable(node, ctx);
    }
    static final class LlmChainExecutable extends AbstractStudioNode {
        private final LlmChainEngine engine;
        private final Map<String, Object> nodeConfigs;
        private volatile boolean ready;

        LlmChainExecutable(AssembledNode node, NodeBuildContext ctx) {
            super(node);
            this.nodeConfigs = node.configs() == null ? Map.of() : node.configs();
            LlmChainEngine.ModelBridge bridge =
                    ctx != null && ctx.testOverrides() != null ? ctx.testOverrides().llmBridge() : null;
            if (bridge != null) {
                this.engine = new LlmChainEngine(node.id(), LlmChainConfig.from(node.id(), nodeConfigs), bridge);
                this.ready = true;
            } else {
                this.engine = new LlmChainEngine(node.id());
            }
        }

        LlmChainEngine engine() {
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
            ensureInit();
            Map<String, Object> result = engine.invoke(inputs, session, context);
            return NodePayload.ofFields(result);
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
            ensureInit();
            Map<String, Object> in = asMap(inputs);
            return engine.stream(in, session, context);
        }

        private void ensureInit() {
            if (ready) {
            return;
        }
            synchronized (this) {
                if (!ready) {
                    engine.init(new LinkedHashMap<>(nodeConfigs));
                    ready = true;
                }
            }
        }
    }
}
