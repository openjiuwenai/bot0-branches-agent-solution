/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.flowmcp;

import com.openjiuwen.core.foundation.tool.mcp.McpServerConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * IR → {@link McpServerConfig} — 1:1 with Python {@code convert_ir_to_server_config}.
 *
 * @since 2026-08-26
 */

public final class IrToMcpServerConfig {
    private static final Set<String> SKIP_SNAKE = Set.of("headers", "auth");
    static final Set<String> MCP_PARAM_LOCATIONS =
            Set.of(McpToolParam.METHOD_BODY, McpToolParam.METHOD_HEADERS, McpToolParam.METHOD_QUERY);

    private IrToMcpServerConfig() {}

    /**
     * Convert Studio IR conf to core {@link McpServerConfig} and extract tool params.
     *
     * @param irConfig raw IR (camel or snake)
     * @return pair of config + deserialized tool params
     */

    public static Converted convert(Map<String, Object> irConfig) {
        Map<String, Object> ir = camelToSnake(irConfig, SKIP_SNAKE);

        Map<String, String> authHeaders = extractAuthHeaders(ir.get("headers"));
        authHeaders = extendHeaders(authHeaders, ir);

        String serverName = str(ir.get("name"));
        String serverId = serverName;
        String mcpIrId = str(ir.get("id"));
        if (!mcpIrId.isBlank()) {
            serverName = mcpIrId;
        }

        List<McpToolParam> toolParams = paramDeserialization(ir.get("arguments"), true);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("auth", ir.get("auth") instanceof Map<?, ?> ? mapOf(ir.get("auth")) : Map.of());
        params.put(
                "plugin_dependency",
                ir.get("plugin_dependency") instanceof Map<?, ?>
                        ? mapOf(ir.get("plugin_dependency"))
                        : (ir.get("pluginDependency") instanceof Map<?, ?>
                                ? mapOf(ir.get("pluginDependency"))
                                : Map.of()));
        params.put("tool_params", toolParams);
        params.put("mcp_choose_tools", ir.get("mcp_choose_tools"));
        params.put(
                "input_parameters",
                ir.get("input_parameters") instanceof Map<?, ?> ? mapOf(ir.get("input_parameters")) : Map.of());

        String transportType = str(ir.getOrDefault("type", "sse"));
        String clientType;
        if ("sse".equals(transportType) || "sse_new".equals(transportType)) {
            // Java core registers "sse"; Python uses SSEClientNew under sse_new
            clientType = "sse";
        } else if ("streamable_http".equals(transportType) || "streamable_http_new".equals(transportType)) {
            clientType = "streamable_http";
        } else {
            clientType = transportType;
        }

        String serverPath = str(ir.get("url"));
        if (!serverPath.isBlank()) {
            com.openjiuwen.studio.dsl.util.OutboundUrlSafety.validateOutbound(serverPath);
        }

        McpServerConfig config = McpServerConfig.builder()
                .serverId(serverId.isBlank() ? null : serverId)
                .serverName(serverName)
                .serverPath(serverPath)
                .clientType(clientType)
                .authHeaders(authHeaders)
                .params(params)
                .build();
        return new Converted(config, toolParams, transportType);
    }

    static List<McpToolParam> paramDeserialization(Object arguments, boolean allowSchemaEmpty) {
        List<McpToolParam> params = new ArrayList<>();
        if (!(arguments instanceof List<?> list)) {
            return params;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) {
                continue;
            }
            if (!m.containsKey("name") || !m.containsKey("description")) {
                if (!allowSchemaEmpty) {
                    throw FlowMcpErrors.of(
                            FlowMcpStatusCode.WORKFLOW_MCP_PARAM_TYPE_ERROR,
                            Map.of("param", "name/description", "type", "required"));
                }
            }
            McpToolParam p = McpToolParam.fromMap(m);
            if (!MCP_PARAM_LOCATIONS.contains(p.method())) {
                throw FlowMcpErrors.of(
                        FlowMcpStatusCode.WORKFLOW_MCP_PARAM_METHOD_ERROR, Map.of("method", p.method()));
            }
            params.add(p);
        }
        return params;
    }

    /**
     * Python {@code extends_headers} / {@code extend_heads} — identity by default.
     *
     * @param headers headers
     * @param ir ir
     * @return result
     * @since 0.1.0
     */

    static Map<String, String> extendHeaders(Map<String, String> headers, Map<String, Object> ir) {
        return headers;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> extractAuthHeaders(Object headersField) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!(headersField instanceof Map<?, ?> outer)) {
            return out;
        }
        // IR may be flat {"Authorization": "..."} or nested {"headers": {...}, "query": ...}
        Object nested = outer.get("headers");
        Map<?, ?> source = nested instanceof Map<?, ?> n ? n : outer;
        for (Map.Entry<?, ?> e : source.entrySet()) {
            String key = String.valueOf(e.getKey());
            if ("crypt_method".equals(key) || "query".equals(key) || "scope".equals(key) || "headers".equals(key)) {
                if ("headers".equals(key) && e.getValue() instanceof Map<?, ?> h) {
                    h.forEach((k, v) -> out.put(String.valueOf(k), v == null ? "" : String.valueOf(v)));
                }
                continue;
            }
            out.put(key, e.getValue() == null ? "" : String.valueOf(e.getValue()));
        }
        return out;
    }

    private static Map<String, Object> camelToSnake(Map<String, Object> src, Set<String> skip) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (src == null) {
            return out;
        }
        for (Map.Entry<String, Object> e : src.entrySet()) {
            String key = e.getKey();
            String lower = key == null ? "" : key.toLowerCase(Locale.ROOT);
            if (skip.contains(lower) || skip.contains(key)) {
                out.put(lower.equals("headers") || "headers".equals(key) ? "headers" : key, e.getValue());
                if ("pluginDependency".equals(key)) {
                    out.put("plugin_dependency", e.getValue());
                }
                continue;
            }
            out.put(toSnake(key), e.getValue());
            // keep original aliases used by FlowMcp
            if ("toolName".equals(key)) {
                out.put("tool_name", e.getValue());
            }
            if ("pluginDependency".equals(key)) {
                out.put("plugin_dependency", e.getValue());
            }
        }
        // flat headers key from IR like headers: {Authorization: ...}
        if (src.containsKey("headers") && !out.containsKey("headers")) {
            out.put("headers", src.get("headers"));
        }
        return out;
    }

    private static String toSnake(String camel) {
        if (camel == null || camel.isBlank()) {
        return "";
    }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static Map<String, Object> mapOf(Object raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> m) {
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
        }
        return out;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /**
     * Conversion result.
     *
     * @param config config
     * @param toolParams toolParams
     * @param transportType transportType
     * @return result
     * @since 0.1.0
     */

    public record Converted(McpServerConfig config, List<McpToolParam> toolParams, String transportType) {
        }
    }