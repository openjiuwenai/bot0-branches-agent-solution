/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.flowmcp;

import java.util.Map;

/**
 * Deserialized MCP tool argument param — mirrors Python {@code Param} subset used by FlowMcp.
 *
 * @since 2026-08-26
 */
public final class McpToolParam {
    public static final String METHOD_BODY = "Body";
    public static final String METHOD_HEADERS = "Headers";
    public static final String METHOD_QUERY = "Query";

    private final String name;
    private final String description;
    private final String type;
    private final boolean required;
    private final String method;
    private final Object defaultValue;

    public McpToolParam(
            String name, String description, String type, boolean required, String method, Object defaultValue) {
        this.name = name == null ? "" : name;
        this.description = description == null ? "" : description;
        this.type = type == null ? "" : type;
        this.required = required;
        this.method = method == null || method.isBlank() ? METHOD_BODY : method;
        this.defaultValue = defaultValue;
    }

    public static McpToolParam fromMap(Map<?, ?> raw) {
        Object req = raw.get("required");
        boolean required = false;
        if (req instanceof Boolean b) {
            required = b;
        } else if (req != null) {
            String s = String.valueOf(req).toLowerCase();
            if ("true".equals(s) || "false".equals(s)) {
                required = Boolean.parseBoolean(s);
            } else {
                throw FlowMcpErrors.of(
                        FlowMcpStatusCode.WORKFLOW_MCP_PARAM_TYPE_ERROR,
                        Map.of("param", "required", "type", "bool"));
            }
        }
        Object type = raw.get("type");
        Object method = raw.get("method");
        Object name = raw.get("name");
        Object desc = raw.get("description");
        Object def = raw.containsKey("default_value") ? raw.get("default_value") : raw.get("defaultValue");
        return new McpToolParam(
                name == null ? "" : String.valueOf(name),
                desc == null ? "" : String.valueOf(desc),
                type == null ? "" : String.valueOf(type),
                required,
                method == null ? METHOD_BODY : String.valueOf(method),
                def);
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public String type() {
        return type;
    }

    public boolean required() {
        return required;
    }

    public String method() {
        return method;
    }

    public Object defaultValue() {
        return defaultValue;
    }
}
