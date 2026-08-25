/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.loopforest.search;

import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.foundation.tool.ToolCard;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DuckDuckGo 即时搜索工具——免费零 key 的 web 搜索（graph-loop e2e 用）。
 *
 * <p>使用 DuckDuckGo Instant Answer API（免费、无需注册）：
 * {@code https://api.duckduckgo.com/?q=QUERY&format=json}
 *
 * <p>返回结构：相关主题摘要 + 即时答案（若有）。非完整搜索结果页面——
 * 对于 DeepResearch 多跳任务，模型需要多次调用以交叉引用。
 *
 * <p><b>Honest boundary</b>：DDG Instant Answer 覆盖有限（非完整搜索引擎 API）；
 * 更丰富的搜索需 Brave（免费 2000/月）或 Tavily（免费 1000/月）。MVP 够用。
 *
 * @since 2026-08
 */
public class DuckDuckGoSearchTool extends Tool {

    /** 工具名（LLM 可见）。 */
    public static final String TOOL_NAME = "web_search";

    private final HttpClient httpClient;

    /**
     * 构造 DDG 搜索工具。
     */
    public DuckDuckGoSearchTool() {
        super(ToolCard.builder()
                .id(TOOL_NAME)
                .name(TOOL_NAME)
                .description("Search the web using DuckDuckGo. Input a search query, "
                        + "returns related topics and abstract. Call multiple times "
                        + "with different queries for multi-hop research.")
                .inputParams(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "query", Map.of("type", "string",
                                        "description", "The search query text")),
                        "required", java.util.List.of("query")))
                .build());
        java.net.http.HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15));
        // GFW 环境：SEARCH_PROXY_HOST 设置时走代理（如 127.0.0.1:7897 Clash）
        String proxyHost = System.getenv("SEARCH_PROXY_HOST");
        if (proxyHost != null && !proxyHost.isBlank()) {
            int proxyPort = Integer.parseInt(
                    System.getenv().getOrDefault("SEARCH_PROXY_PORT", "7897"));
            builder.proxy(java.net.ProxySelector.of(
                    new java.net.InetSocketAddress(proxyHost, proxyPort)));
        }
        this.httpClient = builder.build();
    }

    @Override
    public Object invoke(Map<String, Object> args, Map<String, Object> kwargs) {
        String query = String.valueOf(args.getOrDefault("query", ""));
        if (query.isBlank()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "query is required");
            return err;
        }
        try {
            String result = search(query);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("query", query);
            out.put("result", result);
            return out;
        } catch (IOException | InterruptedException e) {
            // 恢复中断状态
            Thread.currentThread().interrupt();
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "search failed: " + e.getMessage());
            return err;
        }
    }

    /**
     * 调用 DDG Instant Answer API。
     *
     * @param query 搜索词
     * @return 搜索结果文本（摘要/相关主题）
     * @throws IOException 网络错误
     * @throws InterruptedException 被中断
     */
    @Override
    public java.util.Iterator<Object> stream(Map<String, Object> args,
            Map<String, Object> kwargs) throws Exception {
        return java.util.List.of((Object) invoke(args, kwargs)).iterator();
    }

    private String search(String query) throws IOException, InterruptedException {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = "https://api.duckduckgo.com/?q=" + encoded + "&format=json&no_html=1";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "graph-loop-e2e/1.0")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return "HTTP " + response.statusCode() + " from DuckDuckGo";
        }
        return extractAbstract(response.body());
    }

    /**
     * 从 DDG JSON 响应中提取有用文本（极简提取——不引入 JSON 库）。
     * DDG Instant Answer 返回 AbstractText + RelatedTopics[].Text。
     *
     * @param json DDG JSON 响应
     * @return 提取的文本（摘要 + 最多 5 条相关主题）
     */
    static String extractAbstract(String json) {
        StringBuilder sb = new StringBuilder();
        // AbstractText
        java.util.regex.Matcher am = java.util.regex.Pattern
                .compile("\"AbstractText\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        if (am.find()) {
            sb.append("Abstract: ").append(unescape(am.group(1))).append("\n");
        }
        // AbstractURL
        java.util.regex.Matcher um = java.util.regex.Pattern
                .compile("\"AbstractURL\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        if (um.find()) {
            sb.append("Source: ").append(unescape(um.group(1))).append("\n");
        }
        // RelatedTopics Text（最多 5 条）
        java.util.regex.Matcher tm = java.util.regex.Pattern
                .compile("\"Text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.){20,})\"").matcher(json);
        int count = 0;
        while (tm.find() && count < 5) {
            sb.append("- ").append(unescape(tm.group(1))).append("\n");
            count++;
        }
        // Answer（若有）
        java.util.regex.Matcher an = java.util.regex.Pattern
                .compile("\"Answer\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        if (an.find() && an.group(1).length() > 2) {
            sb.append("Answer: ").append(unescape(an.group(1))).append("\n");
        }
        return sb.length() > 0 ? sb.toString() : "No results found (DDG Instant Answer coverage is limited)";
    }

    private static String unescape(String s) {
        return s.replace("\\\\", "\\").replace("\\\"", "\"").replace("\\n", "\n");
    }
}
