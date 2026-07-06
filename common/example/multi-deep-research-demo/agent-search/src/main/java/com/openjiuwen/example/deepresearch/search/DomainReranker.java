/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.deepresearch.search;

import com.openjiuwen.example.deepresearch.search.WebSearchProvider.Result;
import com.openjiuwen.example.deepresearch.search.WebSearchProvider.SourceKind;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Post-search reranker. Vendor official hosts get ×2, generic blog hosts get
 * ×0.7, everything else is unchanged. We rerank after Tavily already returned
 * its own score so the upstream ranking is preserved for non-classified hosts.
 *
 * @since 2026-07-06
 */
public final class DomainReranker {
    private static final double OFFICIAL_WEIGHT = 2.0;
    private static final double BLOG_WEIGHT = 0.7;

    private DomainReranker() {
    }

    /**
     * Applies the source-kind weighting and returns a score-descending list.
     *
     * @param input the raw ranked list from the upstream provider
     * @return a new list sorted by reweighted score (highest first)
     */
    public static List<Result> rerank(List<Result> input) {
        List<Result> reweighted = new ArrayList<>(input.size());
        for (Result result : input) {
            reweighted.add(new Result(result.url(), result.title(), result.snippet(),
                    result.sourceKind(), weight(result)));
        }
        reweighted.sort(Comparator.comparingDouble(Result::score).reversed());
        return reweighted;
    }

    private static double weight(Result result) {
        if (result.sourceKind() == SourceKind.OFFICIAL) {
            return result.score() * OFFICIAL_WEIGHT;
        }
        if (result.sourceKind() == SourceKind.BLOG) {
            return result.score() * BLOG_WEIGHT;
        }
        return result.score();
    }
}
