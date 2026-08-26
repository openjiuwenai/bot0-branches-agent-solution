/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.flowmcp;

import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.model.NodeCauseCode;

import java.util.Map;

/**
 * FlowMcp error helpers.
 *
 * @since 2026-08-26
 */
final class FlowMcpErrors {
    private FlowMcpErrors() {}

    static NodeExecutionException of(String nodeId, FlowMcpStatusCode status, Map<String, ?> kwargs) {
        return new NodeExecutionException(
                nodeId == null ? "mcp" : nodeId,
                "jiuwen.mcp",
                NodeCauseCode.NODE_INVOKE_FAILED,
                status.message(kwargs));
    }

    static NodeExecutionException of(FlowMcpStatusCode status, Map<String, ?> kwargs) {
        return of("mcp", status, kwargs);
    }

    static NodeExecutionException config(String nodeId, FlowMcpStatusCode status, Map<String, ?> kwargs) {
        return new NodeExecutionException(
                nodeId == null ? "mcp" : nodeId,
                "jiuwen.mcp",
                NodeCauseCode.NODE_CONFIG_INVALID,
                status.message(kwargs));
    }

    static NodeExecutionException execute(String nodeId, String errorType) {
        return new NodeExecutionException(
                nodeId == null ? "mcp" : nodeId,
                "jiuwen.mcp",
                NodeCauseCode.NODE_INVOKE_FAILED,
                FlowMcpStatusCode.WORKFLOW_MCP_EXECUTE_ERROR.message(Map.of("error", errorType)));
    }
}
