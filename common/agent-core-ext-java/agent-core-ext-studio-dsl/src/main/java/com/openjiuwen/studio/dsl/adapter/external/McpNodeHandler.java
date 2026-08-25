/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.adapter.external;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.adapter.AbstractStudioNode;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.NodeCauseCode;
import com.openjiuwen.studio.dsl.model.NodePayload;
import com.openjiuwen.studio.dsl.spi.McpToolInvoker;
import com.openjiuwen.studio.dsl.spi.NodeHandlerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * jiuwen.mcp — invoke via McpToolInvoker SPI (Studio FlowMcp).
 *
 * @since 2026-08-17
 */
public final class McpNodeHandler implements NodeHandlerFactory {
    /**
     * canonicalType.
     */
    @Override
    public String canonicalType() {
        return "jiuwen.mcp";
    }
    /**
     * aliases.
     */
    @Override
    public Set<String> aliases() {
        return Set.of("jiuwen.flowMcp");
    }
    /**
     * create.
     * @param node node
     * @param ctx ctx
     */
    @Override
    public ComponentExecutable create(AssembledNode node, NodeBuildContext ctx) {
        return new McpExecutable(node, ctx.mcpToolInvoker());
    }

    static final class McpExecutable extends AbstractStudioNode {
        private final McpToolInvoker invoker;

        McpExecutable(AssembledNode node, McpToolInvoker invoker) {
            super(node);
            this.invoker = invoker;
        }
        /**
         * doInvoke.
         * @param inputs inputs
         * @param session session
         * @param context context
         * @throws Exception when the call fails
         */
        @Override
        @SuppressWarnings("unchecked")
        protected NodePayload doInvoke(Map<String, Object> inputs, NodeSessionApi session, ModelContext context)
                throws Exception {
            Map<String, Object> uf = new LinkedHashMap<>(userFieldsOf(inputs));
            if (node.configs().containsKey("mockResponse")) {
                Object mock = node.configs().get("mockResponse");
                if (mock instanceof Map<?, ?> m) {
                    m.forEach((k, v) -> uf.put(String.valueOf(k), v));
                } else {
                    uf.put("mcpResult", mock);
                }
                return NodePayload.userFields(uf);
            }
            if (invoker == null) {
                throw new NodeExecutionException(
                        node.id(), "jiuwen.mcp", NodeCauseCode.NODE_CONFIG_INVALID, "McpToolInvoker not configured");
            }
            String server = str(node.configs().getOrDefault("server", node.configs().get("mcpServer")));
            String tool = str(node.configs().getOrDefault("tool", node.configs().get("toolName")));
            Object args = node.configs().getOrDefault("arguments", node.configs().get("params"));
            Map<String, Object> argMap = args instanceof Map<?, ?> m ? cast(m) : uf;
            try {
                Map<String, Object> result = invoker.invoke(server, tool, argMap);
                uf.putAll(result);
                uf.put("mcpResult", result);
                return NodePayload.userFields(uf);
            } catch (IllegalStateException e) {
                throw new NodeExecutionException(
                        node.id(), "jiuwen.mcp", NodeCauseCode.NODE_CONFIG_INVALID, e.getMessage(), e);
            }
        }

        private static Map<String, Object> cast(Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }

        private static String str(Object o) {
            return o == null ? "" : String.valueOf(o);
        }
    }
}
