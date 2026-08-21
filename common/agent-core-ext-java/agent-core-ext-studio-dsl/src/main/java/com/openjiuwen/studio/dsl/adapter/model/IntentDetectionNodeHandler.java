package com.openjiuwen.studio.dsl.adapter.model;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.adapter.AbstractStudioNode;
import com.openjiuwen.studio.dsl.adapter.DelegatingStudioNode;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.NodePayload;
import com.openjiuwen.studio.dsl.spi.NodeHandlerFactory;
import com.openjiuwen.studio.dsl.util.ConditionEvaluator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** jiuwen.intentDetection — classify via intents list rules or core bridge (编排归 029). */
public final class IntentDetectionNodeHandler implements NodeHandlerFactory {
    @Override
    public String canonicalType() {
        return "jiuwen.intentDetection";
    }

    @Override
    public Set<String> aliases() {
        return Set.of();
    }

    @Override
    public ComponentExecutable create(AssembledNode node, NodeBuildContext ctx) {
        if (ctx.coreExecutableFactory() != null) {
            ComponentExecutable core = ctx.coreExecutableFactory().createIntentDetection(node);
            if (core != null) {
                return new DelegatingStudioNode(node, core);
            }
        }
        return new IntentExecutable(node);
    }

    static final class IntentExecutable extends AbstractStudioNode {
        IntentExecutable(AssembledNode node) {
            super(node);
        }

        @Override
        @SuppressWarnings("unchecked")
        protected NodePayload doInvoke(Map<String, Object> inputs, NodeSessionApi session, ModelContext context) {
            Map<String, Object> uf = new LinkedHashMap<>(userFieldsOf(inputs));
            String query = String.valueOf(uf.getOrDefault("query", uf.getOrDefault("text", "")));
            String selected = "default";
            Object intents = node.configs().getOrDefault("intents", node.configs().get("intentList"));
            if (intents instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> m) {
                        String id = String.valueOf(first(m, "intentId", "id", "name", "default"));
                        Object cond = m.get("condition");
                        Object keywords = m.get("keywords");
                        if (cond != null && ConditionEvaluator.matches(cond, uf)) {
                            selected = id;
                            break;
                        }
                        if (keywords instanceof List<?> kws) {
                            for (Object kw : kws) {
                                if (query.contains(String.valueOf(kw))) {
                                    selected = id;
                                    break;
                                }
                            }
                            if (!"default".equals(selected)) {
                                break;
                            }
                        }
                    }
                }
            }
            uf.put("intent", selected);
            uf.put("intentId", selected);
            uf.put("__branchId__", selected);
            return NodePayload.userFields(uf);
        }

        private static Object first(Map<?, ?> m, String a, String b, String c, Object def) {
            Object v = m.get(a);
            if (v == null) v = m.get(b);
            if (v == null) v = m.get(c);
            return v != null ? v : def;
        }
    }
}
