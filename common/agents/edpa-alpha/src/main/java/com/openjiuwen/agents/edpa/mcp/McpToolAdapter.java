/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.mcp;

import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.foundation.tool.ToolCard;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Adapts one remote MCP tool into a local SDK {@link Tool}. The LLM sees a
 * normal tool card (name / description / inputParams = the MCP tool's JSON
 * Schema); when it invokes the tool, the call is forwarded to the MCP server
 * over the thin {@link McpClient}.
 *
 * <p>This is the bridge that lets EDPA-alpha consume MCP tools without going
 * through the SDK's broken MCP client — the tool dispatch path stays native,
 * only the data channel speaks JSON-RPC.
 *
 * @since 2026-07
 */
public class McpToolAdapter extends Tool {
    private final McpClient client;
    private final McpTool tool;
    private final ToolCard card;

    /**
     * Creates an adapter that forwards invocations of the given MCP tool to the
     * supplied client.
     *
     * @param client the thin JSON-RPC client used to call the MCP server
     * @param tool the remote MCP tool descriptor to adapt into a local tool
     */
    public McpToolAdapter(McpClient client, McpTool tool) {
        super(buildCard(tool));
        this.client = client;
        this.tool = tool;
        this.card = super.getCard();
    }

    private static ToolCard buildCard(McpTool tool) {
        return ToolCard.builder().id(tool.name()).name(tool.name()).description(tool.description())
                .inputParams(tool.inputSchema()).build();
    }

    /**
     * Returns the cached tool card built from the MCP tool descriptor.
     *
     * @return the local SDK tool card the LLM sees
     */
    @Override
    public ToolCard getCard() {
        return card;
    }

    /**
     * Forwards the tool invocation to the MCP server, returning whatever the
     * server replies.
     *
     * @param args the positional/keyword arguments merged into one map; an empty
     *             map is used when {@code null}
     * @param kwargs unused placeholder kept for signature compatibility
     * @return the MCP server's tool call result
     * @throws Exception if the MCP client fails to reach the server or the call errors
     */
    @Override
    public Object invoke(Map<String, Object> args, Map<String, Object> kwargs) throws Exception {
        return client.callTool(tool.name(), args == null ? Map.of() : args);
    }

    /**
     * Streams the tool result. MCP tools are not natively streamed, so this
     * delegates to {@link #invoke(Map, Map)} and yields the single result.
     *
     * @param args the positional/keyword arguments merged into one map; an empty
     *             map is used when {@code null}
     * @param kwargs unused placeholder kept for signature compatibility
     * @return an iterator over a single-element list holding the invocation result
     * @throws Exception if {@link #invoke(Map, Map)} fails
     */
    @Override
    public Iterator<Object> stream(Map<String, Object> args, Map<String, Object> kwargs) throws Exception {
        return List.<Object>of(invoke(args, kwargs)).iterator();
    }

    /**
     * Returns the MCP tool name this adapter forwards to.
     *
     * @return the underlying MCP tool's name
     */
    public String mcpToolName() {
        return tool.name();
    }
}
