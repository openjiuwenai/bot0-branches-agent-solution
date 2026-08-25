/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.loopforest.search;

import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.foundation.tool.ToolCard;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Wikipedia 搜索工具——opensearch 找标题 + summary 取摘要（graph-loop e2e 用）。
 *
 * <p>两步查询：① opensearch 定位文章 ② REST summary 获取正文摘要。
 * 搜索质量远超 DDG Instant Answer；需代理（GFW）——SEARCH_PROXY_HOST 环境变量。
 *
 * @since 2026-08
 */
public class WikipediaSearchTool extends Tool {

    public static final String TOOL_NAME = "web_search";

    private final HttpClient httpClient;

    public WikipediaSearchTool() {
        super(ToolCard.builder()
                .id(TOOL_NAME)
                .name(TOOL_NAME)
                .description("Search Wikipedia for factual information. "
                        + "Input a search query (short keywords work best, "
                        + "e.g. 'Guido van Rossum' not 'who created Python'). "
                        + "Returns matching articles with summaries.")
                .inputParams(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "query", Map.of("type", "string",
                                        "description", "Search keywords (2-5 words works best)")),
                        "required", List.of("query")))
                .build());
        var builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15));
        String proxyHost = System.getenv("SEARCH_PROXY_HOST");
        if (proxyHost != null && !proxyHost.isBlank()) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxyHost,
                    Integer.parseInt(System.getenv().getOrDefault("SEARCH_PROXY_PORT", "7897")))));
        }
        this.httpClient = builder.build();
    }

    @Override
    public Object invoke(Map<String, Object> args, Map<String, Object> kwargs) {
        String query = String.valueOf(args.getOrDefault("query", ""));
        if (query.isBlank()) {
            return Map.of("error", "query is required");
        }
        try {
            return Map.of("query", query, "result", searchAndSummarize(query));
        } catch (InterruptedException e) {
            // 仅真中断才恢复标志（无条件 interrupt 会毒化宿主线程）
            Thread.currentThread().interrupt();
            return Map.of("error", "search interrupted");
        } catch (Exception e) {
            return Map.of("error", "search failed: " + e.getMessage());
        }
    }

    @Override
    public java.util.Iterator<Object> stream(Map<String, Object> args,
            Map<String, Object> kwargs) throws Exception {
        return List.of((Object) invoke(args, kwargs)).iterator();
    }

    private String searchAndSummarize(String query) throws Exception {
        // Step 1: opensearch 找标题
        String searchUrl = "https://en.wikipedia.org/w/api.php?action=opensearch"
                + "&search=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&format=json&limit=3";
        String searchBody = httpGet(searchUrl);
        String firstTitle = extractFirstTitle(searchBody);
        if (firstTitle == null) {
            return "No Wikipedia articles found for: " + query
                    + " (try shorter keywords)";
        }
        // Step 2: 获取摘要
        String summaryUrl = "https://en.wikipedia.org/api/rest_v1/page/summary/"
                + URLEncoder.encode(firstTitle.replace(" ", "_"), StandardCharsets.UTF_8);
        String summaryBody = httpGet(summaryUrl);
        return extractSummary(summaryBody, firstTitle);
    }

    private String httpGet(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "graph-loop-e2e/1.0 (research)")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return "{}";
        }
        return response.body();
    }

    /**
     * 从 opensearch 响应提取首个标题。
     *
     * <p>opensearch 结构 [query, [titles], [descs], [urls]]——正则锚定
     * {@code ["<query>",["<title>"]}。精确命中（单元素数组）与模糊命中（多元素）同样生效。
     *
     * @param body opensearch JSON 响应
     * @return 首标题；无结果返回 null
     */
    static String extractFirstTitle(String body) {
        var segment = java.util.regex.Pattern
                .compile("\\[\"[^\"]*\",\\[\"([^\"]+)\"").matcher(body);
        return segment.find() ? segment.group(1) : null;
    }

    static String extractSummary(String json, String title) {
        StringBuilder sb = new StringBuilder();
        sb.append("Title: ").append(title).append("\n");
        // 线性扫描提取（长 extract 不再触发正则递归 StackOverflowError）
        String extract = JsonStringScan.stringValueOf(json, "extract");
        if (extract != null) {
            sb.append("Summary: ").append(extract).append("\n");
        }
        String description = JsonStringScan.stringValueOf(json, "description");
        if (description != null) {
            sb.append("Type: ").append(description).append("\n");
        }
        return sb.length() > 20 ? sb.toString() : "No summary available for: " + title;
    }
}
