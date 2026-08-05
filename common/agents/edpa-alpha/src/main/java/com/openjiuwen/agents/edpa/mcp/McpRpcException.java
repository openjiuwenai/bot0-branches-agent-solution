/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.mcp;

/**
 * Raised when an MCP {@code tools/call} (or any request) returns a JSON-RPC
 * {@code error} object. Carries the request id so callers / verifiers can
 * distinguish a tool failure (device-fault signal) from a transport fault.
 *
 * @since 2026-07
 */
public class McpRpcException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int requestId;

    public McpRpcException(int requestId, String message) {
        super("MCP id=" + requestId + ": " + message);
        this.requestId = requestId;
    }

    public int getRequestId() {
        return requestId;
    }
}
