/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.flowmcp;

import com.openjiuwen.core.foundation.tool.mcp.McpClient;
import com.openjiuwen.core.foundation.tool.mcp.McpServerConfig;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MCP client wrapper — mirrors Python SSEClientNew/StreamableHttpClientNew runtime-kwargs handling:
 * pops {@link FlowMcpEngine#JIUWEN_RUNTIME_KWARGS} and merges {@code runtime_auth.headers} into
 * request auth headers before delegating to core {@link McpClient}.
 *
 * @since 2026-08-26
 */

final class RuntimeAwareMcpClient implements McpClient {
    private final McpClient delegate;
    private final McpServerConfig config;
    private final List<McpToolParam> toolParams;

    RuntimeAwareMcpClient(McpClient delegate, McpServerConfig config, List<McpToolParam> toolParams) {
        this.delegate = delegate;
        this.config = config;
        this.toolParams = toolParams == null ? List.of() : List.copyOf(toolParams);
    }

    List<McpToolParam> toolParams() {
        return toolParams;
    }
    McpServerConfig config() {
        return config;
    }

    /**
     * connect.
     *
     * @param retryTimes retryTimes
     * @param timeout timeout
     * @return result
     * @since 0.1.0
     *
     * @throws Exception when invocation fails
     */

    @Override
    public boolean connect(int retryTimes, float timeout) throws Exception {
        return delegate.connect(retryTimes, timeout);
    }

    /**
     * disconnect.
     *
     * @param timeout timeout
     * @return result
     * @since 0.1.0
     *
     * @throws Exception when invocation fails
     */

    @Override
    public boolean disconnect(float timeout) throws Exception {
        return delegate.disconnect(timeout);
    }

    /**
     * listTools.
     *
     * @param timeout timeout
     * @return result
     * @since 0.1.0
     *
     * @throws Exception when invocation fails
     */

    @Override
    public List<Object> listTools(float timeout) throws Exception {
        return delegate.listTools(timeout);
    }

    /**
     * callTool.
     *
     * @param toolName toolName
     * @param arguments arguments
     * @param timeout timeout
     * @return result
     * @since 0.1.0
     *
     * @throws Exception when invocation fails
     */

    @Override
    public Object callTool(String toolName, Map<String, Object> arguments, float timeout) throws Exception {
        Map<String, Object> args = arguments == null ? new HashMap<>() : new HashMap<>(arguments);
        Object kwargsRaw = args.remove(FlowMcpEngine.JIUWEN_RUNTIME_KWARGS);
        Map<String, String> extraHeaders = extractRuntimeHeaders(kwargsRaw);

        Map<String, String> auth = config.getAuthHeaders();
        Map<String, String> snapshot = auth == null ? Map.of() : new HashMap<>(auth);
        try {
            if (auth != null && !extraHeaders.isEmpty()) {
                auth.putAll(extraHeaders);
            }
            return delegate.callTool(toolName, args, timeout);
        } finally {
            if (auth != null) {
                auth.clear();
                auth.putAll(snapshot);
            }
        }
    }

    /**
     * getToolInfo.
     *
     * @param toolName toolName
     * @param timeout timeout
     * @return result
     * @since 0.1.0
     *
     * @throws Exception when invocation fails
     */

    @Override
    public Optional<Object> getToolInfo(String toolName, float timeout) throws Exception {
        return delegate.getToolInfo(toolName, timeout);
    }

    /**
     * getServerPath.
     *
     * @return result
     * @since 0.1.0
     */

    @Override
    public String getServerPath() {
        return delegate.getServerPath();
    }
    @SuppressWarnings("unchecked")
    private static Map<String, String> extractRuntimeHeaders(Object kwargsRaw) {
        Map<String, String> out = new HashMap<>();
        if (!(kwargsRaw instanceof Map<?, ?> kwargs)) {
            return out;
        }
        Object runtimeAuth = kwargs.get("runtime_auth");
        if (runtimeAuth instanceof Map<?, ?> ra) {
            Object headers = ra.get("headers");
            if (headers instanceof Map<?, ?> h) {
                h.forEach((k, v) -> out.put(String.valueOf(k), v == null ? "" : String.valueOf(v)));
            }
        }
        return out;
    }
}
