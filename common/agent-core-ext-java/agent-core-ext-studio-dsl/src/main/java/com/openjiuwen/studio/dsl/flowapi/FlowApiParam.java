/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.flowapi;

/**
 * Python {@code Param} subset used by FlowApi.
 *
 * @since 2026-08-26
 */
public record FlowApiParam(String name, String description, String type, boolean required) {
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
