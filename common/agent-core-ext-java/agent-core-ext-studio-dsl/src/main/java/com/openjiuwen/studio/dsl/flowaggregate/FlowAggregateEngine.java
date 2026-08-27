/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.flowaggregate;

import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.model.NodeCauseCode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Python {@code Aggregate} / {@code flow_aggregate.py} (strict 1:1).
 *
 * @since 2026-08-26
 */

public final class FlowAggregateEngine {
    public static final String USER_FIELDS = "userFields";

    private final String nodeId;
    private final FlowAggregateConfig config;

    public FlowAggregateEngine(String nodeId, FlowAggregateConfig config) {
        this.nodeId = nodeId == null ? "aggregate" : nodeId;
        this.config = config;
    }

    /**
     * Python {@code Aggregate.invoke} → {@code { "userFields": res }}.
     *
     * @param inputs inputs
     * @param session unused (parity signature)
     * @return fields map with userFields only
     */

    public Map<String, Object> invoke(Map<String, Object> inputs, NodeSessionApi session) {
        String mode = config.mode();
        Map<String, List<String>> groups = config.groups();
        Map<String, Object> user = extractUser(inputs);
        Map<String, Object> res = new LinkedHashMap<>();
        if (FlowAggregateConfig.DEFAULT_MODE.equals(mode)) {
            for (Map.Entry<String, List<String>> e : groups.entrySet()) {
                List<Object> valueList = new ArrayList<>();
                for (String item : e.getValue()) {
                    valueList.add(user.get(item));
                }
                validateParamType(valueList);
                res.put(e.getKey(), firstNonEmpty(valueList));
            }
        } else {
            throw inputInvalid("unsupported aggregate mode: " + mode);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(USER_FIELDS, res);
        return out;
    }

    /**
     * Python {@code Aggregate.collect}: resolve stream-like values then {@link #invoke}.
     *
     * @param inputs inputs
     * @param session session
     * @return same shape as invoke
     */

    public Map<String, Object> collect(Object inputs, NodeSessionApi session) {
        Map<String, Object> resolved = resolveStreamInputs(asMap(inputs));
        return invoke(resolved, session);
    }

    /**
     * * Python: {@code inner if (isinstance(inner, dict) and inner) else inputs}.
     *
     * @param inputs inputs
     * @return result
     * @since 0.1.0
     */
    public static Map<String, Object> extractUser(Map<String, Object> inputs) {
        if (inputs == null) {
            return Map.of();
        }
        Object inner = inputs.get(USER_FIELDS);
        if (inner instanceof Map<?, ?> m && !m.isEmpty()) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return new LinkedHashMap<>(inputs);
    }

    /**
     * * Python {@code _first_non_empty}.
     *
     * @param lst lst
     * @return result
     * @since 0.1.0
     */
    public static Object firstNonEmpty(List<Object> lst) {
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

    /**
     * * Python {@code _validate_param_type} (strict type equality; no Number widen).
     *
     * @param lst lst
     * @since 0.1.0
     */
    void validateParamType(List<Object> lst) {
        if (lst == null || lst.isEmpty()) {
        return;
    }
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
            if (!type.equals(item.getClass())) {
                throw inputInvalid("group value types differ");
            }
        }
    }

    /**
     * * Python {@code _validate_dict_value_types}.
     *
     * @param lst lst
     * @since 0.1.0
     */
    @SuppressWarnings("unchecked")
    void validateDictValueTypes(List<Object> lst) {
        Map<?, ?> template = (Map<?, ?>) lst.get(0);
        for (Object item : lst) {
            if (!(item instanceof Map<?, ?> map)) {
                throw inputInvalid("group value types differ");
            }
            for (Object key : template.keySet()) {
                if (!map.containsKey(key)) {
                    throw inputInvalid("dict group key '" + key + "' type mismatch");
                }
                Object tv = template.get(key);
                Object iv = map.get(key);
                if (tv == null) {
                    if (iv != null) {
                        throw inputInvalid("dict group key '" + key + "' type mismatch");
                    }
                } else if (iv == null || !tv.getClass().equals(iv.getClass())) {
                    throw inputInvalid("dict group key '" + key + "' type mismatch");
                }
            }
        }
    }

    /**
     * * Python {@code _resolve_stream_inputs} — Iterator / Iterable / Publisher as AsyncGenerator.
     *
     * @param inputs inputs
     * @return result
     * @since 0.1.0
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> resolveStreamInputs(Map<String, Object> inputs) {
        if (inputs == null) {
            return Map.of();
        }
        Object inner = inputs.get(USER_FIELDS);
        if (inner instanceof Map<?, ?> um) {
            Map<String, Object> resolvedInner = new LinkedHashMap<>();
            um.forEach((k, v) -> resolvedInner.put(String.valueOf(k), resolveOne(v)));
            Map<String, Object> resolved = new LinkedHashMap<>(inputs);
            resolved.put(USER_FIELDS, resolvedInner);
            return resolved;
        }
        Map<String, Object> resolved = new LinkedHashMap<>();
        inputs.forEach((k, v) -> resolved.put(k, resolveOne(v)));
        return resolved;
    }

    private static Object resolveOne(Object value) {
        if (value instanceof Iterator<?> it) {
        return joinChunks(it);
    }
        if (value instanceof Iterable<?> it && !(value instanceof Map<?, ?>) && !(value instanceof CharSequence)) {
            return joinChunks(it.iterator());
        }
        return value;
    }

    private static Object joinChunks(Iterator<?> it) {
        List<String> chunks = new ArrayList<>();
        while (it.hasNext()) {
            Object chunk = it.next();
            if (chunk != null) {
                chunks.add(String.valueOf(chunk));
            }
        }
        return chunks.isEmpty() ? null : String.join("", chunks);
    }

    private static Map<String, Object> asMap(Object inputs) {
        if (inputs instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return new LinkedHashMap<>();
    }

    private NodeExecutionException inputInvalid(String reason) {
        return new NodeExecutionException(nodeId, "jiuwen.aggregate", NodeCauseCode.NODE_CONFIG_INVALID, reason);
    }
}
