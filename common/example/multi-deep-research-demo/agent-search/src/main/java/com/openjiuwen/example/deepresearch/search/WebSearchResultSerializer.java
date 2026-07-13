/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.deepresearch.search;

import com.openjiuwen.example.deepresearch.search.WebSearchProvider.Result;
import com.openjiuwen.example.deepresearch.search.WebSearchProvider.SearchResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Serialises {@link SearchResponse} into the wire shape:
 * <pre>{@code
 * { "results": [ { url, title, snippet, source_kind, score } ] }
 * }</pre>
 * Centralised so prod and stub tool bridges emit byte-identical output.
 *
 * @since 2026-07-06
 */
final class WebSearchResultSerializer {
    private WebSearchResultSerializer() {
    }

    static Map<String, Object> serialize(SearchResponse response) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Result result : response.results()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("url", result.url());
            entry.put("title", result.title());
            entry.put("snippet", result.snippet());
            entry.put("source_kind", result.sourceKind().name().toLowerCase(Locale.ROOT));
            entry.put("score", result.score());
            results.add(entry);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("results", results);
        return out;
    }
}
