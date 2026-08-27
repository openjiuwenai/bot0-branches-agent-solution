/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.flowapi;

/**
 * Python {@code Param} subset used by FlowApi.
 *
 * @since 2026-08-26
 *
 * @param name name
 * @param description description
 * @param type type
 * @param required required
 * @return result
 */

public record FlowApiParam(String name, String description, String type, boolean required) {

    /**
     * fromDict.
     *
     * @param p p
     * @return result
     * @since 0.1.0
     */

    public static FlowApiParam fromDict(java.util.Map<String, Object> p) {
        return new FlowApiParam(
                str(p.get("name")),
                str(p.getOrDefault("description", "")),
                str(p.getOrDefault("type", p.getOrDefault("param_type", ""))),
                bool(p.get("required")));
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
    private static boolean bool(Object o) {
        if (o instanceof Boolean b) {
        return b;
    }
        return "true".equalsIgnoreCase(String.valueOf(o));
    }
}
