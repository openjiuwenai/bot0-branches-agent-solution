/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.util;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Lightweight branch condition evaluator aligned with Studio branch configs.
 * Engine does not interpret business semantics beyond declared operators (FEAT-031).
 *
 * @since 2026-08-17
 */
public final class ConditionEvaluator {
    private ConditionEvaluator() {}

    /**
     * Evaluate a Studio branch condition against userFields.
     *
     * @param condition condition
     * @param userFields userFields
     * @return true when the condition matches
     */
    public static boolean matches(Object condition, Map<String, Object> userFields) {
        if (condition == null) {
            return true;
        }
        if (condition instanceof Boolean b) {
            return b;
        }
        if (condition instanceof String s) {
            return matchString(s, userFields);
        }
        if (condition instanceof Map<?, ?> m) {
            return matchMap(m, userFields);
        }
        if (condition instanceof List<?> list) {
            return matchAll(list, userFields);
        }
        return false;
    }

    private static boolean matchString(String s, Map<String, Object> userFields) {
        if (s.isBlank() || "true".equalsIgnoreCase(s) || "default".equalsIgnoreCase(s)) {
            return true;
        }
        if (s.contains("==")) {
            String[] p = s.split("==", 2);
            return Objects.equals(stringify(PathResolver.get(userFields, p[0].trim())), strip(p[1].trim()));
        }
        if (s.contains("!=")) {
            String[] p = s.split("!=", 2);
            return !Objects.equals(stringify(PathResolver.get(userFields, p[0].trim())), strip(p[1].trim()));
        }
        Optional<Object> v = PathResolver.get(userFields, s);
        return v.isPresent() && !"".equals(v.get()) && !Boolean.FALSE.equals(v.get());
    }

    private static boolean matchMap(Map<?, ?> m, Map<String, Object> userFields) {
        String op = String.valueOf(first(m, "operator", "op", "eq"));
        Object left = resolveSide(m.get("left"), userFields);
        if (left == null && m.containsKey("variable")) {
            left = PathResolver.get(userFields, String.valueOf(m.get("variable"))).orElse(null);
        }
        Object right = resolveSide(m.get("right"), userFields);
        if (right == null) {
            right = firstObj(m, "value", "compareValue");
        }
        return compare(op, left, right);
    }

    private static boolean matchAll(List<?> list, Map<String, Object> userFields) {
        for (Object c : list) {
            if (!matches(c, userFields)) {
                return false;
            }
        }
        return true;
    }

    private static Object resolveSide(Object side, Map<String, Object> uf) {
        if (side instanceof Map<?, ?> m) {
            return resolveMapSide(m, uf);
        }
        if (side instanceof String s && uf != null && uf.containsKey(s)) {
            return uf.get(s);
        }
        return side;
    }

    private static Object resolveMapSide(Map<?, ?> m, Map<String, Object> uf) {
        if (m.containsKey("value")) {
            return resolveValueSide(m, uf);
        }
        if (m.containsKey("variable")) {
            return PathResolver.get(uf, String.valueOf(m.get("variable"))).orElse(null);
        }
        return m;
    }

    private static Object resolveValueSide(Map<?, ?> m, Map<String, Object> uf) {
        Object src = m.get("sourceType");
        Object val = m.get("value");
        String vs = String.valueOf(val);
        if ("reference".equals(String.valueOf(src)) || vs.startsWith("${")) {
            String path = vs.replace("${", "").replace("}", "");
            if (path.startsWith("userFields.")) {
                path = path.substring("userFields.".length());
            }
            return PathResolver.get(uf, path).orElse(null);
        }
        if (uf != null && uf.containsKey(vs)) {
            return uf.get(vs);
        }
        return val;
    }

    private static boolean compare(String op, Object left, Object right) {
        String o = op.toLowerCase(Locale.ROOT);
        return switch (o) {
            case "eq", "equals", "==", "equal" -> Objects.equals(stringify(left), stringify(right));
            case "neq", "!=", "not_equals" -> !Objects.equals(stringify(left), stringify(right));
            case "empty", "is_empty" -> left == null || "".equals(stringify(left));
            case "not_empty", "is_not_empty" -> left != null && !"".equals(stringify(left));
            case "contains" -> stringify(left).contains(stringify(right));
            case "gt" -> toDouble(left) > toDouble(right);
            case "gte" -> toDouble(left) >= toDouble(right);
            case "lt" -> toDouble(left) < toDouble(right);
            case "lte" -> toDouble(left) <= toDouble(right);
            case "true", "always" -> true;
            default -> Objects.equals(stringify(left), stringify(right));
        };
    }

    private static String stringify(Object o) {
        if (o instanceof Optional<?> opt) {
            return opt.map(String::valueOf).orElse("");
        }
        return o == null ? "" : String.valueOf(o);
    }

    private static String strip(String s) {
        if ((s.startsWith("'") && s.endsWith("'")) || (s.startsWith("\"") && s.endsWith("\""))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static Object firstObj(Map<?, ?> m, String a, String b) {
        Object v = m.get(a);
        return v != null ? v : m.get(b);
    }

    private static Object first(Map<?, ?> m, String a, String b, Object def) {
        Object v = firstObj(m, a, b);
        return v != null ? v : def;
    }

    private static double toDouble(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        return Double.parseDouble(String.valueOf(o));
    }
}
