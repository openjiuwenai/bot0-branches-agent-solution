/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.e2e;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

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

    static boolean envPresent() {
        return KEY != null && !KEY.isBlank() && !BASE.isBlank();
    }

    // HttpClient with default settings (standard JDK API).
    private final HttpClient http = HttpClient.newHttpClient();

    /**
     * 发送聊天请求并返回助手端响应内容。
     */
    String chat(String userPrompt) {
        if (!envPresent()) {
            throw new IllegalStateException("OPENJIUWEN env not set");
        }
        String body = """
                {"model":"%s","messages":[{"role":"user","content":%s}],
                "temperature":0.3,"thinking":{"type":"disabled"}}
                """.replace(System.lineSeparator(), "").formatted(MODEL, jsonString(userPrompt));
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(BASE + PATH)).header("Authorization", "Bearer " + KEY)
                .header("Content-Type", "application/json").timeout(Duration.ofSeconds(300))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new LlmCallException("LLM HTTP " + resp.statusCode() + ": "
                        + resp.body().substring(0, Math.min(200, resp.body().length())));
            }
            return extractContent(resp.body());
        } catch (HttpTimeoutException e) {
            throw new LlmCallException("LLM request timed out: " + e.getMessage(), e);
        } catch (java.io.IOException e) {
            throw new LlmCallException("LLM I/O error: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            throw new LlmCallException("LLM call interrupted: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw e;
        }
    }

    private static String jsonString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace(String.valueOf((char) 10), "\\" + "n")
                .replace(String.valueOf((char) 13), "") + "\"";
    }

    private static String extractContent(String json) {
        String content = extractField(json, "content");
        if (content != null && !content.isEmpty()) {
            return content;
        }
        String reasoningContent = extractField(json, "reasoning_content");
        if (reasoningContent != null && !reasoningContent.isEmpty()) {
            return reasoningContent;
        }
        String reasoning = extractField(json, "reasoning");
        if (reasoning != null && !reasoning.isEmpty()) {
            return reasoning;
        }
        return "";
    }

    private static String extractField(String json, String field) {
        String search = "\"" + field + "\":\"";
        int idx = json.indexOf(search);
        if (idx < 0) {
            return "";
        }
        int start = idx + search.length();
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char n = json.charAt(++i);
                sb.append(n == 'n' ? (char) 10 : n);
                continue;
            }
            if (c == '"') {
                break;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    static final class LlmCallException extends RuntimeException {
        LlmCallException(String message) {
            super(message);
        }

        LlmCallException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
