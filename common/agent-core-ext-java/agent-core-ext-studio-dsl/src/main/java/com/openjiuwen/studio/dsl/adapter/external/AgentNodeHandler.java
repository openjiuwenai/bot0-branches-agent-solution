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
import com.openjiuwen.studio.dsl.contract.AgentInvoker;
import com.openjiuwen.studio.dsl.contract.NodeHandlerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * jiuwen.agent — AgentInvoker contract; A2A details FEAT-004.
 *
 * @since 2026-08-17
 */
public final class AgentNodeHandler implements NodeHandlerFactory {
    /**
     * canonicalType.
     *
     * @return result
     */
    @Override
    public String canonicalType() {
        return "jiuwen.agent";
    }

    /**
     * aliases.
     *
     * @return result
     */
    @Override
    public Set<String> aliases() {
        return Set.of("jiuwen.flowAgent");
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
        return new AgentExecutable(node, ctx.agentInvoker());
    }

    static final class AgentExecutable extends AbstractStudioNode {
        private final AgentInvoker invoker;

        AgentExecutable(AssembledNode node, AgentInvoker invoker) {
            super(node);
            this.invoker = invoker;
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
            Map<String, Object> uf = new LinkedHashMap<>(userFieldsOf(inputs));
            if (node.configs().containsKey("mockResponse")) {
                Object mock = node.configs().get("mockResponse");
                if (mock instanceof Map<?, ?> m) {
                    m.forEach((k, v) -> uf.put(String.valueOf(k), v));
                } else {
                    uf.put("agentResult", mock);
                }
                return NodePayload.userFields(uf);
            }
            if (invoker == null) {
                throw new NodeExecutionException(
                        node.id(), "jiuwen.agent", NodeCauseCode.NODE_CONFIG_INVALID, "AgentInvoker not configured");
            }
            String agentId = str(node.configs().getOrDefault("agentId", node.configs().get("agent")));
            Map<String, Object> result = invoker.invoke(agentId, uf);
            uf.putAll(result);
            uf.put("agentResult", result);
            return NodePayload.userFields(uf);
        }

        private static String str(Object o) {
            return o == null ? "" : String.valueOf(o);
        }
    }
}
