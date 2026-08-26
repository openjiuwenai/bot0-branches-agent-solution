/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.adapter.model;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.adapter.AbstractStudioNode;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.extractor.ExtractorEngine;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.NodePayload;
import com.openjiuwen.studio.dsl.contract.NodeHandlerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * jiuwen.extractor — strict 1:1 with Python {@code flow_extractor.py}.
 *
 * @since 2026-08-17
 */
public final class ExtractorNodeHandler implements NodeHandlerFactory {
    /**
     * canonicalType.
     *
     * @return result
     */
    @Override
    public String canonicalType() {
        return "jiuwen.extractor";
    }

    /**
     * aliases.
     *
     * @return result
     */
    @Override
    public Set<String> aliases() {
        return Set.of("jiuwen.infoExtraction");
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
        return new ExtractorExecutable(node);
    }

    static final class ExtractorExecutable extends AbstractStudioNode {
        private final ExtractorEngine engine;
        private final Map<String, Object> nodeConfigs;

        ExtractorExecutable(AssembledNode node) {
            super(node);
            this.engine = new ExtractorEngine(node.id());
            this.nodeConfigs = node.configs() == null ? Map.of() : node.configs();
        }

        /**
         * doInvoke.
         *
         * @param inputs inputs
         * @param session session
         * @param context context
         * @return result
         * @throws Exception when the call fails
         */
        @Override
        protected NodePayload doInvoke(Map<String, Object> inputs, NodeSessionApi session, ModelContext context)
                throws Exception {
            Map<String, Object> invokeInputs = inputs == null ? new LinkedHashMap<>() : new LinkedHashMap<>(inputs);
            if (!nodeConfigs.isEmpty() && !invokeInputs.containsKey("config")) {
                invokeInputs.put("config", nodeConfigs);
            }
            return NodePayload.userFields(engine.invoke(invokeInputs, session, context));
        }
    }
}
