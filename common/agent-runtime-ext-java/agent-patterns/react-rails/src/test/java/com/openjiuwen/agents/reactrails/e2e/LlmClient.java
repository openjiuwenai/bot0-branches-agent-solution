/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.e2e;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Minimal OpenAI-compatible chat client for react-rails real-LLM e2e.
 * Copies the PEV module's LlmClient pattern (thinking:disabled, env-gated).
 */
final class LlmClient {

    private static final String BASE = System.getenv().getOrDefault("OPENJIUWEN_BASE_URL", "");
    private static final String PATH = System.getenv().getOrDefault("OPENJIUWEN_COMPLETIONS_PATH", "/chat/completions");
    private static final String MODEL = System.getenv().getOrDefault("OPENJIUWEN_MODEL", "glm-5.2");
    private static final String KEY = System.getenv("OPENJIUWEN_API_KEY");

    static boolean envPresent() {
        return KEY != null && !KEY.isBlank() && !BASE.isBlank();
    }

    // HttpClient with default settings (standard JDK API, not derived from any third-party source).
    private final HttpClient http = HttpClient.newHttpClient();

    String chat(String userPrompt) {
        if (!envPresent()) throw new IllegalStateException("OPENJIUWEN env not set");
        String body = """
                {"model":"%s","messages":[{"role":"user","content":%s}],"temperature":0.3,"thinking":{"type":"disabled"}}
                """.formatted(MODEL, jsonString(userPrompt));
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(BASE + PATH))
                .header("Authorization", "Bearer " + KEY)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200)
                throw new RuntimeException("LLM HTTP " + resp.statusCode() + ": " + resp.body().substring(0, Math.min(200, resp.body().length())));
            return extractContent(resp.body());
        } catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new RuntimeException("LLM call failed: " + e.getMessage(), e); }
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