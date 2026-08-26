/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.flowmcp;

import com.openjiuwen.core.foundation.tool.mcp.McpClient;
import com.openjiuwen.core.foundation.tool.mcp.McpToolCard;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

/**
 * Hand-rolled MCP client stub for tests (avoids Mockito inline agent).
 *
 * @since 2026-08-26
 */
public final class RecordingMcpClient implements McpClient {
    private final List<McpToolCard> tools;
    private final BiFunction<String, Map<String, Object>, Object> callHandler;
    private final AtomicInteger listToolsCalls = new AtomicInteger();
    private final AtomicInteger callToolCalls = new AtomicInteger();
    private final List<Map<String, Object>> lastCallArgs = new CopyOnWriteArrayList<>();

    public RecordingMcpClient(List<McpToolCard> tools, BiFunction<String, Map<String, Object>, Object> callHandler) {
        this.tools = tools == null ? List.of() : List.copyOf(tools);
        this.callHandler = callHandler;
    }

    public static RecordingMcpClient withContent(List<?> content) {
        McpToolCard card = McpToolCard.builder()
                .id("mock_tool")
                .name("mock_tool")
                .serverName("weather_mcp_server")
                .description("mock")
                .inputParams(Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of(
                                "query",
                                Map.of("type", "string"),
                                FlowMcpEngine.JIUWEN_RUNTIME_KWARGS,
                                Map.of("type", "object")),
                        "additionalProperties",
                        true))
                .build();
        return new RecordingMcpClient(List.of(card), (name, args) -> content);
    }

    public int listToolsCalls() {
        return listToolsCalls.get();
    }

    public int callToolCalls() {
        return callToolCalls.get();
    }

    public Map<String, Object> lastCallArguments() {
        return lastCallArgs.isEmpty() ? Map.of() : lastCallArgs.get(lastCallArgs.size() - 1);
    }

    @Override
    public boolean connect(int retryTimes, float timeout) {
        return true;
    }

    @Override
    public boolean disconnect(float timeout) {
        return true;
    }

    @Override
    public List<Object> listTools(float timeout) {
        listToolsCalls.incrementAndGet();
        return new ArrayList<>(tools);
    }

    @Override
    public Object callTool(String toolName, Map<String, Object> arguments, float timeout) {
        callToolCalls.incrementAndGet();
        Map<String, Object> copy = arguments == null ? Map.of() : Map.copyOf(arguments);
        lastCallArgs.add(copy);
        return callHandler.apply(toolName, arguments);
    }

    @Override
    public Optional<Object> getToolInfo(String toolName, float timeout) {
        return tools.stream().filter(t -> toolName.equals(t.getName())).map(t -> (Object) t).findFirst();
    }

    @Override
    public String getServerPath() {
        return "http://localhost/mcp";
    }
}
