/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.deepresearch.search;

import com.openjiuwen.example.deepresearch.search.WebSearchProvider.Language;
import com.openjiuwen.example.deepresearch.search.WebSearchProvider.SearchRequest;
import com.openjiuwen.example.deepresearch.search.WebSearchProvider.SearchResponse;
import com.openjiuwen.example.deepresearch.search.WebSearchProvider.TimeRange;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Stub {@code web_search} tool bridge backed by {@link StubWebSearchProvider}.
 * Same I/O contract as {@link WebSearchTool} — only the tool implementation
 * class differs between stub and prod, picked by the wrapper's Spring profile.
 *
 * @since 2026-07-06
 */
public final class StubWebSearchTool {
    private static final WebSearchProvider PROVIDER = new StubWebSearchProvider();

    private StubWebSearchTool() {
    }

    /**
     * Executes a fixture-backed web search.
     *
     * @param inputs raw tool inputs (keys: {@code query}, {@code top_k},
     *     {@code time_range}, {@code language})
     * @return a serialised search response, or an error map when {@code query} is blank
     */
    public static Object search(Map<String, Object> inputs) {
        String query = stringInput(inputs, "query", "");
        if (query.isBlank()) {
            return error("query is required");
        }
        int topK = intInput(inputs, "top_k", 10);
        TimeRange timeRange = parseTimeRange(stringInput(inputs, "time_range", "all"));
        Language language = parseLanguage(stringInput(inputs, "language", "any"));

        SearchResponse response = PROVIDER.search(new SearchRequest(query, topK, timeRange, language));
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
        out.put("results", List.of());
        out.put("error", message);
        return out;
    }
}
