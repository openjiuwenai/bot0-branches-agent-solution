package com.openjiuwen.studio.dsl.adapter;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.WorkflowComponent;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.NodeCauseCode;
import com.openjiuwen.studio.dsl.model.NodePayload;
import java.util.LinkedHashMap;
import java.util.Map;

/** Shared invoke → userFields wrapping for Studio DSL nodes. */
public abstract class AbstractStudioNode extends WorkflowComponent {
    protected final AssembledNode node;

    protected AbstractStudioNode(AssembledNode node) {
        this.node = node;
    }

    @Override
    public Object invoke(Object inputs, NodeSessionApi session, ModelContext context) {
        try {
            Map<String, Object> in = asMap(inputs);
            NodePayload out = doInvoke(in, session, context);
            return out.toInvokeMap();
        } catch (NodeExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new NodeExecutionException(
                    node.id(), node.canonicalType(), NodeCauseCode.NODE_INVOKE_FAILED, e.getMessage(), e);
        }
    }

    protected abstract NodePayload doInvoke(Map<String, Object> inputs, NodeSessionApi session, ModelContext context)
            throws Exception;

    @SuppressWarnings("unchecked")
    protected static Map<String, Object> asMap(Object inputs) {
        if (inputs == null) {
            return new LinkedHashMap<>();
        }
        if (inputs instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return new LinkedHashMap<>(Map.of("value", inputs));
    }

    protected static Map<String, Object> userFieldsOf(Map<String, Object> inputs) {
        Object uf = inputs.get("userFields");
        if (uf instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return new LinkedHashMap<>(inputs);
    }
}
