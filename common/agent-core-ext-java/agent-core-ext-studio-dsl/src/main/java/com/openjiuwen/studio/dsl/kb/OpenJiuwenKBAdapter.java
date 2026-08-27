/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.kb;

import java.util.function.Function;
import java.util.List;
import java.util.Map;

/**
 * OpenJiuwen local RAG adapter (Python {@code OpenJiuwenKBAdapter}).
 *
 * <p>Java local vector pipeline is host-provided via {@link #setSearchDelegate}; factory registration
 * ensures IR connector type {@code OpenJiuwen} resolves.
 *
 * @since 2026-08-27
 */

public final class OpenJiuwenKBAdapter implements KBServiceAdapter {
    @FunctionalInterface
    public interface SearchDelegate {
        List<KBSearchResult> search(
                String query,
                Map<String, Object> connectionConfig,
                List<Map<String, Object>> knowledgeBases,
                Map<String, Object> retrievalParams);
    }

    private static volatile SearchDelegate delegate;

    /**
     * Host wiring for local OpenJiuwen KB search.
     *
     * @param d d
     * @since 0.1.0
     */

    public static void setSearchDelegate(SearchDelegate d) {
        delegate = d;
    }

    /**
     * search.
     *
     * @param query query
     * @param connectionConfig connectionConfig
     * @param knowledgeBases knowledgeBases
     * @param retrievalParams retrievalParams
     * @return result
     * @since 0.1.0
     */

    @Override
    public List<KBSearchResult> search(
            String query,
            Map<String, Object> connectionConfig,
            List<Map<String, Object>> knowledgeBases,
            Map<String, Object> retrievalParams) {
        SearchDelegate d = delegate;
        if (d == null) {
            throw new IllegalStateException(
                    "OpenJiuwen KB connector requires host SearchDelegate "
                            + "(OpenJiuwenKBAdapter.setSearchDelegate)");
        }
        return d.search(query, connectionConfig, knowledgeBases, retrievalParams);
    }
}
