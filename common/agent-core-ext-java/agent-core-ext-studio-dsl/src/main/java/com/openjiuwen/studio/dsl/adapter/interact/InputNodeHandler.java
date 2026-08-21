package com.openjiuwen.studio.dsl.adapter.interact;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.adapter.AbstractStudioNode;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.NodePayload;
import com.openjiuwen.studio.dsl.spi.NodeHandlerFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** jiuwen.input — accept user input fields and mark received (Studio FlowInput). */
public final class InputNodeHandler implements NodeHandlerFactory {
    @Override
    public String canonicalType() {
        return "jiuwen.input";
    }

    @Override
    public Set<String> aliases() {
        return Set.of("jiuwen.flowInput");
    }

    @Override
    public ComponentExecutable create(AssembledNode node, NodeBuildContext ctx) {
        return new InputExecutable(node);
    }

    static final class InputExecutable extends AbstractStudioNode {
        InputExecutable(AssembledNode node) {
            super(node);
        }

        @Override
        protected NodePayload doInvoke(Map<String, Object> inputs, NodeSessionApi session, ModelContext context) {
            Map<String, Object> uf = new LinkedHashMap<>(userFieldsOf(inputs));
            Object expected = node.configs().get("fields");
            if (expected instanceof Map<?, ?> schema) {
                schema.forEach((k, v) -> uf.putIfAbsent(String.valueOf(k), null));
            }
            uf.put("inputReceived", true);
            return NodePayload.userFields(uf);
        }
    }
}
