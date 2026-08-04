/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.deepresearch.agent.mcp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Library-tier POJO describing one MCP server the deep-research DeepAgent should attach to.
 *
 * <p>Deliberately transport-agnostic: any spec-compliant MCP server reachable via one of
 * core-java's supported client types ({@code streamable_http}, {@code sse}, {@code stdio})
 * can be swapped in by editing yaml only, with no code change on the agent side.
 *
 * @since 2026-07-07
 */
public class McpServerSetting {
    private String name = "";
    private String url = "";
    private String clientType = "streamable_http";
    private int connectTimeoutSeconds = 3;
    private int callTimeoutSeconds = 5;
    private Map<String, String> authHeaders = new LinkedHashMap<>();

    /**
     * Gets the server display name (used for logs and the {@code server_name} field).
     *
     * @return the server name
     */
    public String getName() {
        return name;
    }

    /**
     * Sets the server display name.
     *
     * @param name the server name
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * Gets the endpoint URL (or command line for stdio transports).
     *
     * @return the endpoint URL
     */
    public String getUrl() {
        return url;
    }

    /**
     * Sets the endpoint URL.
     *
     * @param url the endpoint URL
     */
    public void setUrl(String url) {
        this.url = url;
    }

    /**
     * Gets the core-java client type (one of {@code streamable_http}, {@code sse}, {@code stdio}).
     *
     * @return the client type
     */
    public String getClientType() {
        return clientType;
    }

    /**
     * Sets the core-java client type.
     *
     * @param clientType the client type
     */
    public void setClientType(String clientType) {
        this.clientType = clientType;
    }

    /**
     * Gets the startup-probe / connect timeout in seconds.
     *
     * @return the connect timeout in seconds
     */
    public int getConnectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    /**
     * Sets the startup-probe / connect timeout in seconds.
     *
     * @param connectTimeoutSeconds the connect timeout in seconds
     */
    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
    }

    /**
     * Gets the runtime tool-call timeout in seconds.
     *
     * @return the call timeout in seconds
     */
    public int getCallTimeoutSeconds() {
        return callTimeoutSeconds;
    }

    /**
     * Sets the runtime tool-call timeout in seconds.
     *
     * @param callTimeoutSeconds the call timeout in seconds
     */
    public void setCallTimeoutSeconds(int callTimeoutSeconds) {
        this.callTimeoutSeconds = callTimeoutSeconds;
    }

    /**
     * Gets the auth headers to attach to every JSON-RPC request.
     *
     * @return a mutable header map (never null)
     */
    public Map<String, String> getAuthHeaders() {
        return authHeaders;
    }

    /**
     * Sets the auth headers.
     *
     * @param authHeaders the auth header map
     */
    public void setAuthHeaders(Map<String, String> authHeaders) {
        this.authHeaders = authHeaders == null ? new LinkedHashMap<>() : authHeaders;
    }
}
