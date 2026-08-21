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
import com.openjiuwen.studio.dsl.spi.AgentInvoker;
import com.openjiuwen.studio.dsl.spi.NodeHandlerFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** jiuwen.agent — AgentInvoker SPI; A2A details FEAT-004. */
public final class AgentNodeHandler implements NodeHandlerFactory {
    @Override
    public String canonicalType() {
        return "jiuwen.agent";
    }

    @Override
    public Set<String> aliases() {
        return Set.of("jiuwen.flowAgent");
    }

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
            return o == null ? null : String.valueOf(o);
        }
    }
}
