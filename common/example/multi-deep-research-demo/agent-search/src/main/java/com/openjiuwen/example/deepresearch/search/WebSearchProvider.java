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
 *
 * @since 2026-07-06
 */
public interface WebSearchProvider {
    /**
     * Executes a search using the provider-specific backend.
     *
     * @param request the search request
     * @return the search response
     */
    SearchResponse search(SearchRequest request);

    /**
     * Search request payload.
     *
     * @param query the query string
     * @param topK maximum number of results (1..10)
     * @param timeRange recency window filter
     * @param language preferred language
     */
    record SearchRequest(String query, int topK, TimeRange timeRange, Language language) {
    }

    /**
     * Search response payload.
     *
     * @param results ordered list of matches (highest score first)
     */
    record SearchResponse(List<Result> results) {
    }

    /**
     * A single search hit.
     *
     * @param url the result URL
     * @param title the result title
     * @param snippet a short body excerpt
     * @param sourceKind the classified source type
     * @param score the ranker score
     */
    record Result(String url, String title, String snippet, SourceKind sourceKind, double score) {
    }

    /** Recency window filter values. */
    enum TimeRange {
        /** Restrict to results from the past year. */
        YEAR,
        /** Restrict to results from the past month. */
        MONTH,
        /** Restrict to results from the past week. */
        WEEK,
        /** No recency filter. */
        ALL
    }

    /** Preferred result language. */
    enum Language {
        /** Chinese. */
        ZH,
        /** English. */
        EN,
        /** No language preference. */
        ANY
    }

    /** Source classification tags used by the reranker. */
    enum SourceKind {
        /** Vendor / project official site. */
        OFFICIAL,
        /** Individual or corporate blog. */
        BLOG,
        /** News outlet. */
        NEWS,
        /** Community forum. */
        FORUM
    }
}
