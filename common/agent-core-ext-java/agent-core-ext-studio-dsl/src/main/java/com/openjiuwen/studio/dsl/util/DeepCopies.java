package com.openjiuwen.studio.dsl.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DeepCopies {
    private DeepCopies() {}

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
