package com.openjiuwen.studio.dsl.util;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Lightweight branch condition evaluator aligned with Studio branch configs.
 * Engine does not interpret business semantics beyond declared operators (FEAT-028).
 */
public final class ConditionEvaluator {
    private ConditionEvaluator() {}

    @SuppressWarnings("unchecked")
    public static boolean matches(Object condition, Map<String, Object> userFields) {
        if (condition == null) {
            return true;
        }
        if (condition instanceof Boolean b) {
            return b;
        }
        if (condition instanceof String s) {
            if (s.isBlank() || "true".equalsIgnoreCase(s) || "default".equalsIgnoreCase(s)) {
                return true;
            }
            // simple equality: field==value or field!=value
            if (s.contains("==")) {
                String[] p = s.split("==", 2);
                return Objects.equals(String.valueOf(PathResolver.get(userFields, p[0].trim())), strip(p[1].trim()));
            }
            if (s.contains("!=")) {
                String[] p = s.split("!=", 2);
                return !Objects.equals(String.valueOf(PathResolver.get(userFields, p[0].trim())), strip(p[1].trim()));
            }
            Object v = PathResolver.get(userFields, s);
            return v != null && !"".equals(v) && !Boolean.FALSE.equals(v);
        }
        if (condition instanceof Map<?, ?> m) {
            String op = String.valueOf(first(m, "operator", "op", "eq"));
            Object left = resolveSide(m.get("left"), userFields);
            if (left == null && m.containsKey("variable")) {
                left = PathResolver.get(userFields, String.valueOf(m.get("variable")));
            }
            Object right = resolveSide(m.get("right"), userFields);
            if (right == null) {
                right = firstObj(m, "value", "compareValue");
            }
            return compare(op, left, right);
        }
        if (condition instanceof List<?> list) {
            // AND of conditions
            for (Object c : list) {
                if (!matches(c, userFields)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private static Object resolveSide(Object side, Map<String, Object> uf) {
        if (side instanceof Map<?, ?> m) {
            if (m.containsKey("value")) {
                Object src = m.get("sourceType");
                Object val = m.get("value");
                String vs = String.valueOf(val);
                if ("reference".equals(String.valueOf(src)) || vs.startsWith("${")) {
                    String path = vs.replace("${", "").replace("}", "");
                    if (path.startsWith("userFields.")) {
                        path = path.substring("userFields.".length());
                    }
                    return PathResolver.get(uf, path);
                }
                // bare field name in left/right.value → resolve from userFields when present
                if (uf != null && uf.containsKey(vs)) {
                    return uf.get(vs);
                }
                return val;
            }
            if (m.containsKey("variable")) {
                return PathResolver.get(uf, String.valueOf(m.get("variable")));
            }
        }
        if (side instanceof String s && uf != null && uf.containsKey(s)) {
            return uf.get(s);
        }
        return side;
    }

    private static boolean compare(String op, Object left, Object right) {
        String o = op.toLowerCase();
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
