/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.mcp;

import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.singleagent.agents.ReActAgent;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Registers every tool exposed by an {@link McpClient} onto a {@link ReActAgent}
 * as local {@link McpToolAdapter}s.
 *
 * <p>Mirrors {@code ExploreToolRegistrar.registerOnto} /
 * {@code ReplanTool.registerOnto} dual-registration contract, per tool:
 * <ol>
 *   <li>{@code agent.getAbilityManager().add(card)} — LLM visibility (listToolInfo)</li>
 *   <li>{@code Runner.resourceMgr().addTool(adapter, null)} — runtime dispatch</li>
 * </ol>
 *
 * <p>This is the workaround entry point: instead of
 * {@code Runner.resourceMgr().addMcpServer(...)} (SDK MCP client is broken), we
 * list tools ourselves and register native adapters that speak JSON-RPC over
 * the thin {@link StdioMcpClient}. The agent sees a normal multi-tool surface;
 * only the data channel knows it is MCP.
 *
 * @since 2026-07
 */
public final class McpToolRegistrar {
    private McpToolRegistrar() {
    }

    /**
     * List tools from {@code client} and register each as a local adapter.
     *
     * @param agent the target ReActAgent
     * @param client an already-started McpClient (caller owns its lifecycle)
     * @return the registered adapters (caller may keep handles for direct invocation)
     * @throws Exception if {@link McpClient#listTools()} fails to enumerate MCP tools
     */
    public static List<McpToolAdapter> registerOnto(ReActAgent agent, McpClient client) throws Exception {
        List<McpTool> tools = client.listTools();
        List<McpToolAdapter> adapters = new java.util.ArrayList<>();
        for (McpTool t : tools) {
            McpToolAdapter adapter = new McpToolAdapter(client, t);
            agent.getAbilityManager().add(adapter.getCard());
            Runner.resourceMgr().addTool(adapter, null);
            adapters.add(adapter);
        }
        return adapters;
    }

    /**
     * Convenience: just the MCP tool names that were registered.
     *
     * @param adapters the adapters returned by {@link #registerOnto}
     * @return the ordered set of MCP tool names exposed by the given adapters
     */
    public static Set<String> registeredNames(List<McpToolAdapter> adapters) {
        Set<String> names = new LinkedHashSet<>();
        for (McpToolAdapter a : adapters) {
            names.add(a.mcpToolName());
        }
        return names;
    }
}
