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
 */
public final class DomainReranker {

    private static final double OFFICIAL_WEIGHT = 2.0;
    private static final double BLOG_WEIGHT = 0.7;

    private DomainReranker() {
    }

    public static List<Result> rerank(List<Result> input) {
        List<Result> reweighted = new ArrayList<>(input.size());
        for (Result r : input) {
            reweighted.add(new Result(r.url(), r.title(), r.snippet(), r.sourceKind(), weight(r)));
        }
        reweighted.sort(Comparator.comparingDouble(Result::score).reversed());
        return reweighted;
    }

    private static double weight(Result r) {
        if (r.sourceKind() == SourceKind.OFFICIAL) {
            return r.score() * OFFICIAL_WEIGHT;
        }
        if (r.sourceKind() == SourceKind.BLOG) {
            return r.score() * BLOG_WEIGHT;
        }
        return r.score();
    }
}
