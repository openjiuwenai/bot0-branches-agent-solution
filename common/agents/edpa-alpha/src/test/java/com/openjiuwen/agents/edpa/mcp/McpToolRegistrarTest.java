/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.schema.AgentCard;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * McpToolRegistrar bearing tests — dual-registration contract (same as
 * ExploreToolRegistrar / ReplanTool.registerOnto):
 * AbilityManager.add(card) + Runner.resourceMgr().addTool(adapter).
 *
 * <p>mutation-RED:
 * <ul>
 *   <li>Strip AbilityManager.add → LLM can't see MCP tools → RED (test 1)</li>
 *   <li>Strip Runner.resourceMgr().addTool → getTool can't find it → RED (test 2)</li>
 * </ul>
 *
 * @since 2026-07
 */
class McpToolRegistrarTest {
    @Test
    void registerOntoMakesAllMcpToolsVisibleToLLM() throws Exception {
        ReActAgent agent = new ReActAgent(AgentCard.builder().name("test").build());
        StubClient client = new StubClient(List.of(new McpTool("get_financials", "fin", Map.of()),
                new McpTool("get_company_info", "info", Map.of()), new McpTool("get_cik_by_ticker", "cik", Map.of())));

        List<McpToolAdapter> adapters = McpToolRegistrar.registerOnto(agent, client);

        var toolInfos = agent.getAbilityManager().listToolInfo();
        for (McpToolAdapter a : adapters) {
            boolean visible = toolInfos.stream().anyMatch(t -> a.mcpToolName().equals(t.getName()));
            assertThat(visible).as("AbilityManager.add must make MCP tool '%s' visible to LLM", a.mcpToolName())
                    .isTrue();
        }
        assertThat(McpToolRegistrar.registeredNames(adapters)).containsExactlyInAnyOrder("get_financials",
                "get_company_info", "get_cik_by_ticker");
    }

    @Test
    void registerOntoDispatchRegistersAdapters() throws Exception {
        ReActAgent agent = new ReActAgent(AgentCard.builder().name("test").build());
        StubClient client = new StubClient(List.of(new McpTool("get_financials", "fin", Map.of())));

        List<McpToolAdapter> adapters = McpToolRegistrar.registerOnto(agent, client);

        Object dispatched = Runner.resourceMgr().getTool("get_financials");
        assertThat(dispatched).as("Runner.resourceMgr().addTool must register the adapter under its name").isNotNull();
        assertThat(dispatched).as("registered tool must be the exact adapter instance (identity)")
                .isSameAs(adapters.get(0));
        // Discrimination: a never-registered name must NOT resolve to our adapter
        assertThat(Runner.resourceMgr().getTool("definitely-not-registered-xyzzy"))
                .as("missing tool must not be confused with a registered one").isNotSameAs(adapters.get(0));
    }

    @Test
    void registerOntoEmptyToolListIsNoOp() throws Exception {
        ReActAgent agent = new ReActAgent(AgentCard.builder().name("test").build());
        StubClient client = new StubClient(List.of());

        List<McpToolAdapter> adapters = McpToolRegistrar.registerOnto(agent, client);

        assertThat(adapters).isEmpty();
        assertThat(McpToolRegistrar.registeredNames(adapters)).isEmpty();
    }

    static class StubClient implements McpClient {
        final List<McpTool> tools;
        java.util.function.Function<Map<String, Object>, String> responder = a -> "";

        StubClient(List<McpTool> tools) {
            this.tools = tools;
        }

        @Override
        public List<McpTool> listTools() {
            return tools;
        }

        @Override
        public String callTool(String name, Map<String, Object> arguments) {
            return responder.apply(arguments);
        }

        @Override
        public void close() {
            // no-op
        }
    }
}
