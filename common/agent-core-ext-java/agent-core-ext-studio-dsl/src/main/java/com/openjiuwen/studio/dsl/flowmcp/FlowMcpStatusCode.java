/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.flowmcp;

import java.util.Map;

/**
 * FlowMcp status codes — 1:1 with Python {@code FlowMcpStatusCode}.
 *
 * @since 2026-08-26
 */
public enum FlowMcpStatusCode {
    WORKFLOW_MCP_UNSUPPORTED_TYPE_ERROR(101870, "Unsupported mcp type: {mcp_type}."),
    WORKFLOW_MCP_FIELD_EMPTY_TYPE_ERROR(101872, "Field {field} should not be empty in {mcp_type}."),
    WORKFLOW_MCP_EXECUTE_ERROR(101873, "Mcp execute error, reason is {error}."),
    WORKFLOW_MCP_PARAM_METHOD_ERROR(101874, "Unsupported mcp param method: {method}."),
    WORKFLOW_MCP_PARAM_TYPE_ERROR(101875, "Invalid mcp argument param type for {param}, expect type is {type}."),
    WORKFLOW_MCP_INPUTS_ERROR(101876, "FlowMcp inputs error, param is {param}"),
    WORKFLOW_MCP_OUTPUTS_TYPE_ERROR(101877, "FlowMcp outputs type error: {msg}");

    /** Align with Python StatusCode.WORKFLOW_MCP_EXECUTE_ERROR for outer catch. */
    public static final int WORKFLOW_MCP_EXECUTE_ERROR_CODE = 101873;

    /** Align with Python StatusCode.SUCCESS. */
    public static final int SUCCESS_CODE = 0;

    /** Align with Python StatusCode.WORKFLOW_API_EXECUTE_ERROR. */
    public static final int WORKFLOW_API_EXECUTE_ERROR_CODE = 105000;

    private final int code;
    private final String template;

    FlowMcpStatusCode(int code, String template) {
        this.code = code;
        this.template = template;
    }

    public int code() {
        return code;
    }

    public String message(Map<String, ?> kwargs) {
        String msg = template;
        if (kwargs != null) {
            for (Map.Entry<String, ?> e : kwargs.entrySet()) {
                msg = msg.replace("{" + e.getKey() + "}", String.valueOf(e.getValue()));
            }
        }
        return msg;
    }
}
