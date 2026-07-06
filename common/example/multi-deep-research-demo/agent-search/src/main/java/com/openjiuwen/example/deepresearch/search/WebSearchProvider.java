/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.deepresearch.search;

import java.util.List;

/**
 * Pluggable backend behind the {@code web_search} skill.
 *
 * <p>Implementations: {@link TavilyWebSearchProvider} (prod, Tavily Search API)
 * and {@link StubWebSearchProvider} (fixture-based). A future Serper / Exa /
 * SearXNG backend only has to add a new implementation here.
 */
public interface WebSearchProvider {

    SearchResponse search(SearchRequest request);

    record SearchRequest(String query, int topK, TimeRange timeRange, Language language) {
    }

    record SearchResponse(List<Result> results) {
    }

    record Result(String url, String title, String snippet, SourceKind sourceKind, double score) {
    }

    enum TimeRange { YEAR, MONTH, WEEK, ALL }

    enum Language { ZH, EN, ANY }

    enum SourceKind { OFFICIAL, BLOG, NEWS, FORUM }
}
