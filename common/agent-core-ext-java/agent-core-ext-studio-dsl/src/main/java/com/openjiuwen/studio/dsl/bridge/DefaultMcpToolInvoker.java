package com.openjiuwen.studio.dsl.bridge;

import com.openjiuwen.studio.dsl.spi.McpToolInvoker;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Default MCP invoker: local tool table first; optional HTTP JSON endpoint via arguments.__endpoint__.
 */
public final class DefaultMcpToolInvoker implements McpToolInvoker {
    private final Map<String, Function<Map<String, Object>, Map<String, Object>>> local =
            new ConcurrentHashMap<>();

    public void register(String server, String tool, Function<Map<String, Object>, Map<String, Object>> fn) {
        local.put(key(server, tool), fn);
    }

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
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(String.valueOf(endpoint)))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("statusCode", resp.statusCode());
        out.put("body", resp.body());
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
