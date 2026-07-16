/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.mcp;

import java.util.Map;

/**
 * One MCP tool descriptor — the projection of a remote MCP tool we need to
 * register a local {@link com.openjiuwen.core.foundation.tool.Tool} adapter for.
 *
 * <p>{@code inputSchema} is the MCP tool's JSON Schema (object with
 * {@code properties}/{@code required}) passed through verbatim as the local
 * ToolCard's {@code inputParams}, so the LLM sees the exact same parameter
 * shape the remote tool expects.
 *
 * @since 2026-07
 */
public record McpTool(String name, String description, Map<String, Object> inputSchema) {
    public McpTool {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("MCP tool name must not be blank");
        }
        description = description == null ? "" : description;
        inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
    }
}
