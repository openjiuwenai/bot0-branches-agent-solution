/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

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

/**
 * jiuwen.intentDetection — classify via intents list rules or core bridge (编排归 029).
 *
 * @since 2026-08-17
 */
public final class IntentDetectionNodeHandler implements NodeHandlerFactory {
    /**
     * canonicalType.
     *
     * @return result
     */
    @Override
    public String canonicalType() {
        return "jiuwen.intentDetection";
    }

    /**
     * aliases.
     *
     * @return result
     */
    @Override
    public Set<String> aliases() {
        return Set.of();
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
        if (ctx.coreExecutableFactory() != null) {
            return ctx.coreExecutableFactory()
                    .createIntentDetection(node)
                    .<ComponentExecutable>map(core -> new DelegatingStudioNode(node, core))
                    .orElseGet(() -> new IntentExecutable(node));
        }
        return new IntentExecutable(node);
    }

    static final class IntentExecutable extends AbstractStudioNode {
        IntentExecutable(AssembledNode node) {
            super(node);
        }

        /**
         * doInvoke.
         *
         * @param inputs inputs
         * @param session session
         * @param context context
         * @return result
         */
        @Override
        @SuppressWarnings("unchecked")
        protected NodePayload doInvoke(Map<String, Object> inputs, NodeSessionApi session, ModelContext context) {
            Map<String, Object> uf = new LinkedHashMap<>(userFieldsOf(inputs));
            String query = String.valueOf(uf.getOrDefault("query", uf.getOrDefault("text", "")));
            String selected = "default";
            Object intents = node.configs().getOrDefault("intents", node.configs().get("intentList"));
            if (intents instanceof List<?> list) {
                selected = matchIntent(list, uf, query, selected);
            }
            uf.put("intent", selected);
            uf.put("intentId", selected);
            uf.put("__branchId__", selected);
            return NodePayload.userFields(uf);
        }

        private static String matchIntent(List<?> list, Map<String, Object> uf, String query, String selected) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> m)) {
                    continue;
                }
                String id = String.valueOf(first(m, "intentId", "id", "name", "default"));
                Object cond = m.get("condition");
                if (cond != null && ConditionEvaluator.matches(cond, uf)) {
                    return id;
                }
                if (keywordsHit(m.get("keywords"), query)) {
                    return id;
                }
            }
            return selected;
        }

        private static boolean keywordsHit(Object keywords, String query) {
            if (!(keywords instanceof List<?> kws)) {
                return false;
            }
            for (Object kw : kws) {
                if (query.contains(String.valueOf(kw))) {
                    return true;
                }
            }
            return false;
        }

        private static Object first(Map<?, ?> m, String a, String b, String c, Object def) {
            Object v = m.get(a);
            if (v == null) {
                v = m.get(b);
            }
            if (v == null) {
                v = m.get(c);
            }
            return v != null ? v : def;
        }
    }
}
