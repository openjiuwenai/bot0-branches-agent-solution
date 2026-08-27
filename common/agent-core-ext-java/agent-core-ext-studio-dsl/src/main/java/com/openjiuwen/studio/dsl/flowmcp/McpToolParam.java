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

    /**
     * METHOD_BODY.
     *
     * @since 0.1.0
     */

    public static final String METHOD_BODY = "Body";

    /**
     * METHOD_HEADERS.
     *
     * @since 0.1.0
     */

    public static final String METHOD_HEADERS = "Headers";

    /**
     * METHOD_QUERY.
     *
     * @since 0.1.0
     */

    public static final String METHOD_QUERY = "Query";

    private final String name;
    private final String description;
    private final String type;
    private final boolean required;
    private final String method;
    private final Object defaultValue;

    /**
     * McpToolParam.
     *
     * @param name name
     * @param description description
     * @param type type
     * @param required required
     * @param method method
     * @param defaultValue defaultValue
     * @since 0.1.0
     */

    public McpToolParam(
            String name, String description, String type, boolean required, String method, Object defaultValue) {
        this.name = name == null ? "" : name;
        this.description = description == null ? "" : description;
        this.type = type == null ? "" : type;
        this.required = required;
        this.method = method == null || method.isBlank() ? METHOD_BODY : method;
        this.defaultValue = defaultValue;
    }

    /**
     * fromMap.
     *
     * @param raw raw
     * @return result
     * @since 0.1.0
     */

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

    /**
     * name.
     *
     * @return result
     * @since 0.1.0
     */

    public String name() {
        return name;
    }

    /**
     * description.
     *
     * @return result
     * @since 0.1.0
     */

    public String description() {
        return description;
    }

    /**
     * type.
     *
     * @return result
     * @since 0.1.0
     */

    public String type() {
        return type;
    }

    /**
     * required.
     *
     * @return result
     * @since 0.1.0
     */

    public boolean required() {
        return required;
    }

    /**
     * method.
     *
     * @return result
     * @since 0.1.0
     */

    public String method() {
        return method;
    }

    /**
     * defaultValue.
     *
     * @return result
     * @since 0.1.0
     */

    public Object defaultValue() {
        return defaultValue;
    }
}
