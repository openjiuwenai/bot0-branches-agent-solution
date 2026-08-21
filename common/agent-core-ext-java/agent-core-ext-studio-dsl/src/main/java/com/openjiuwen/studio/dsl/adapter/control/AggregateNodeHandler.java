package com.openjiuwen.studio.dsl.adapter.control;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.adapter.AbstractStudioNode;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.NodePayload;
import com.openjiuwen.studio.dsl.spi.NodeHandlerFactory;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** first-non-null aggregate aligned with Studio Aggregate. */
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
        return new AggregateExecutable(node);
    }

    static final class AggregateExecutable extends AbstractStudioNode {
        AggregateExecutable(AssembledNode node) {
            super(node);
        }

        @Override
        @SuppressWarnings("unchecked")
        protected NodePayload doInvoke(Map<String, Object> inputs, NodeSessionApi session, ModelContext context) {
            Map<String, Object> uf = userFieldsOf(inputs);
            Map<String, Object> out = new LinkedHashMap<>();
            Object groups = node.configs().get("groups");
            if (groups instanceof Map<?, ?> map) {
                map.forEach((k, v) -> out.put(String.valueOf(k), firstNonNull(uf, v)));
            } else if (groups instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> g) {
                        String id = String.valueOf(g.get("id"));
                        out.put(id, firstNonNull(uf, g.get("value_list") != null ? g.get("value_list") : g.get("valueList")));
                    }
                }
            } else {
                out.putAll(uf);
            }
            return NodePayload.userFields(out);
        }

        private static Object firstNonNull(Map<String, Object> uf, Object keys) {
            if (keys instanceof List<?> list) {
                for (Object k : list) {
                    Object v = uf.get(String.valueOf(k));
                    if (v != null) {
                        return v;
                    }
                }
                return null;
            }
            if (keys != null) {
                return uf.get(String.valueOf(keys));
            }
            return null;
        }
    }
}
