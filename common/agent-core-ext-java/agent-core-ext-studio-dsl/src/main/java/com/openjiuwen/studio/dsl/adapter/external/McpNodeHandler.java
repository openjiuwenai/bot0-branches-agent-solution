/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.adapter.external;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.foundation.tool.mcp.McpClient;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.adapter.AbstractStudioNode;
import com.openjiuwen.studio.dsl.contract.NodeHandlerFactory;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.flowmcp.FlowMcpEngine;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.NodePayload;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * jiuwen.mcp — strict 1:1 with Python {@code flow_mcp.FlowMcp}.
 *
 * @since 2026-08-17
 */

public final class McpNodeHandler implements NodeHandlerFactory {

    /**
     * canonicalType.
     *
     * @return result
     * @since 0.1.0
     */

    @Override
    public String canonicalType() {
        return "jiuwen.mcp";
    }

    /**
     * aliases.
     *
     * @return result
     * @since 0.1.0
     */

    @Override
    public Set<String> aliases() {
        return Set.of("jiuwen.flowMcp", "jiuwen.flow_mcp");
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
        return new McpExecutable(node, ctx);
    }
    static final class McpExecutable extends AbstractStudioNode {
        private final FlowMcpEngine engine;
        private final Map<String, Object> nodeConfigs;
        private volatile boolean ready;

        McpExecutable(AssembledNode node, NodeBuildContext ctx) {
            super(node);
            McpClient testClient =
                    ctx != null && ctx.testOverrides() != null ? ctx.testOverrides().mcpClient() : null;
            this.engine = testClient != null ? new FlowMcpEngine(node.id(), testClient) : new FlowMcpEngine(node.id());
            this.nodeConfigs = node.configs() == null ? Map.of() : node.configs();
        }

        FlowMcpEngine engine() {
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
