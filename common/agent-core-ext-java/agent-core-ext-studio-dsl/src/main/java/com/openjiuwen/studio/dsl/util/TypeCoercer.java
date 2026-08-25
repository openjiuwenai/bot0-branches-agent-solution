/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Studio FlowCode {@code _coerce_value} / list-schema coerce (L2 §4.3.2 + Python flow_code).
 *
 * @since 2026-08-17
 */
public final class TypeCoercer {
    private TypeCoercer() {}

    /**
     * Coerce a map of values using an optional per-field schema.
     *
     * @param inputs inputs
     * @param schema schema
     * @return coerced map
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> coerceMap(Map<String, Object> inputs, Map<String, Object> schema) {
        if (inputs == null) {
            return Map.of();
        }
        if (schema == null || schema.isEmpty()) {
            return new LinkedHashMap<>(inputs);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : inputs.entrySet()) {
            Object sch = schema.get(e.getKey());
            out.put(e.getKey(), coerce(e.getValue(), typeOf(sch), sch));
        }
        return out;
    }

    /**
     * Coerce using IR field list ({@code id}/{@code type}/optional {@code schema}).
     *
     * @param values values
     * @param fieldList field defs
     * @param inputMode when true, None→defaults for string/array/object
     * @return result
     */
    public static Map<String, Object> coerceByFieldList(
            Map<String, Object> values, List<Map<String, Object>> fieldList, boolean inputMode) {
        if (values == null) {
            return Map.of();
        }
        if (fieldList == null || fieldList.isEmpty()) {
            return new LinkedHashMap<>(values);
        }
        Map<String, String> typeMap = new LinkedHashMap<>();
        for (Map<String, Object> field : fieldList) {
            Object id = field.get("id");
            if (id == null) {
                continue;
            }
            String resolved = resolveFieldType(field);
            if (resolved != null) {
                typeMap.put(String.valueOf(id), resolved);
            }
        }
        if (typeMap.isEmpty()) {
            return new LinkedHashMap<>(values);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : values.entrySet()) {
            String t = typeMap.get(e.getKey());
            if (t != null) {
                out.put(e.getKey(), coerce(e.getValue(), t, null, inputMode));
            } else {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    static String resolveFieldType(Map<String, Object> fieldDef) {
        Object fieldType = fieldDef.get("type");
        if (fieldType == null) {
            return null;
        }
        String t = String.valueOf(fieldType);
        if ("array".equals(t)) {
            Object schema = fieldDef.get("schema");
            if (schema instanceof Map<?, ?> m) {
                Object item = m.get("type");
                if (item != null) {
                    return "array<" + item + ">";
                }
            }
        }
        return t;
    }

    private static String typeOf(Object sch) {
        if (sch instanceof Map<?, ?> m) {
            Object t = m.get("type");
            return t == null ? "" : String.valueOf(t);
        }
        return sch == null ? "" : String.valueOf(sch);
    }

    /**
     * Coerce a single value.
     *
     * @param value value
     * @param type type
     * @param schema schema
     * @return coerced value
     */
    public static Object coerce(Object value, String type, Object schema) {
        return coerce(value, type, schema, false);
    }

    /**
     * Coerce a single value with optional input-mode defaults.
     *
     * @param value value
     * @param type type
     * @param schema schema
     * @param inputMode inputMode
     * @return coerced value
     */
    @SuppressWarnings("unchecked")
    public static Object coerce(Object value, String type, Object schema, boolean inputMode) {
        if (type == null || type.isEmpty()) {
            return value;
        }
        if (value == null) {
            if (inputMode) {
                if ("string".equals(type)) {
                    return "";
                }
                if ("array".equals(type) || type.startsWith("array<")) {
                    return List.of();
                }
                if ("object".equals(type)) {
                    return Map.of();
                }
            }
            return null;
        }
        try {
            if (type.startsWith("array<") && type.endsWith(">")) {
                String itemType = type.substring(6, type.length() - 1).trim();
                Object listVal = value;
                if (value instanceof String s) {
                    listVal = tryParseJsonArray(s);
                    if (listVal == value) {
                        return value;
                    }
                }
                if (!(listVal instanceof List<?> list)) {
                    return value;
                }
                List<Object> out = new ArrayList<>();
                for (Object item : list) {
                    out.add(coerce(item, itemType, null, false));
                }
                return out;
            }
            return switch (type) {
                case "string" -> String.valueOf(value);
                case "integer" -> {
                    if (value instanceof Boolean b) {
                        yield b ? 1L : 0L;
                    }
                    yield value instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(value));
                }
                case "number" -> {
                    if (value instanceof Boolean b) {
                        yield b ? 1.0d : 0.0d;
                    }
                    yield value instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(value));
                }
                case "boolean" -> {
                    if (value instanceof Boolean b) {
                        yield b;
                    }
                    if (value instanceof String s) {
                        String lower = s.toLowerCase();
                        yield "true".equals(lower) || "1".equals(lower) || "yes".equals(lower);
                    }
                    yield Boolean.parseBoolean(String.valueOf(value));
                }
                case "object" -> {
                    if (value instanceof Map<?, ?>) {
                        yield value;
                    }
                    yield Map.of("value", value);
                }
                case "array" -> {
                    if (value instanceof List<?> list) {
                        yield new ArrayList<>(list);
                    }
                    if (value instanceof String s) {
                        Object parsed = tryParseJsonArray(s);
                        if (parsed instanceof List<?>) {
                            yield parsed;
                        }
                    }
                    yield List.of(value);
                }
                default -> value;
            };
        } catch (RuntimeException e) {
            return value;
        }
    }

    private static Object tryParseJsonArray(String s) {
        String t = s.trim();
        if (!t.startsWith("[")) {
            return s;
        }
        try {
            return SubprocessJson.parseArray(t);
        } catch (Exception e) {
            return s;
        }
    }

    /** Tiny bridge so util package can parse JSON arrays without circular deps on python package. */
    private static final class SubprocessJson {
        static Object parseArray(String s) throws Exception {
            // Delegate to python SimpleJson via reflection-free duplicate minimal array parse
            return MiniJson.parse(s);
        }
    }

    private static final class MiniJson {
        private final String s;
        private int i;

        private MiniJson(String s) {
            this.s = s;
        }

        static Object parse(String s) throws Exception {
            MiniJson p = new MiniJson(s.trim());
            Object v = p.parseValue();
            p.skipWs();
            if (p.i != p.s.length()) {
                throw new Exception("trailing");
            }
            return v;
        }

        private Object parseValue() throws Exception {
            skipWs();
            char c = s.charAt(i);
            if (c == '[') {
                return parseArray();
            }
            if (c == '{') {
                return parseObject();
            }
            if (c == '"') {
                return parseString();
            }
            return parseLiteral();
        }

        private List<Object> parseArray() throws Exception {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWs();
            if (peek(']')) {
                i++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWs();
                if (peek(']')) {
                    i++;
                    return list;
                }
                expect(',');
            }
        }

        private Map<String, Object> parseObject() throws Exception {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWs();
            if (peek('}')) {
                i++;
                return map;
            }
            while (true) {
                String key = parseString();
                skipWs();
                expect(':');
                map.put(key, parseValue());
                skipWs();
                if (peek('}')) {
                    i++;
                    return map;
                }
                expect(',');
            }
        }

        private String parseString() throws Exception {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\' && i < s.length()) {
                    sb.append(s.charAt(i++));
                } else {
                    sb.append(c);
                }
            }
            throw new Exception("unterminated");
        }

        private Object parseLiteral() {
            int start = i;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (Character.isLetterOrDigit(c) || c == '+' || c == '-' || c == '.') {
                    i++;
                } else {
                    break;
                }
            }
            String lit = s.substring(start, i);
            if ("true".equals(lit)) {
                return Boolean.TRUE;
            }
            if ("false".equals(lit)) {
                return Boolean.FALSE;
            }
            if ("null".equals(lit)) {
                return null;
            }
            if (lit.contains(".")) {
                return Double.valueOf(lit);
            }
            return Long.valueOf(lit);
        }

        private void expect(char c) throws Exception {
            skipWs();
            if (i >= s.length() || s.charAt(i) != c) {
                throw new Exception("expected " + c);
            }
            i++;
        }

        private boolean peek(char c) {
            return i < s.length() && s.charAt(i) == c;
        }

        private void skipWs() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }
    }
}
