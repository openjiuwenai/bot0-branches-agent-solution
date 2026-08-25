/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.adapter.control;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.adapter.AbstractStudioNode;
import com.openjiuwen.studio.dsl.contract.NodeHandlerFactory;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.NodeCauseCode;
import com.openjiuwen.studio.dsl.model.NodePayload;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * jiuwen.aggregate — first-non-null (Python flow_aggregate).
 *
 * @since 2026-08-17
 */
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
        Map<String, Object> configs = node.configs();
        if (configs == null || configs.isEmpty()) {
            throw new NodeExecutionException(
                    node.id(), "jiuwen.aggregate", NodeCauseCode.NODE_CONFIG_INVALID, "conf is required");
        }
        return new AggregateExecutable(node);
    }

    static final class AggregateExecutable extends AbstractStudioNode {
        AggregateExecutable(AssembledNode node) {
            super(node);
        }

        @Override
        protected NodePayload doInvoke(Map<String, Object> inputs, NodeSessionApi session, ModelContext context) {
            String mode = String.valueOf(node.configs().getOrDefault("mode", "first-non-null"));
            if (!"first-non-null".equals(mode)) {
                throw new NodeExecutionException(
                        node.id(),
                        "jiuwen.aggregate",
                        NodeCauseCode.NODE_CONFIG_INVALID,
                        "unsupported aggregate mode: " + mode);
            }
            Map<String, Object> uf = userFieldsOf(inputs);

            Map<String, List<String>> groups = groupsMap(node.configs());
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> e : groups.entrySet()) {
                List<Object> values = new ArrayList<>();
                for (String key : e.getValue()) {
                    values.add(uf.get(key));
                }
                validateParamTypes(values);
                out.put(e.getKey(), firstNonEmpty(values));
            }
            if (groups.isEmpty()) {
                out.putAll(uf);
            }
            return NodePayload.userFields(out);
        }

        @SuppressWarnings("unchecked")
        private static Map<String, List<String>> groupsMap(Map<String, Object> configs) {
            Object raw = configs.get("groups");
            Map<String, List<String>> normalized = new LinkedHashMap<>();
            if (raw instanceof Map<?, ?> map) {
                map.forEach((k, v) -> {
                    List<String> keys = new ArrayList<>();
                    if (v instanceof List<?> list) {
                        for (Object x : list) {
                            keys.add(String.valueOf(x));
                        }
                    } else if (v != null) {
                        keys.add(String.valueOf(v));
                    }
                    normalized.put(String.valueOf(k), keys);
                });
            } else if (raw instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> g) {
                        String id = String.valueOf(g.get("id"));
                        Object vl = g.get("value_list");
                        if (vl == null) {
                            vl = g.get("valueList");
                        }
                        List<String> keys = new ArrayList<>();
                        if (vl instanceof List<?> vs) {
                            for (Object x : vs) {
                                keys.add(String.valueOf(x));
                            }
                        }
                        normalized.put(id, keys);
                    }
                }
            }
            return normalized;
        }

        private void validateParamTypes(List<Object> lst) {
            List<Object> nonNull = new ArrayList<>();
            for (Object item : lst) {
                if (item != null) {
                    nonNull.add(item);
                }
            }
            if (nonNull.isEmpty()) {
                return;
            }
            Object first = nonNull.get(0);
            if (first instanceof Map<?, ?>) {
                validateDictValueTypes(nonNull);
                return;
            }
            Class<?> type = first.getClass();
            for (Object item : nonNull) {
                if (!type.isInstance(item) && !item.getClass().equals(type)) {
                    // allow Number widen? Python is strict isinstance type(non_null[0])
                    if (!(first instanceof Number && item instanceof Number)) {
                        throw new NodeExecutionException(
                                node.id(),
                                "jiuwen.aggregate",
                                NodeCauseCode.NODE_CONFIG_INVALID,
                                "group value types differ");
                    }
                }
            }
        }

        @SuppressWarnings("unchecked")
        private void validateDictValueTypes(List<Object> lst) {
            Map<?, ?> template = (Map<?, ?>) lst.get(0);
            for (Object item : lst) {
                if (!(item instanceof Map<?, ?> map)) {
                    throw new NodeExecutionException(
                            node.id(),
                            "jiuwen.aggregate",
                            NodeCauseCode.NODE_CONFIG_INVALID,
                            "group value types differ");
                }
                for (Object key : template.keySet()) {
                    if (!map.containsKey(key)) {
                        throw new NodeExecutionException(
                                node.id(),
                                "jiuwen.aggregate",
                                NodeCauseCode.NODE_CONFIG_INVALID,
                                "dict group key '" + key + "' type mismatch");
                    }
                    Object tv = template.get(key);
                    Object iv = map.get(key);
                    if (tv != null && iv != null && !tv.getClass().equals(iv.getClass())) {
                        throw new NodeExecutionException(
                                node.id(),
                                "jiuwen.aggregate",
                                NodeCauseCode.NODE_CONFIG_INVALID,
                                "dict group key '" + key + "' type mismatch");
                    }
                }
            }
        }

        private static Object firstNonEmpty(List<Object> lst) {
            if (lst == null || lst.isEmpty()) {
                return null;
            }
            for (Object x : lst) {
                if (x instanceof String s) {
                    if (!s.isEmpty()) {
                        return x;
                    }
                } else if (x != null) {
                    return x;
                }
            }
            return null;
        }
    }
}
