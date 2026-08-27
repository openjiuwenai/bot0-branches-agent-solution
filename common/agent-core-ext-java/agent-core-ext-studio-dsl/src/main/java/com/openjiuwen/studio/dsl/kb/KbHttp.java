/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.kb;

import com.openjiuwen.studio.dsl.python.SubprocessPythonCodeExecutor;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal HTTP JSON helper for KB adapters (JDK HttpClient).
 *
 * @since 2026-08-25
 */

public final class KbHttp {
    private static final HttpClient CLIENT =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private KbHttp() {}

    /**
     * postJson.
     *
     * @param url url
     * @param headers headers
     * @param body body
     * @return parsed JSON object
     */

    @SuppressWarnings("unchecked")
    public static Map<String, Object> postJson(String url, Map<String, String> headers, Map<String, Object> body) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(toJson(body), StandardCharsets.UTF_8));
            if (headers != null) {
                headers.forEach(b::header);
            }
            if (headers == null || !headers.containsKey("Content-Type")) {
                b.header("Content-Type", "application/json");
            }
            HttpResponse<String> resp = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                String t = resp.body() == null ? "" : resp.body();
                throw new IllegalStateException(
                        "KB API error: status=" + resp.statusCode() + ", body=" + truncate(t));
            }
            String raw = resp.body() == null ? "{}" : resp.body().trim();
            if (raw.isEmpty()) {
                return Map.of();
            }
            Object parsed = SubprocessPythonCodeExecutor.SimpleJson.parse(raw);
            if (parsed instanceof Map<?, ?> m) {
                Map<String, Object> out = new LinkedHashMap<>();
                m.forEach((k, v) -> out.put(String.valueOf(k), v));
                return out;
            }
            throw new IllegalStateException("KB API response is not a JSON object");
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("KB HTTP request failed: " + e.getMessage(), e);
        }
    }

    /**
     * toJson.
     *
     * @param value value
     * @return json
     */

    public static String toJson(Object value) {
        if (value == null) {
        return "null";
    }
        if (value instanceof String s) {
            return '"' + escape(s) + '"';
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(escape(String.valueOf(e.getKey()))).append('"').append(':');
                sb.append(toJson(e.getValue()));
            }
            return sb.append('}').toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object o : list) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(toJson(o));
            }
            return sb.append(']').toString();
        }
        return '"' + escape(String.valueOf(value)) + '"';
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
    private static String truncate(String s) {
        return s.length() <= 500 ? s : s.substring(0, 500) + "...";
    }
    static int intOf(Object o, int def) {
        if (o instanceof Number n) {
        return n.intValue();
    }
        if (o == null) {
            return def;
        }
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    static double doubleOf(Object o, double def) {
        if (o instanceof Number n) {
        return n.doubleValue();
    }
        if (o == null) {
            return def;
        }
        try {
            return Double.parseDouble(String.valueOf(o));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
    @SuppressWarnings("unchecked")
    static Map<String, Object> mapOf(Object o) {
        if (!(o instanceof Map<?, ?> m)) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }
}
