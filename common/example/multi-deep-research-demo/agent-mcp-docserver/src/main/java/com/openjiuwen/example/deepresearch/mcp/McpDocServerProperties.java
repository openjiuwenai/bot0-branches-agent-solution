/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.deepresearch.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * External configuration for the MCP doc server.
 *
 * @since 2026-07-07
 */
@ConfigurationProperties(prefix = "mcp-doc-server")
public class McpDocServerProperties {
    private String serverName = "deep-research-doc-lib";
    private String serverVersion = "0.1.0";
    private String fixturesClasspath = "fixtures/";
    private String jsonRpcPath = "/mcp";

    /**
     * Gets the advertised server name (returned in the initialize handshake).
     *
     * @return the server name
     */
    public String getServerName() {
        return serverName;
    }

    /**
     * Sets the advertised server name.
     *
     * @param serverName the server name
     */
    public void setServerName(String serverName) {
        this.serverName = serverName;
    }

    /**
     * Gets the advertised server version.
     *
     * @return the server version
     */
    public String getServerVersion() {
        return serverVersion;
    }

    /**
     * Sets the advertised server version.
     *
     * @param serverVersion the server version
     */
    public void setServerVersion(String serverVersion) {
        this.serverVersion = serverVersion;
    }

    /**
     * Gets the classpath prefix under which the fixture bundle lives.
     *
     * @return the classpath prefix (must end with {@code /})
     */
    public String getFixturesClasspath() {
        return fixturesClasspath;
    }

    /**
     * Sets the classpath prefix for fixture resolution.
     *
     * @param fixturesClasspath the classpath prefix
     */
    public void setFixturesClasspath(String fixturesClasspath) {
        this.fixturesClasspath = fixturesClasspath;
    }

    /**
     * Gets the JSON-RPC endpoint path (as advertised to clients).
     *
     * @return the endpoint path
     */
    public String getJsonRpcPath() {
        return jsonRpcPath;
    }

    /**
     * Sets the JSON-RPC endpoint path.
     *
     * @param jsonRpcPath the endpoint path
     */
    public void setJsonRpcPath(String jsonRpcPath) {
        this.jsonRpcPath = jsonRpcPath;
    }
}
