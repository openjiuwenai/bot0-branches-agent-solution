package com.openjiuwen.studio.dsl.adapter.control;

import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.core.workflow.component.End;
import com.openjiuwen.studio.dsl.adapter.AbstractStudioNode;
import com.openjiuwen.studio.dsl.adapter.DelegatingStudioNode;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.NodePayload;
import com.openjiuwen.studio.dsl.spi.NodeHandlerFactory;
import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** jiuwen.end — adapt core End when responseTemplate present; else Studio terminal shape. */
public final class EndNodeHandler implements NodeHandlerFactory {
    @Override
    public String canonicalType() {
        return "jiuwen.end";
    }

    @Override
    public Set<String> aliases() {
        return Set.of();
    }

    @Override
    public ComponentExecutable create(AssembledNode node, NodeBuildContext ctx) {
        Map<String, Object> configs = node.configs();
        Object template = configs.getOrDefault("responseTemplate", configs.get("template"));
        if (template != null && !String.valueOf(template).isBlank()) {
            try {
                return new DelegatingStudioNode(node, new End(configs));
            } catch (RuntimeException ignored) {
                // fall through to lightweight end
            }
        }
        return new EndExecutable(node);
    }

    static final class EndExecutable extends AbstractStudioNode {
        EndExecutable(AssembledNode node) {
            super(node);
        }

        @Override
        protected NodePayload doInvoke(Map<String, Object> inputs, NodeSessionApi session, ModelContext context) {
            Map<String, Object> uf = new LinkedHashMap<>(userFieldsOf(inputs));
            uf.put("__terminal__", true);
            return NodePayload.userFields(uf);
        }
    }
}
