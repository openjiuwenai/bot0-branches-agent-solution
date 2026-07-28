/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.deepresearch;

import com.openjiuwen.core.common.exception.BaseError;
import com.openjiuwen.core.common.logging.LoggerProtocol;
import com.openjiuwen.core.common.logging.Loggers;
import com.openjiuwen.core.foundation.tool.mcp.McpServerConfig;
import com.openjiuwen.core.runner.Runner;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Probes configured MCP servers for reachability and registers the survivors with
 * {@link Runner#resourceMgr()}. Every failure is swallowed and logged — a broken or absent
 * MCP server MUST NEVER prevent the DeepAgent from starting or serving traffic.
 *
 * @since 2026-07-07
 */
public final class McpRegistrar {
    private static final LoggerProtocol LOG = Loggers.MCP;
    private static final String PROBE_METHOD = "initialize";
    private static final String PROBE_BODY = "{\"jsonrpc\":\"2.0\",\"id\":\"probe\",\"method\":\"" + PROBE_METHOD
            + "\",\"params\":{\"protocolVersion\":\"2024-11-05\","
            + "\"clientInfo\":{\"name\":\"deep-research-probe\",\"version\":\"0.1.0\"},"
            + "\"capabilities\":{}}}";

    private McpRegistrar() {
    }

    /**
     * Probes and registers each configured server, returning the names of servers
     * that were successfully registered. Never throws.
     *
     * @param settings user-configured MCP servers (may be empty or null)
     * @return the display names of the registered servers, in the order they were configured
     */
    public static List<String> probeAndRegister(List<McpServerSetting> settings) {
        List<String> registered = new ArrayList<>();
        if (settings == null || settings.isEmpty()) {
            return registered;
        }
        for (McpServerSetting setting : settings) {
            if (setting == null || setting.getUrl() == null || setting.getUrl().isBlank()) {
                continue;
            }
            if (!probeReachable(setting)) {
                LOG.warn("MCP server unreachable, skipping: name={}, url={}", setting.getName(), setting.getUrl());
                continue;
            }
            if (registerOne(setting)) {
                registered.add(setting.getName());
            }
        }
        return registered;
    }

    private static boolean probeReachable(McpServerSetting setting) {
        if (!"streamable_http".equals(setting.getClientType()) && !"sse".equals(setting.getClientType())) {
            return true;
        }
        HttpURLConnection connection = null;
        try {
            URL endpoint = URI.create(setting.getUrl()).toURL();
            URLConnection raw = endpoint.openConnection();
            if (!(raw instanceof HttpURLConnection httpConnection)) {
                LOG.debug("MCP probe skipped, non-HTTP URLConnection: name={}, url={}",
                        setting.getName(), setting.getUrl());
                return false;
            }
            connection = httpConnection;
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(setting.getConnectTimeoutSeconds() * 1000);
            connection.setReadTimeout(setting.getConnectTimeoutSeconds() * 1000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            for (var entry : setting.getAuthHeaders().entrySet()) {
                connection.setRequestProperty(entry.getKey(), entry.getValue());
            }
            try (OutputStream out = connection.getOutputStream()) {
                out.write(PROBE_BODY.getBytes(StandardCharsets.UTF_8));
            }
            int code = connection.getResponseCode();
            return code >= 200 && code < 500;
        } catch (IOException | RuntimeException e) {
            LOG.debug("MCP probe failed: name={}, url={}, error={}",
                    setting.getName(), setting.getUrl(), e.getMessage());
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static boolean registerOne(McpServerSetting setting) {
        try {
            McpServerConfig config = McpServerConfig.builder()
                    .serverName(setting.getName())
                    .serverPath(setting.getUrl())
                    .clientType(setting.getClientType())
                    .authHeaders(setting.getAuthHeaders())
                    .build();
            Runner.resourceMgr().addMcpServer(config, null, null);
            LOG.info("MCP server registered: name={}, url={}, clientType={}",
                    setting.getName(), setting.getUrl(), setting.getClientType());
            return true;
        } catch (BaseError e) {
            LOG.warn("MCP server registration failed (fail-open): name={}, url={}, error={}",
                    setting.getName(), setting.getUrl(), e.getMessage());
            return false;
        }
    }
}
