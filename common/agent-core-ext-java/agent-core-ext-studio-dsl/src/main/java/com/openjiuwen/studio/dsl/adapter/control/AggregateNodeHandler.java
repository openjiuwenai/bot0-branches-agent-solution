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
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.flowaggregate.FlowAggregateConfig;
import com.openjiuwen.studio.dsl.flowaggregate.FlowAggregateEngine;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.NodeCauseCode;
import com.openjiuwen.studio.dsl.model.NodePayload;

import java.util.Map;
import java.util.Set;

/**
 * jiuwen.aggregate — strict 1:1 with Python {@code flow_aggregate.py} ({@code Aggregate}).
 *
 * @since 2026-08-17
 */
public final class AggregateNodeHandler implements NodeHandlerFactory {
    @Override
    public String canonicalType() {
        return "jiuwen.aggregate";
    }

    @Override
    public Set<String> aliases() {
        return Set.of("jiuwen.aggregation", "jiuwen.flowAggregate");
    }

    @Override
    public ComponentExecutable create(AssembledNode node, NodeBuildContext ctx) {
        try {
            FlowAggregateConfig config = FlowAggregateConfig.fromNodeConfigs(node.configs());
            return new AggregateExecutable(node, new FlowAggregateEngine(node.id(), config));
        } catch (IllegalArgumentException e) {
            throw new NodeExecutionException(
                    node.id(), "jiuwen.aggregate", NodeCauseCode.NODE_CONFIG_INVALID, e.getMessage(), e);
        }
    }

    static final class AggregateExecutable extends AbstractStudioNode {
        private final FlowAggregateEngine engine;

        AggregateExecutable(AssembledNode node, FlowAggregateEngine engine) {
            super(node);
            this.engine = engine;
        }

        @Override
        protected NodePayload doInvoke(Map<String, Object> inputs, NodeSessionApi session, ModelContext context) {
            return NodePayload.ofFields(engine.invoke(inputs, session));
        }

        @Override
        public Object collect(Object inputs, NodeSessionApi session, ModelContext context) {
            return engine.collect(inputs, session);
        }
    }
}
