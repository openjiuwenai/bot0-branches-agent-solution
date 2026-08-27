/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight branch / loop condition evaluator aligned with Studio + Python ExpressionCondition.
 * Supports map operators and expression strings ({@code &&}, {@code length()}, {@code in}/{@code not_in},
 * {@code is_empty}/{@code is_not_empty}, comparisons).
 *
 * @since 2026-08-17
 */

public final class ConditionEvaluator {
    private static final Pattern IN_OP =
            Pattern.compile("^(.+?)\\s+not_in\\s+(.+)$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern IN_OP_POS =
            Pattern.compile("^(.+?)\\s+in\\s+(.+)$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern IS_EMPTY =
            Pattern.compile("^is_empty\\s*\\((.+)\\)$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern IS_NOT_EMPTY =
            Pattern.compile("^is_not_empty\\s*\\((.+)\\)$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern LENGTH_FN =
            Pattern.compile("^length\\s*\\((.+)\\)$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern COMPARE =
            Pattern.compile(
                    "^(.+?)\\s*(==|!=|>=|<=|>|<)\\s*(.+)$", Pattern.DOTALL);

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

    private static boolean matchString(String raw, Map<String, Object> userFields) {
        String s = stripOuterParens(raw == null ? "" : raw.trim());
        if (s.isBlank() || "true".equalsIgnoreCase(s) || "default".equalsIgnoreCase(s)) {
            return true;
        }
        if (containsTopLevel(s, "&&")) {
            for (String part : splitTopLevel(s, "&&")) {
                if (!matchString(part.trim(), userFields)) {
                    return false;
                }
            }
            return true;
        }
        if (containsTopLevel(s, "||")) {
            for (String part : splitTopLevel(s, "||")) {
                if (matchString(part.trim(), userFields)) {
                    return true;
                }
            }
            return false;
        }
        Matcher empty = IS_EMPTY.matcher(s);
        if (empty.matches()) {
            Object v = resolveExprValue(empty.group(1).trim(), userFields);
            return v == null || "".equals(stringify(v)) || (v instanceof List<?> l && l.isEmpty());
        }
        Matcher notEmpty = IS_NOT_EMPTY.matcher(s);
        if (notEmpty.matches()) {
            Object v = resolveExprValue(notEmpty.group(1).trim(), userFields);
            return v != null && !"".equals(stringify(v)) && !(v instanceof List<?> l && l.isEmpty());
        }
        Matcher notIn = IN_OP.matcher(s);
        if (notIn.matches()) {
            String needle = strip(notIn.group(1).trim());
            Object hay = resolveExprValue(notIn.group(2).trim(), userFields);
            return !stringify(hay).contains(needle);
        }
        Matcher in = IN_OP_POS.matcher(s);
        if (in.matches() && !s.toLowerCase(Locale.ROOT).contains("not_in")) {
            String needle = strip(in.group(1).trim());
            Object hay = resolveExprValue(in.group(2).trim(), userFields);
            return stringify(hay).contains(needle);
        }
        Matcher cmp = COMPARE.matcher(s);
        if (cmp.matches()) {
            Object left = resolveExprValue(cmp.group(1).trim(), userFields);
            String op = cmp.group(2);
            Object right = resolveExprValue(cmp.group(3).trim(), userFields);
            return compare(op, left, right);
        }
        Optional<Object> v = PathResolver.get(userFields, stripRef(s));
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
        String opLower = op.toLowerCase(Locale.ROOT);
        if ("in".equals(opLower)) {
            if (m.containsKey("variable")) {
                return membership(left, right);
            }
            return membership(right, left);
        }
        if ("not_in".equals(opLower)) {
            if (m.containsKey("variable")) {
                return !membership(left, right);
            }
            return !membership(right, left);
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
        if (side instanceof String s) {
            return resolveExprValue(s, uf);
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
            return resolveExprValue(vs, uf);
        }
        if (uf != null && uf.containsKey(vs)) {
            return uf.get(vs);
        }
        return val;
    }

    private static Object resolveExprValue(String token, Map<String, Object> uf) {
        String t = stripOuterParens(token == null ? "" : token.trim());
        Matcher len = LENGTH_FN.matcher(t);
        if (len.matches()) {
            Object inner = resolveExprValue(len.group(1).trim(), uf);
            return lengthOf(inner);
        }
        if ((t.startsWith("'") && t.endsWith("'")) || (t.startsWith("\"") && t.endsWith("\""))) {
            return strip(t);
        }
        if (t.startsWith("${") && t.endsWith("}")) {
            t = t.substring(2, t.length() - 1).trim();
        }
        if (t.startsWith("userFields.")) {
            t = t.substring("userFields.".length());
        }
        // drop common Studio path prefixes for node-level tests
        if (t.startsWith("start.systemFields.")) {
            t = t.substring("start.systemFields.".length());
        } else if (t.startsWith("start.userFields.")) {
            t = t.substring("start.userFields.".length());
        }
        Optional<Object> fromPath = PathResolver.get(uf, t);
        if (fromPath.isPresent()) {
            return fromPath.get();
        }
        if (uf != null && uf.containsKey(t)) {
            return uf.get(t);
        }
        try {
            if (t.contains(".")) {
                return Double.parseDouble(t);
            }
            return Long.parseLong(t);
        } catch (NumberFormatException ignored) {
            return strip(t);
        }
    }

    private static int lengthOf(Object o) {
        if (o == null) {
        return 0;
    }
        if (o instanceof List<?> list) {
            return list.size();
        }
        if (o instanceof Map<?, ?> map) {
            return map.size();
        }
        return String.valueOf(o).length();
    }

    private static boolean compare(String op, Object left, Object right) {
        String o = op.toLowerCase(Locale.ROOT);
        return switch (o) {
            case "eq", "equals", "==", "equal" -> Objects.equals(stringify(left), stringify(right));
            case "neq", "!=", "not_equals" -> !Objects.equals(stringify(left), stringify(right));
            case "empty", "is_empty" -> left == null || "".equals(stringify(left));
            case "not_empty", "is_not_empty" -> left != null && !"".equals(stringify(left));
            case "contains" -> membership(left, right);
            case "in" -> membership(right, left);
            case "not_in" -> !membership(right, left);
            case "not_contains" -> !membership(left, right);
            case "gt", ">" -> toDouble(left) > toDouble(right);
            case "gte", ">=" -> toDouble(left) >= toDouble(right);
            case "lt", "<" -> toDouble(left) < toDouble(right);
            case "lte", "<=" -> toDouble(left) <= toDouble(right);
            case "true", "always" -> true;
            default -> Objects.equals(stringify(left), stringify(right));
        };
    }

    /**
     * {@code left in right} — membership in collection/map or substring in string container.
     *
     * @param container container
     * @param member member
     * @return result
     * @since 0.1.0
     */
    private static boolean membership(Object container, Object member) {
        if (container instanceof List<?> list) {
            String ms = stringify(member);
            for (Object item : list) {
                if (Objects.equals(stringify(item), ms)) {
                    return true;
                }
            }
            return false;
        }
        if (container instanceof Map<?, ?> map) {
            if (map.containsKey(member)) {
                return true;
            }
            String ms = stringify(member);
            if (map.containsKey(ms)) {
                return true;
            }
            for (Object v : map.values()) {
                if (Objects.equals(stringify(v), ms)) {
                    return true;
                }
            }
            return false;
        }
        return stringify(container).contains(stringify(member));
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

    private static String stripRef(String s) {
        String t = s.trim();
        if (t.startsWith("${") && t.endsWith("}")) {
            return t.substring(2, t.length() - 1).trim();
        }
        return t;
    }

    private static String stripOuterParens(String s) {
        String t = s.trim();
        while (t.startsWith("(") && t.endsWith(")") && balanced(t.substring(1, t.length() - 1))) {
            t = t.substring(1, t.length() - 1).trim();
        }
        return t;
    }

    private static boolean balanced(String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth < 0) {
                    return false;
                }
            }
        }
        return depth == 0;
    }

    private static boolean containsTopLevel(String s, String op) {
        return !splitTopLevel(s, op).isEmpty() && splitTopLevel(s, op).size() > 1;
    }
    private static List<String> splitTopLevel(String s, String op) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        boolean inQuote = false;
        char quote = 0;
        int start = 0;
        for (int i = 0; i < s.length(); ) {
            char c = s.charAt(i);
            if (inQuote) {
                if (c == quote) {
                    inQuote = false;
                }
                i++;
                continue;
            }
            if (c == '\'' || c == '"') {
                inQuote = true;
                quote = c;
                i++;
                continue;
            }
            if (c == '(') {
                depth++;
                i++;
                continue;
            }
            if (c == ')') {
                depth--;
                i++;
                continue;
            }
            if (depth == 0 && s.startsWith(op, i)) {
                parts.add(s.substring(start, i));
                i += op.length();
                start = i;
                continue;
            }
            i++;
        }
        parts.add(s.substring(start));
        return parts;
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
