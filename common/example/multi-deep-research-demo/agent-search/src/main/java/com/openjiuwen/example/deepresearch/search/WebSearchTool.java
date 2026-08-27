/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.deepresearch.search;

import com.openjiuwen.example.deepresearch.search.WebSearchProvider.Language;
import com.openjiuwen.example.deepresearch.search.WebSearchProvider.Result;
import com.openjiuwen.example.deepresearch.search.WebSearchProvider.SearchRequest;
import com.openjiuwen.example.deepresearch.search.WebSearchProvider.SearchResponse;
import com.openjiuwen.example.deepresearch.search.WebSearchProvider.TimeRange;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Prod {@code web_search} tool bridge. Wraps a configured
 * {@link TavilyWebSearchProvider} without reading process environment variables.
 *
 * <p>Tool contract: {@code public Object search(Map<String,Object>)}, matching
 * the {@code LocalFunction} signature expected by agent-core-java's
 * {@code AbilityManager}. Runtime configuration is supplied through the constructor.
 *
 * @since 2026-07-06
 */
public final class WebSearchTool {
    private final WebSearchProvider provider;

    /**
     * Creates a production Tavily-backed web-search tool.
     *
     * @param apiKey configured Tavily API key
     */
    public WebSearchTool(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("Tavily API key must be configured");
        }
        this.provider = new TavilyWebSearchProvider(apiKey.trim());
    }

    WebSearchTool(WebSearchProvider provider) {
        this.provider = java.util.Objects.requireNonNull(provider, "provider");
    }

    /**
     * Executes a web search using the configured Tavily-backed provider.
     *
     * @param inputs raw tool inputs (keys: {@code query}, {@code top_k},
     *     {@code time_range}, {@code language})
     * @return a serialised search response, or an error map with an empty
     *     {@code results} list when the provider call fails
     */
    public Object search(Map<String, Object> inputs) {
        String query = stringInput(inputs, "query", "");
        if (query.isBlank()) {
            return error("query is required");
        }
        int topK = intInput(inputs, "top_k", 10);
        TimeRange timeRange = parseTimeRange(stringInput(inputs, "time_range", "all"));
        Language language = parseLanguage(stringInput(inputs, "language", "any"));

        SearchResponse response;
        try {
            response = provider.search(new SearchRequest(query, topK, timeRange, language));
        } catch (IllegalStateException ex) {
            return error("tavily_failed: " + ex.getMessage());
        }
        return WebSearchResultSerializer.serialize(response);
    }

    private static String stringInput(Map<String, Object> inputs, String key, String fallback) {
        Object value = inputs == null ? null : inputs.get(key);
        return value == null ? fallback : value.toString();
    }

    private static int intInput(Map<String, Object> inputs, String key, int fallback) {
        Object value = inputs == null ? null : inputs.get(key);
        if (value instanceof Number num) {
            return num.intValue();
        }
        if (value instanceof String str && !str.isBlank()) {
            try {
                return Integer.parseInt(str.trim());
            } catch (NumberFormatException ex) {
                return fallback;
            }
        }
        return fallback;
    }

    private static TimeRange parseTimeRange(String raw) {
        try {
            return TimeRange.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return TimeRange.ALL;
        }
    }

    private static Language parseLanguage(String raw) {
        try {
            return Language.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return Language.ANY;
        }
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("results", List.<Result>of());
        out.put("error", message);
        return out;
    }
}
