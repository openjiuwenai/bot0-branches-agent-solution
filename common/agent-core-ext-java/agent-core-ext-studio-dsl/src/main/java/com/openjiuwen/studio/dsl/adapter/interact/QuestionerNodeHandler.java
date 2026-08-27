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
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.NodePayload;
import com.openjiuwen.studio.dsl.questioner.QuestionerConfig;
import com.openjiuwen.studio.dsl.questioner.QuestionerEngine;

import java.util.Map;
import java.util.Set;

/**
 * jiuwen.questioner — strict 1:1 with
 * {@code agent_runtime/.../workflow_node/questioner.py}
 * (LLM extract + reflection + rails + Redis Trace + INPUT_REQUIRED + ModelContext history).
 *
 * <p>Always uses {@link QuestionerEngine} (studio path). Core
 * {@code QuestionerExecutable} lacks Redis Trace / reflection / rails.
 *
 * @since 2026-08-17
 */

public final class QuestionerNodeHandler implements NodeHandlerFactory {

    /**
     * canonicalType.
     *
     * @return result
     * @since 0.1.0
     */

    @Override
    public String canonicalType() {
        return "jiuwen.questioner";
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
        return new QuestionerExecutable(node);
    }
    static final class QuestionerExecutable extends AbstractStudioNode {
        QuestionerExecutable(AssembledNode node) {
            super(node);
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
            QuestionerConfig config = QuestionerConfig.fromNodeConfigs(node.configs());
            QuestionerEngine engine = new QuestionerEngine(node.id(), config);
            Map<String, Object> uf = engine.invoke(inputs, session, context);
            return NodePayload.userFields(uf);
        }
    }
}
