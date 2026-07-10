/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.pev.e2e;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

/**
 * Minimal OpenAI-compatible chat-completions client for the real-LLM e2e test.
 *
 * <p>Test-scope only — proves the PEV data channel against a real LLM (OPENJIUWEN_*
 * env, BigModel/GLM). Uses only {@code java.net.http} (no extra dep). The response
 * parsing is intentionally minimal (soft-observe e2e, not a production client).
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

    /**
     * Send a single user message, return the assistant content (empty string on any parse failure).
     *
     * <p>Infrastructure outages (connect/read timeout, non-200 from the gateway, I/O errors)
     * surface as {@link LlmUnavailableException} — a distinct, catchable type so real-LLM e2e
     * tests can honestly soft-skip on a runtime/env outage instead of hard-failing the suite
     * (honesty split, 铁律①: an unavailable endpoint is an env signal, not a logic defect).
     */
    String chat(String userPrompt) {
        if (!envPresent()) {
            throw new IllegalStateException("OPENJIUWEN_API_KEY / BASE_URL not set");
        }
        String url = BASE + PATH;
        // Thinking control — env LLM_THINKING selects the param shape per provider/model:
        //   glm-off (default): GLM-5.2 "thinking":{"type":"disabled"} — GLM-5.2 thinking on complex
        //     prompts produces 16-47KB reasoning + 74-211s+ variance (verified), so the structured
        //     e2e tasks (plan JSON, PASS/FAIL, summarize) disable it (→ ~3s).
        //   qwen-off / qwen-on: Qwen3 "enable_thinking":false/true — Qwen3.5-flash thinking is
        //     bounded (~5-25s), works in both modes; lets the cross-model e2e compare.
        //   thinking-on / thinking-off: DeepSeek "thinking":{"type":"enabled/disabled"}.
        //   none: emit no thinking param (model default).
        String mode = System.getenv().getOrDefault("LLM_THINKING", "glm-off");
        String thinkingJson = switch (mode) {
            case "qwen-off" -> "\"enable_thinking\":false";
            case "qwen-on" -> "\"enable_thinking\":true";
            case "thinking-on" -> "\"thinking\":{\"type\":\"enabled\"}";
            case "thinking-off" -> "\"thinking\":{\"type\":\"disabled\"}";
            case "none" -> "";
            default -> "\"thinking\":{\"type\":\"disabled\"}";
        };
        String body = """
                {"model":"%s","messages":[{"role":"user","content":%s}],"temperature":0.3%s%s}
                """.formatted(MODEL, jsonString(userPrompt), thinkingJson.isEmpty() ? "" : ",", thinkingJson);
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).header("Authorization", "Bearer " + KEY)
                .header("Content-Type", "application/json")
                // 300s covers GLM-4.7 thinking=on complex scenarios (ClaimsAdjudication
                // takes 20s off / 120s+ on). The prior 120s cap caused false soft-skips.
                // reasoning_content fallback (GLM/Qwen) fixed the infinite-loop root cause.
                .timeout(Duration.ofSeconds(300)).POST(HttpRequest.BodyPublishers.ofString(body)).build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                // Gateway / rate-limit / 5xx — treat as infra outage (soft-skip), not a logic bug.
                throw new LlmUnavailableException("LLM HTTP " + resp.statusCode() + ": " + safeBody(resp.body()));
            }
            return extractContent(resp.body());
        } catch (LlmUnavailableException e) {
            throw e;
        } catch (HttpTimeoutException e) {
            // 0-byte / read-timeout — the GLM endpoint silently dropping large planner prompts.
            // Honest signal: env/runtime unavailable, not a control-flow defect.
            throw new LlmUnavailableException("LLM request timed out: " + e.getMessage(), e);
        } catch (java.io.IOException e) {
            // Connection reset / unreachable host — infra outage.
            throw new LlmUnavailableException("LLM I/O error: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            throw new LlmUnavailableException("LLM call interrupted: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw e;
        }
    }

    private static String safeBody(String body) {
        return body == null ? "(no body)" : body.substring(0, Math.min(200, body.length()));
    }

    private static String jsonString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace(String.valueOf((char) 10), "\\" + "n")
                .replace(String.valueOf((char) 13), "") + "\"";
    }

    /**
     * Extract {@code choices[0].message.content} from an OpenAI-compatible response (best-effort).
     *
     * <p>Three providers, three field names for the same "thinking overflow" behavior:
     * <ul>
     *   <li><b>GLM-5.2</b>: writes into {@code reasoning_content}, leaves {@code content} empty
     *       when the token budget runs out before a final answer.</li>
     *   <li><b>Qwen3.5-35B</b> (OpenRouter): writes into {@code reasoning} — always present
     *       even with {@code enable_thinking:false}, and content can be empty on length-limited
     *       or complex prompts (same mechanism: token budget exhausted).</li>
     *   <li><b>DeepSeek</b> (v4-flash / v4-pro): keeps {@code content} populated in both
     *       {@code thinking:enabled/disabled} modes; {@code reasoning_content} coexists but
     *       is not needed as a fallback.</li>
     * </ul>
     * Fallback chain: content → reasoning_content (GLM) → reasoning (Qwen) → "".
     */
    private static String extractContent(String json) {
        String content = extractJsonStringField(json, "content");
        if (content != null && !content.isEmpty()) {
            return content;
        }

        String reasoningContent = extractJsonStringField(json, "reasoning_content");
        if (reasoningContent != null && !reasoningContent.isEmpty()) {
            return reasoningContent;
        }

        String reasoning = extractJsonStringField(json, "reasoning");
        if (reasoning != null && !reasoning.isEmpty()) {
            return reasoning;
        }

        return "";
    }

    /**
     * Extract the JSON string value for a given field name (best-effort, no Jackson).
     */
    private static String extractJsonStringField(String json, String field) {
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

    /**
     * Raised when the LLM endpoint is unavailable (timeout / I/O / non-200). Real-LLM e2e
     * tests catch this to soft-skip — honoring the honesty split: a runtime/env outage is
     * reported as skipped-with-reason, never as a red test that masks a real defect.
     */
    static final class LlmUnavailableException extends RuntimeException {
        LlmUnavailableException(String msg) {
            super(msg);
        }
        LlmUnavailableException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }
}
