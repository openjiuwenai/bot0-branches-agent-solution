/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

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
import java.util.Optional;
import java.util.Set;

/**
 * first-non-null aggregate aligned with Studio Aggregate.
 *
 * @since 2026-08-17
 */
public final class AggregateNodeHandler implements NodeHandlerFactory {

    /**
     * canonicalType.
     *
     * @return result
     */
    @Override
    public String canonicalType() {
        return "jiuwen.aggregate";
    }

    /**
     * aliases.
     *
     * @return result
     */
    @Override
    public Set<String> aliases() {
        return Set.of("jiuwen.aggregation", "jiuwen.flowAggregate");
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
        return new AggregateExecutable(node);
    }

    static final class AggregateExecutable extends AbstractStudioNode {
        AggregateExecutable(AssembledNode node) {
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
            Map<String, Object> uf = userFieldsOf(inputs);
            Map<String, Object> out = new LinkedHashMap<>();
            Object groups = node.configs().get("groups");
            if (groups instanceof Map<?, ?> map) {
                map.forEach((k, v) -> out.put(String.valueOf(k), firstNonNull(uf, v).orElse(null)));
            } else if (groups instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> g) {
                        String id = String.valueOf(g.get("id"));
                        out.put(id, firstNonNull(uf, valueListOf(g)).orElse(null));
                    }
                }
            } else {
                out.putAll(uf);
            }
            return NodePayload.userFields(out);
        }

        private static Object valueListOf(Map<?, ?> g) {
            Object v = g.get("value_list");
            return v != null ? v : g.get("valueList");
        }

        private static Optional<Object> firstNonNull(Map<String, Object> uf, Object keys) {
            if (keys instanceof List<?> list) {
                for (Object k : list) {
                    Object v = uf.get(String.valueOf(k));
                    if (v != null) {
                        return Optional.of(v);
                    }
                }
                return Optional.empty();
            }
            if (keys != null) {
                return Optional.ofNullable(uf.get(String.valueOf(keys)));
            }
            return Optional.empty();
        }
    }
}
