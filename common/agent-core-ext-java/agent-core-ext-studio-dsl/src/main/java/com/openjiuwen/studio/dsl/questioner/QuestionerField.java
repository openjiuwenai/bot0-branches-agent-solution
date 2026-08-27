/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.questioner;

import java.util.Map;

/**
 * Questioner field definition.
 *
 * @since 2026-08-25
 */
public final class QuestionerField {
    private final String fieldName;
    private final String description;
    private final String type;
    private final String cnFieldName;
    private final boolean required;
    private final Object defaultValue;
    private final boolean reflection;

    /**
     * QuestionerField.
     *
     * @param fieldName fieldName
     * @param description description
     * @param type type
     * @param cnFieldName cnFieldName
     * @param required required
     * @param defaultValue defaultValue
     * @param reflection reflection
     */
    public QuestionerField(
            String fieldName,
            String description,
            String type,
            String cnFieldName,
            boolean required,
            Object defaultValue,
            boolean reflection) {
        this.fieldName = fieldName;
        this.description = description == null ? "" : description;
        this.type = type == null || type.isBlank() ? "string" : type;
        this.cnFieldName = cnFieldName == null || cnFieldName.isBlank() ? fieldName : cnFieldName;
        this.required = required;
        this.defaultValue = defaultValue;
        this.reflection = reflection;
    }

    /**
     * fromMap.
     *
     * @param m m
     * @return result
     */
    public static QuestionerField fromMap(Map<String, Object> m) {
        String name = first(m, "fieldName", "id", "name");
        String desc = str(m.get("description"));
        String type = str(m.getOrDefault("type", "string"));
        String cn = str(m.getOrDefault("cnFieldName", name));
        boolean required = m.get("required") instanceof Boolean b && b;
        Object def = m.get("defaultValue");
        boolean reflection = m.get("reflection") instanceof Boolean rb && rb;
        return new QuestionerField(name, desc, type, cn, required, def, reflection);
    }

    private static String first(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null && !String.valueOf(v).isBlank()) {
                return String.valueOf(v);
            }
        }
        return "";
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /** @return fieldName */
    public String fieldName() {
        return fieldName;
    }

    /** @return description */
    public String description() {
        return description;
    }

    /** @return type */
    public String type() {
        return type;
    }

    /** @return cnFieldName */
    public String cnFieldName() {
        return cnFieldName;
    }

    /** @return required */
    public boolean required() {
        return required;
    }

    /** @return defaultValue */
    public Object defaultValue() {
        return defaultValue;
    }

    /** @return reflection */
    public boolean reflection() {
        return reflection;
    }
}
