/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.util;

/**
 * Extracts assistant content from an OpenAI-compatible chat-completions JSON response.
 *
 * <p>Three providers, three field names for the same "thinking overflow" behavior:
 * <ul>
 *   <li><b>DeepSeek</b> (v4-flash / v4-pro): keeps {@code content} populated;
 *       {@code reasoning_content} coexists but is not needed.</li>
 *   <li><b>GLM-5.x</b>: may write into {@code reasoning_content} and leave
 *       {@code content} empty when token budget runs out.</li>
 *   <li><b>Qwen3.x</b> (OpenRouter): may write into {@code reasoning} and leave
 *       {@code content} as null.</li>
 * </ul>
 * Fallback chain: {@code content} → {@code reasoning_content} (GLM) →
 * {@code reasoning} (Qwen) → empty string.
 *
 * <p>Shared by PEV LlmClient, react-rails LlmClient, and e2e tests —
 * eliminates triplicated extraction logic.
 *
 * @since 2026-07
 */
public final class LlmResponseExtractor {
    private LlmResponseExtractor() {
    }

    /**
     * Extract assistant content from an OpenAI-compatible response JSON.
     *
     * <p>Tries {@code content} first, then falls back to
     * {@code reasoning_content} (GLM), then {@code reasoning} (Qwen).
     *
     * @param json raw response body
     * @return extracted content, or empty string when all fields are absent/empty
     */
    public static String extractContent(String json) {
        if (json == null || json.isEmpty()) {
            return "";
        }
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

    /**
     * Extract a JSON string field value by name (best-effort, no Jackson).
     *
     * @param json JSON text to inspect
     * @param field target field name
     * @return extracted string value, or {@code null} when field is absent
     */
    public static String extractField(String json, String field) {
        String search = "\"" + field + "\":\"";
        int idx = json.indexOf(search);
        if (idx < 0) {
            return "";
        }
        int start = idx + search.length();
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') {
                break;
            }
            if (c == '\\' && i + 1 < json.length()) {
                char n = json.charAt(++i);
                sb.append(unescapeChar(n));
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Map a JSON escape follow-up character to its unescaped form.
     *
     * @param n character immediately following a backslash in the JSON string
     * @return unescaped character (newline for {@code 'n'}, otherwise the char as-is)
     */
    private static char unescapeChar(char n) {
        return n == 'n' ? '\n' : n;
    }
}
