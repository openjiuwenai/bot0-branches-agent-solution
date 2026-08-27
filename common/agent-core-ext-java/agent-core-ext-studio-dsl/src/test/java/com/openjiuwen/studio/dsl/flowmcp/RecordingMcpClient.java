/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.flowmcp;

import com.openjiuwen.core.foundation.tool.mcp.McpClient;
import com.openjiuwen.core.foundation.tool.mcp.McpToolCard;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    /**
     * withContent.
     *
     * @param content content
     * @return result
     * @since 0.1.0
     */

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

    /**
     * listToolsCalls.
     *
     * @return result
     * @since 0.1.0
     */

    public int listToolsCalls() {
        return listToolsCalls.get();
    }

    /**
     * callToolCalls.
     *
     * @return result
     * @since 0.1.0
     */

    public int callToolCalls() {
        return callToolCalls.get();
    }
    public Map<String, Object> lastCallArguments() {
        return lastCallArgs.isEmpty() ? Map.of() : lastCallArgs.get(lastCallArgs.size() - 1);
    }

    /**
     * connect.
     *
     * @param retryTimes retryTimes
     * @param timeout timeout
     * @return result
     * @since 0.1.0
     */

    @Override
    public boolean connect(int retryTimes, float timeout) {
        return true;
    }

    /**
     * disconnect.
     *
     * @param timeout timeout
     * @return result
     * @since 0.1.0
     */

    @Override
    public boolean disconnect(float timeout) {
        return true;
    }

    /**
     * listTools.
     *
     * @param timeout timeout
     * @return result
     * @since 0.1.0
     */

    @Override
    public List<Object> listTools(float timeout) {
        listToolsCalls.incrementAndGet();
        return new ArrayList<>(tools);
    }

    /**
     * callTool.
     *
     * @param toolName toolName
     * @param arguments arguments
     * @param timeout timeout
     * @return result
     * @since 0.1.0
     */

    @Override
    public Object callTool(String toolName, Map<String, Object> arguments, float timeout) {
        callToolCalls.incrementAndGet();
        Map<String, Object> copy = arguments == null ? Map.of() : Map.copyOf(arguments);
        lastCallArgs.add(copy);
        return callHandler.apply(toolName, arguments);
    }

    /**
     * getToolInfo.
     *
     * @param toolName toolName
     * @param timeout timeout
     * @return result
     * @since 0.1.0
     */

    @Override
    public Optional<Object> getToolInfo(String toolName, float timeout) {
        return tools.stream().filter(t -> toolName.equals(t.getName())).map(t -> (Object) t).findFirst();
    }

    /**
     * getServerPath.
     *
     * @return result
     * @since 0.1.0
     */

    @Override
    public String getServerPath() {
        return "http://localhost/mcp";
    }
}
