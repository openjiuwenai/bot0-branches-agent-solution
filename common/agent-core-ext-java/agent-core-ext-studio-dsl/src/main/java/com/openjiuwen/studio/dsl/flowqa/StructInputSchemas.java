/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.flowqa;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Struct input schema helpers — 1:1 with Python {@code flow_qa} schema normalize / build.
 *
 * @since 2026-08-26
 */

public final class StructInputSchemas {
    private StructInputSchemas() {}

    public static Map<String, Object> buildStructInputSchemas(
            Object nodeInputs, Object configInputs, Map<String, Object> explicitSchemas) {
        Map<String, Object> schemas = new LinkedHashMap<>();
        for (Object source : new Object[] {configInputs, nodeInputs}) {
            for (Map<String, Object> field : iterInputFieldDefs(source)) {
                String name = schemaFieldName(field);
                if (name != null && hasStructSchema(field)) {
                    schemas.put(name, field);
                }
            }
        }
        if (explicitSchemas != null) {
            schemas.putAll(explicitSchemas);
        }
        return schemas;
    }

    public static Map<String, Object> normalizeStructInputs(
            Map<String, Object> userFields, Map<String, Object> schemas) {
        if (userFields == null) {
            return Map.of();
        }
        if (schemas == null || schemas.isEmpty()) {
            return userFields;
        }
        Map<String, Object> normalized = new LinkedHashMap<>(userFields);
        for (Map.Entry<String, Object> e : schemas.entrySet()) {
            if (normalized.containsKey(e.getKey()) && e.getValue() instanceof Map<?, ?> sm) {
                normalized.put(e.getKey(), normalizeWithSchema(normalized.get(e.getKey()), asMap(sm)));
            }
        }
        return normalized;
    }

    /**
     * normalizeWithSchema.
     *
     * @param value value
     * @param schema schema
     * @return result
     * @since 0.1.0
     */

    @SuppressWarnings("unchecked")
    public static Object normalizeWithSchema(Object value, Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) {
        return value;
    }
        if (value == null) {
            if (isObjectSchema(schema) || isArraySchema(schema)) {
                return value;
            }
            return schemaDefault(schema);
        }
        if (isObjectSchema(schema)) {
            if (!(value instanceof Map<?, ?>)) {
                return value;
            }
            Map<String, Object> src = asMap((Map<?, ?>) value);
            List<Map.Entry<String, Map<String, Object>>> fields = objectSchemaFields(schema);
            if (fields.isEmpty()) {
                return value;
            }
            Map<String, Object> normalized = new LinkedHashMap<>();
            java.util.Set<String> schemaFieldNames = new java.util.LinkedHashSet<>();
            for (Map.Entry<String, Map<String, Object>> f : fields) {
                schemaFieldNames.add(f.getKey());
                if (src.containsKey(f.getKey())) {
                    normalized.put(f.getKey(), normalizeWithSchema(src.get(f.getKey()), f.getValue()));
                } else {
                    normalized.put(f.getKey(), schemaDefault(f.getValue()));
                }
            }
            for (Map.Entry<String, Object> item : src.entrySet()) {
                if (!schemaFieldNames.contains(item.getKey())) {
                    normalized.put(item.getKey(), item.getValue());
                }
            }
            return normalized;
        }
        if (isArraySchema(schema) && value instanceof List<?> list) {
            Map<String, Object> itemSchema = arrayItemSchema(schema);
            if (itemSchema == null) {
                return value;
            }
            List<Object> out = new ArrayList<>();
            for (Object item : list) {
                out.add(normalizeWithSchema(item, itemSchema));
            }
            return out;
        }
        return value;
    }

    static String schemaFieldName(Map<String, Object> schema) {
        Object n = schema.get("name");
        if (n == null) {
            n = schema.get("id");
        }
        if (n == null) {
            n = schema.get("fieldName");
        }
        return n == null ? null : String.valueOf(n);
    }

    static String schemaType(Map<String, Object> schema) {
        Object t = schema.get("type");
        return t == null ? "" : String.valueOf(t).toLowerCase();
    }

    static List<Map.Entry<String, Map<String, Object>>> objectSchemaFields(Map<String, Object> schema) {
        Object props = schema.get("properties");
        if (props instanceof Map<?, ?> pm) {
            List<Map.Entry<String, Map<String, Object>>> fields = new ArrayList<>();
            pm.forEach((k, v) -> fields.add(Map.entry(
                    String.valueOf(k), v instanceof Map<?, ?> m ? asMap(m) : Map.of())));
            return fields;
        }
        Object children = schema.get("schema");
        if (!(children instanceof List<?> list)) {
            return List.of();
        }
        List<Map.Entry<String, Map<String, Object>>> fields = new ArrayList<>();
        for (Object child : list) {
            if (!(child instanceof Map<?, ?> cm)) {
                continue;
            }
            Map<String, Object> c = asMap(cm);
            String name = schemaFieldName(c);
            if (name != null) {
                fields.add(Map.entry(name, c));
            }
        }
        return fields;
    }

    static boolean isObjectSchema(Map<String, Object> schema) {
        return "object".equals(schemaType(schema))
                || schema.get("properties") instanceof Map<?, ?>
                || schema.get("schema") instanceof List<?>;
    }

    static Map<String, Object> arrayItemSchema(Map<String, Object> schema) {
        Object items = schema.get("items");
        if (items instanceof Map<?, ?> im) {
            return asMap(im);
        }
        Object dsl = schema.get("schema");
        if (dsl instanceof Map<?, ?> dm) {
            return asMap(dm);
        }
        if (dsl instanceof List<?> list) {
            Map<String, Object> wrap = new LinkedHashMap<>();
            wrap.put("type", "object");
            wrap.put("schema", list);
            return wrap;
        }
        return null;
    }

    static boolean isArraySchema(Map<String, Object> schema) {
        return "array".equals(schemaType(schema));
    }
    static Object schemaDefault(Map<String, Object> schema) {
        Object value = schema.get("value");
        if (value instanceof Map<?, ?> vm && vm.containsKey("default")) {
            return vm.get("default");
        }
        if (schema.containsKey("default")) {
            return schema.get("default");
        }
        if (isArraySchema(schema)) {
            return List.of();
        }
        if (isObjectSchema(schema)) {
            return Map.of();
        }
        return "";
    }

    static boolean hasStructSchema(Map<String, Object> schema) {
        if (isObjectSchema(schema)) {
        return !objectSchemaFields(schema).isEmpty();
    }
        if (isArraySchema(schema)) {
            return arrayItemSchema(schema) != null;
        }
        return false;
    }

    static List<Map<String, Object>> iterInputFieldDefs(Object fields) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (fields instanceof List<?> list) {
            for (Object field : list) {
                if (field instanceof Map<?, ?> m) {
                    out.add(asMap(m));
                }
            }
            return out;
        }
        if (fields instanceof Map<?, ?> m) {
            Map<String, Object> one = asMap(m);
            if (schemaFieldName(one) != null) {
                out.add(one);
            }
        }
        return out;
    }

    private static Map<String, Object> asMap(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }
}
