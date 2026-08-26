/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.adapter.external;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.adapter.AbstractStudioNode;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.flowagent.FlowAgentEngine;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.NodePayload;
import com.openjiuwen.studio.dsl.contract.NodeHandlerFactory;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * jiuwen.agent — strict 1:1 with Python {@code flow_agent.FlowAgent}.
 *
 * @since 2026-08-17
 */
public final class AgentNodeHandler implements NodeHandlerFactory {
    @Override
    public String canonicalType() {
        return "jiuwen.agent";
    }

    @Override
    public Set<String> aliases() {
        return Set.of("jiuwen.flowAgent", "jiuwen.flow_agent");
    }

    @Override
    public ComponentExecutable create(AssembledNode node, NodeBuildContext ctx) {
        return new FlowAgentExecutable(node, ctx);
    }

    static final class FlowAgentExecutable extends AbstractStudioNode {
        private final FlowAgentEngine engine;
        private final Map<String, Object> nodeConfigs;
        private final NodeBuildContext ctx;
        private volatile boolean ready;

        FlowAgentExecutable(AssembledNode node, NodeBuildContext ctx) {
            super(node);
            this.engine = new FlowAgentEngine(node.id());
            this.nodeConfigs = node.configs() == null ? Map.of() : node.configs();
            this.ctx = ctx;
        }

        FlowAgentEngine engine() {
            return engine;
        }

        @Override
        protected NodePayload doInvoke(Map<String, Object> inputs, NodeSessionApi session, ModelContext context) {
            ensureInit();
            Map<String, Object> result = engine.invoke(inputs, session, context);
            return NodePayload.ofFields(result);
        }

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
                    if (ctx != null) {
                        engine.setStudioToolRegistry(ctx.toolRegistry());
                    }
                    engine.init(new LinkedHashMap<>(nodeConfigs));
                    ready = true;
                }
            }
        }
    }
}
