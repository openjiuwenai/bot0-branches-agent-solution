/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DeepCopies for Studio DSL node-type extension (FEAT-031).
 *
 * @since 2026-08-17
 */

public final class DeepCopies {
    private DeepCopies() {}

    /**
     * map.
     *
     * @param in in
     * @return result
     */

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Map<String, Object> in) {
        if (in == null) {
        return new LinkedHashMap<>();
    }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : in.entrySet()) {
            out.put(e.getKey(), value(e.getValue()));
        }
        return out;
    }

    /**
     * value.
     *
     * @param v v
     * @return result
     */

    @SuppressWarnings("unchecked")
    public static Object value(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> copy = new LinkedHashMap<>();
            m.forEach((k, val) -> copy.put(String.valueOf(k), value(val)));
            return copy;
        }
        if (v instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object o : list) {
                copy.add(value(o));
            }
            return copy;
        }
        return v;
    }
}
