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
import com.openjiuwen.studio.dsl.flowsetvariable.FlowSetVariableConfig;
import com.openjiuwen.studio.dsl.flowsetvariable.FlowSetVariableEngine;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.NodePayload;
import com.openjiuwen.studio.dsl.store.ConversationValsStores;

import java.util.Map;
import java.util.Set;

/**
 * jiuwen.setVariable — strict 1:1 with Python {@code loop_set_variable.py} ({@code LoopSetVariable}).
 *
 * @since 2026-08-17
 */

public final class SetVariableNodeHandler implements NodeHandlerFactory {

    /**
     * canonicalType.
     *
     * @return result
     * @since 0.1.0
     */

    @Override
    public String canonicalType() {
        return "jiuwen.setVariable";
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
        FlowSetVariableConfig config = FlowSetVariableConfig.fromNodeConfigs(node.configs());
        FlowSetVariableEngine engine =
                new FlowSetVariableEngine(
                        config, ctx.variableScope(), ctx.workflowId(), ConversationValsStores.get());
        return new SetVariableExecutable(node, engine);
    }

    static final class SetVariableExecutable extends AbstractStudioNode {
        private final FlowSetVariableEngine engine;

        SetVariableExecutable(AssembledNode node, FlowSetVariableEngine engine) {
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
            engine.invoke(inputs, session);
            // Python LoopSetVariable.invoke → None
            return NodePayload.ofFields(Map.of());
        }
    }
}
