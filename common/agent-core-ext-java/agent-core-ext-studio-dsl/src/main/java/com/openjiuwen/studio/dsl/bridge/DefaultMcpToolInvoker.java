/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.bridge;

import com.openjiuwen.studio.dsl.contract.McpToolInvoker;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Default MCP invoker: local tool table first; optional HTTP JSON endpoint via arguments.__endpoint__.
 *
 * @since 2026-08-17
 */
public final class DefaultMcpToolInvoker implements McpToolInvoker {
    private final Map<String, Function<Map<String, Object>, Map<String, Object>>> local =
            new ConcurrentHashMap<>();

    /**
     * register.
     *
     * @param server server
     * @param tool tool
     * @param fn fn
     */
    public void register(String server, String tool, Function<Map<String, Object>, Map<String, Object>> fn) {
        local.put(key(server, tool), fn);
    }

    /**
     * invoke.
     *
     * @param server server
     * @param tool tool
     * @param arguments arguments
     * @return result
     * @throws Exception when the call fails
     */
    @Override
    public Map<String, Object> invoke(String server, String tool, Map<String, Object> arguments) throws Exception {
        Function<Map<String, Object>, Map<String, Object>> fn = local.get(key(server, tool));
        if (fn != null) {
            return fn.apply(arguments == null ? Map.of() : arguments);
        }
        Object endpoint = arguments == null ? null : arguments.get("__endpoint__");
        if (endpoint == null) {
            throw new IllegalStateException("MCP tool not registered: " + key(server, tool));
        }
        String body = "{\"server\":\"" + esc(server) + "\",\"tool\":\"" + esc(tool) + "\",\"arguments\":"
                + toJson(arguments) + "}";
        return postJson(String.valueOf(endpoint), body);
    }

    private static Map<String, Object> postJson(String endpoint, String json) throws Exception {
        URLConnection raw = URI.create(endpoint).toURL().openConnection();
        if (!(raw instanceof HttpURLConnection conn)) {
            throw new IllegalStateException("endpoint is not HTTP: " + endpoint);
        }
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(30_000);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(payload.length);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload);
        }
        int status = conn.getResponseCode();
        String respBody;
        try (InputStream stream = status >= 400 ? conn.getErrorStream() : conn.getInputStream()) {
            respBody = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            conn.disconnect();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("statusCode", status);
        out.put("body", respBody);
        return out;
    }

    private static String key(String server, String tool) {
        return String.valueOf(server) + "::" + String.valueOf(tool);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String toJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if ("__endpoint__".equals(e.getKey())) {
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(esc(e.getKey())).append("\":");
            Object v = e.getValue();
            if (v == null) {
                sb.append("null");
            } else if (v instanceof Number || v instanceof Boolean) {
                sb.append(v);
            } else {
                sb.append('"').append(esc(String.valueOf(v))).append('"');
            }
        }
        sb.append('}');
        return sb.toString();
    }
}
