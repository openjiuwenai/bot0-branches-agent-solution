/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.e2e;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 最小化 OpenAI 兼容聊天客户端 for react-rails real-LLM e2e。
 *
 * <p>仅用于真 LLM e2e 测试（自含 HTTP 实现，无外部依赖）。
 * 请求体构造与响应解析均为最小实现，不支持流式（本测试场景无需流式响应）。
 *
 * <p>env 配置：
 * <ul>
 *   <li>OPENJIUWEN_API_KEY — API 密钥（必填）</li>
 *   <li>OPENJIUWEN_BASE_URL — API 端点（必填）</li>
 *   <li>OPENJIUWEN_MODEL — 模型名（可选，缺省 glm-5.2）</li>
 *   <li>OPENJIUWEN_COMPLETIONS_PATH — 补全路径（可选，缺省 /chat/completions）</li>
 * </ul>
 */
final class LlmClient {

    private static final String BASE = System.getenv().getOrDefault("OPENJIUWEN_BASE_URL", "");
    private static final String PATH = System.getenv().getOrDefault("OPENJIUWEN_COMPLETIONS_PATH", "/chat/completions");
    private static final String MODEL = System.getenv().getOrDefault("OPENJIUWEN_MODEL", "glm-5.2");
    private static final String KEY = System.getenv("OPENJIUWEN_API_KEY");

    /**
     * 请求超时毫秒数（覆盖全部模型，含 GLM long-tail thinking）。
     * 从 120s 换算为毫秒（这是 HttpURLConnection 所需单位）。
     */
    private static final int REQUEST_TIMEOUT_MILLIS = 120_000;

    static boolean envPresent() {
        return KEY != null && !KEY.isBlank() && !BASE.isBlank();
    }

    /** 发送聊天请求并返回助手端响应内容（HttpURLConnection——标准 JDK，非第三方）。 */
    String chat(String userPrompt) {
        if (!envPresent()) {
            throw new IllegalStateException("OPENJIUWEN env not set");
        }
        String requestBody = buildRequestBody(userPrompt);
        return sendRequest(requestBody);
    }

    private String buildRequestBody(String userPrompt) {
        return """
                {"model":"%s","messages":[{"role":"user","content":%s}],"temperature":0.3,"thinking":{"type":"disabled"}}
                """.formatted(MODEL, jsonString(userPrompt));
    }

    private String sendRequest(String requestBody) {
        try {
            HttpURLConnection conn = openConnection(BASE + PATH);
            try {
                conn.setRequestProperty("Authorization", "Bearer " + KEY);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = requestBody.getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }

                int status = conn.getResponseCode();
                if (status != 200) {
                    String errorBody = readStream(conn.getErrorStream());
                    throw new RuntimeException("LLM HTTP " + status + ": "
                            + (errorBody != null ? errorBody.substring(0, Math.min(200, errorBody.length())) : "(no body)"));
                }

                String responseBody = readStream(conn.getInputStream());
                return extractContent(responseBody);
            } finally {
                conn.disconnect();
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("LLM call failed: " + e.getMessage(), e);
        }
    }

    private static HttpURLConnection openConnection(String urlString) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(REQUEST_TIMEOUT_MILLIS);
        conn.setReadTimeout(REQUEST_TIMEOUT_MILLIS);
        return conn;
    }

    private static String readStream(java.io.InputStream stream) throws Exception {
        if (stream == null) return null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder result = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line);
            }
            return result.toString();
        }
    }

    private static String jsonString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "") + "\"";
    }

    private static String extractContent(String json) {
        int idx = json.indexOf("\"content\":\"");
        if (idx < 0) return "";
        int start = idx + "\"content\":\"".length();
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char n = json.charAt(++i);
                sb.append(n == 'n' ? '\n' : n);
                continue;
            }
            if (c == '"') break;
            sb.append(c);
        }
        return sb.toString();
    }
}