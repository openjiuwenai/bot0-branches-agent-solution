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
 */
final class WebSearchResultSerializer {

    private WebSearchResultSerializer() {
    }

    static Map<String, Object> serialize(SearchResponse response) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Result r : response.results()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("url", r.url());
            entry.put("title", r.title());
            entry.put("snippet", r.snippet());
            entry.put("source_kind", r.sourceKind().name().toLowerCase(Locale.ROOT));
            entry.put("score", r.score());
            results.add(entry);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("results", results);
        return out;
    }
}
