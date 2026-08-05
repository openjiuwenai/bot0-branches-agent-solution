/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.mcp;

import java.util.List;
import java.util.Map;

/**
 * SPI for a thin MCP client — the "LLMClient direct-call" workaround for the
 * SDK's broken MCP client implementations (StdioClient hangs, StreamableHTTP
 * drops Mcp-Session-Id, SSE returns 0 tools). We speak JSON-RPC ourselves and
 * expose MCP tools through a {@code Tool} adapter, bypassing the SDK MCP path.
 *
 * <p>Mirror of the {@code Explorer} SPI: interface so unit tests can mock the
 * transport while {@link StdioMcpClient} does the real stdio JSON-RPC.
 *
 * @since 2026-07
 */
public interface McpClient extends AutoCloseable {
    /**
     * MCP {@code tools/list} — descriptors for every tool the server exposes.
     *
     * @return descriptors for every tool the server exposes
     * @throws Exception if the transport fails or the server returns an error
     */
    List<McpTool> listTools() throws Exception;

    /**
     * MCP {@code tools/call} — invoke a tool by name, return its text content.
     *
     * @param name tool name (must match a name from {@link #listTools()})
     * @param arguments tool arguments (JSON object)
     * @return concatenated text content from the tool result
     * @throws Exception if the transport fails or the server returns an error
     */
    String callTool(String name, Map<String, Object> arguments) throws Exception;

    /**
     * Tear down the transport (kill subprocess, close streams).
     */
    @Override
    void close();
}
