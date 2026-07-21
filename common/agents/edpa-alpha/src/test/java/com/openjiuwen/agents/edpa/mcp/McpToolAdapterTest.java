/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * McpToolAdapter bearing tests — verifies the local Tool forwards to the MCP
 * client with the right tool name + arguments, and that the ToolCard exposes
 * the MCP inputSchema verbatim as inputParams.
 *
 * <p>mutation-RED:
 * <ul>
 *   <li>Forward a different tool name → callTool receives wrong name → RED</li>
 *   <li>Drop inputParams in buildCard → LLM sees no parameter schema → RED</li>
 * </ul>
 *
 * @since 2026-07
 */
class McpToolAdapterTest {
    @Test
    void invokeForwardsToolNameAndArgs() throws Exception {
        CapturingClient client = new CapturingClient();
        client.tools = List.of(new McpTool("get_financials", "desc", Map.of("properties",
                Map.of("identifier", Map.of("type", "string")), "required", List.of("identifier"))));
        client.responder = args -> "result-for-" + args.get("identifier");

        McpToolAdapter adapter = new McpToolAdapter(client, client.tools.get(0));

        Object result = adapter.invoke(Map.of("identifier", "AAPL"), Map.of());

        assertThat(result).isEqualTo("result-for-AAPL");
        assertThat(client.lastCalledTool).isEqualTo("get_financials");
        assertThat(client.lastCalledArgs).containsEntry("identifier", "AAPL");
    }

    @Test
    void invokeNullArgsBecomesEmptyMap() throws Exception {
        CapturingClient client = new CapturingClient();
        client.tools = List.of(new McpTool("list_all", "no args", Map.of()));
        client.responder = args -> "ok-" + args.size();

        McpToolAdapter adapter = new McpToolAdapter(client, client.tools.get(0));
        Object result = adapter.invoke(null, null);

        assertThat(result).isEqualTo("ok-0");
        assertThat(client.lastCalledArgs).isEmpty();
    }

    @Test
    void cardExposesMcpSchemaAsInputParams() {
        CapturingClient client = new CapturingClient();
        Map<String, Object> schema = Map.of("type", "object", "properties",
                Map.of("identifier", Map.of("type", "string")), "required", List.of("identifier"));
        McpTool tool = new McpTool("get_company_info", "Get company info", schema);

        McpToolAdapter adapter = new McpToolAdapter(client, tool);

        assertThat(adapter.getCard().getName()).isEqualTo("get_company_info");
        assertThat(adapter.getCard().getDescription()).isEqualTo("Get company info");
        assertThat(adapter.getCard().getInputParams())
                .as("MCP inputSchema must be passed through verbatim as inputParams").containsEntry("type", "object")
                .containsKey("properties").containsEntry("required", List.of("identifier"));
    }

    @Test
    void streamReturnsSingleElementIterator() throws Exception {
        CapturingClient client = new CapturingClient();
        client.tools = List.of(new McpTool("t", "d", Map.of()));
        client.responder = args -> "streamed";

        McpToolAdapter adapter = new McpToolAdapter(client, client.tools.get(0));
        Iterator<Object> it = adapter.stream(Map.of(), Map.of());

        assertThat(it.hasNext()).isTrue();
        assertThat(it.next()).isEqualTo("streamed");
        assertThat(it.hasNext()).isFalse();
    }

    @Test
    void mcpToolNameAccessor() {
        CapturingClient client = new CapturingClient();
        McpTool tool = new McpTool("get_cik_by_ticker", "d", Map.of());
        McpToolAdapter adapter = new McpToolAdapter(client, tool);
        assertThat(adapter.mcpToolName()).isEqualTo("get_cik_by_ticker");
    }

    /** Minimal McpClient that records calls and returns a canned response. */
    static class CapturingClient implements McpClient {
        List<McpTool> tools = List.of();
        java.util.function.Function<Map<String, Object>, String> responder = a -> "";
        String lastCalledTool;
        Map<String, Object> lastCalledArgs;

        @Override
        public List<McpTool> listTools() {
            return tools;
        }

        @Override
        public String callTool(String name, Map<String, Object> arguments) {
            lastCalledTool = name;
            lastCalledArgs = arguments == null ? Map.of() : new LinkedHashMap<>(arguments);
            return responder.apply(arguments);
        }

        @Override
        public void close() {
            // no-op
        }
    }
}
